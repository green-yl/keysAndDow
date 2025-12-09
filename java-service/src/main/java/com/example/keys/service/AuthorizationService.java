package com.example.keys.service;

import com.example.keys.model.*;
import com.example.keys.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AuthorizationService {
    
    @Autowired
    private LicenseCodeRepository licenseCodeRepository;
    
    @Autowired
    private LicenseRepository licenseRepository;
    
    @Autowired
    private PlanRepository planRepository;
    
    @Autowired
    private DownloadTokenRepository downloadTokenRepository;
    
    @Autowired
    private DownloadEventRepository downloadEventRepository;
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    @Autowired
    private LicenseSignatureService licenseSignatureService;
    
    @Autowired
    private RateLimitService rateLimitService;
    
    @Autowired
    private ServerManagementService serverManagementService;
    
    /**
     * 激活许可证
     */
    @Transactional
    public Map<String, Object> activateLicense(String code, String hwid, String sub, Map<String, Object> clientInfo, String serverIp) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 0. 限流检查
            if (!rateLimitService.allowGlobal()) {
                result.put("ok", false);
                result.put("error", "系统繁忙，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            if (!rateLimitService.allowActivate(serverIp)) {
                result.put("ok", false);
                result.put("error", "激活过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            if (!rateLimitService.allowDevice(hwid)) {
                result.put("ok", false);
                result.put("error", "设备操作过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            if (!rateLimitService.allowCodeQuery(code)) {
                result.put("ok", false);
                result.put("error", "激活码查询过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            // 1. 检查激活码
            Optional<LicenseCode> licenseCodeOpt = licenseCodeRepository.findByCode(code);
            if (!licenseCodeOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "激活码不存在");
                result.put("code", 401);
                return result;
            }
            
            LicenseCode licenseCode = licenseCodeOpt.get();
            
            // 检查状态
            if (!"active".equals(licenseCode.getStatus())) {
                result.put("ok", false);
                result.put("error", "激活码已被冻结或吊销");
                result.put("code", 401);
                return result;
            }
            
            // 检查过期
            if (licenseCode.getExpAt().isBefore(LocalDateTime.now())) {
                result.put("ok", false);
                result.put("error", "激活码已过期");
                result.put("code", 401);
                return result;
            }
            
            // 检查使用次数
            if (licenseCode.getIssueCount() >= licenseCode.getIssueLimit()) {
                result.put("ok", false);
                result.put("error", "激活码使用次数已达上限");
                result.put("code", 403);
                return result;
            }
            
            // 2. 检查是否已在此设备激活
            Optional<License> existingLicense = licenseRepository.findByCodeAndHwid(code, hwid);
            if (existingLicense.isPresent()) {
                License license = existingLicense.get();
                if ("ok".equals(license.getStatus()) && license.getValidTo().isAfter(LocalDateTime.now())) {
                    // 已激活的许可证，检查服务器IP绑定
                    Map<String, Object> serverBindingResult = serverManagementService.checkServerBinding(code, hwid, serverIp);
                    if (!(Boolean) serverBindingResult.get("ok")) {
                        return serverBindingResult; // 返回服务器绑定检查的错误结果
                    }
                    
                    // 服务器绑定检查通过，返回现有许可证
                    result.put("ok", true);
                    result.put("license", buildLicenseResponse(license));
                    result.put("plan", buildPlanResponse(license));
                    result.put("quota", buildQuotaResponse(license));
                    result.put("valid_from", license.getValidFrom());
                    result.put("valid_to", license.getValidTo());
                    result.put("existing", true);
                    result.put("server_action", serverBindingResult.get("action"));
                    result.put("server_message", serverBindingResult.get("message"));
                    return result;
                }
            }
            
            // 3. 获取套餐信息
            Optional<Plan> planOpt = planRepository.findById(licenseCode.getPlanId());
            if (!planOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "套餐不存在");
                result.put("code", 500);
                return result;
            }
            
            Plan plan = planOpt.get();
            
            // 4. 生成许可证
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime validFrom = now;
            LocalDateTime validTo = now.plusHours(plan.getDurationHours());
            String kid = licenseSignatureService.getCurrentKid();
            
            // 构建许可证负载
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", code);
            payload.put("sub", sub);
            payload.put("hwid", hwid);
            payload.put("plan_id", plan.getId());
            payload.put("plan_name", plan.getName());
            payload.put("features", plan.getFeatures());
            payload.put("valid_from", validFrom.toString());
            payload.put("valid_to", validTo.toString());
            payload.put("quota_total", plan.getInitQuota());
            payload.put("quota_remaining", plan.getInitQuota());
            if (clientInfo != null) {
                payload.put("client", clientInfo);
            }
            
            // 签名许可证
            Map<String, Object> signedLicense = licenseSignatureService.signLicense(payload);
            
            // 5. 保存到数据库
            License license = new License(
                code, sub, hwid, plan.getId(),
                validFrom, validTo, kid,
                (String) signedLicense.get("payload"),
                (String) signedLicense.get("sig"),
                plan.getInitQuota()
            );
            
            // 设置服务器IP（首次激活时直接绑定）
            license.setServerIp(serverIp);
            
            Long licenseId = licenseRepository.insert(license);
            license.setId(licenseId);
            
            // 6. 更新激活码使用次数
            licenseCodeRepository.incrementIssueCount(code);
            
            // 7. 记录审计日志
            auditLogRepository.insert(new AuditLog(
                "user", "activate", 
                "license:" + licenseId,
                "激活许可证，激活码：" + code + "，设备：" + hwid + "，服务器：" + serverIp
            ));
            
            // 8. 返回结果
            result.put("ok", true);
            result.put("license", signedLicense);
            result.put("plan", buildPlanResponse(plan));
            result.put("quota", Map.of("total", plan.getInitQuota(), "remaining", plan.getInitQuota()));
            result.put("valid_from", validFrom);
            result.put("valid_to", validTo);
            
            return result;
            
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "激活失败：" + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
    
    /**
     * 查看许可证状态
     */
    public Map<String, Object> getLicenseStatus(String payloadBase64, String signature, String hwid) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 1. 验证签名
            if (!licenseSignatureService.verifyLicense(payloadBase64, signature)) {
                result.put("ok", false);
                result.put("error", "许可证签名无效");
                result.put("code", 401);
                return result;
            }
            
            // 2. 解析负载
            Map<String, Object> payload = licenseSignatureService.parseLicensePayload(payloadBase64);
            String code = (String) payload.get("code");
            String payloadHwid = (String) payload.get("hwid");
            
            // 3. 验证设备ID
            if (!hwid.equals(payloadHwid)) {
                result.put("ok", false);
                result.put("error", "设备ID不匹配");
                result.put("code", 403);
                return result;
            }
            
            // 4. 查询数据库中的许可证
            Optional<License> licenseOpt = licenseRepository.findByCodeAndHwid(code, hwid);
            if (!licenseOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "许可证不存在");
                result.put("code", 404);
                return result;
            }
            
            License license = licenseOpt.get();
            
            // 5. 检查状态
            String status = "ok";
            LocalDateTime now = LocalDateTime.now();
            
            if ("revoked".equals(license.getStatus())) {
                status = "revoked";
            } else if (license.getValidTo().isBefore(now)) {
                status = "expired";
            }
            
            result.put("ok", true);
            result.put("status", status);
            result.put("valid_from", license.getValidFrom());
            result.put("valid_to", license.getValidTo());
            result.put("quota", buildQuotaResponse(license));
            
            return result;
            
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "查询失败：" + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
    
    /**
     * 下载预授权
     */
    public Map<String, Object> downloadPreauth(String payloadBase64, String signature, String hwid, 
                                               String fileId, Map<String, Object> clientInfo, String ip) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 0. 限流检查
            if (!rateLimitService.allowGlobal()) {
                result.put("ok", false);
                result.put("error", "系统繁忙，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            if (!rateLimitService.allowDevice(hwid)) {
                result.put("ok", false);
                result.put("error", "设备操作过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            // 1. 验证许可证
            Map<String, Object> statusResult = getLicenseStatus(payloadBase64, signature, hwid);
            if (!(Boolean) statusResult.get("ok")) {
                return statusResult;
            }
            
            String status = (String) statusResult.get("status");
            if (!"ok".equals(status)) {
                result.put("ok", false);
                result.put("error", "许可证状态异常：" + status);
                result.put("code", "expired".equals(status) ? 403 : 402);
                return result;
            }
            
            // 2. 获取许可证信息
            Map<String, Object> payload = licenseSignatureService.parseLicensePayload(payloadBase64);
            String code = (String) payload.get("code");
            
            Optional<License> licenseOpt = licenseRepository.findByCodeAndHwid(code, hwid);
            License license = licenseOpt.get();
            
            // 3. 验证服务器IP绑定
            Map<String, Object> serverBindingResult = serverManagementService.checkServerBinding(code, hwid, ip);
            if (!(Boolean) serverBindingResult.get("ok")) {
                return serverBindingResult; // 返回服务器绑定检查的错误结果
            }
            
            // 许可证级别限流
            if (!rateLimitService.allowPreauth(license.getId().toString())) {
                result.put("ok", false);
                result.put("error", "预授权请求过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            // 3. 检查下载额度
            boolean allowGrace = license.getPlanAllowGrace() != null && license.getPlanAllowGrace();
            
            if (license.getDownloadQuotaRemaining() <= 0 && !allowGrace) {
                result.put("ok", false);
                result.put("error", "下载额度已用完");
                result.put("code", 402);
                return result;
            }
            
            if (license.getDownloadQuotaRemaining() < 0 && allowGrace) {
                result.put("ok", false);
                result.put("error", "下载额度已超额，不允许继续下载");
                result.put("code", 402);
                return result;
            }
            
            // 4. 生成下载令牌
            String token = UUID.randomUUID().toString().replace("-", "");
            LocalDateTime expireAt = LocalDateTime.now().plusMinutes(10); // 10分钟有效期
            
            DownloadToken downloadToken = new DownloadToken(token, license.getId(), fileId, expireAt);
            downloadTokenRepository.insert(downloadToken);
            
            // 5. 生成下载URL（这里简化处理，实际应该根据fileId生成具体的下载URL）
            String downloadUrl = "/api/download/file/" + fileId + "?token=" + token;
            
            // 6. 预览额度变化
            int willBe = Math.max(0, license.getDownloadQuotaRemaining() - 1);
            
            result.put("ok", true);
            result.put("download_token", token);
            result.put("expires_in", 600);
            result.put("download_url", downloadUrl);
            result.put("quota_preview", Map.of(
                "remaining", license.getDownloadQuotaRemaining(),
                "will_be", willBe
            ));
            
            return result;
            
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "预授权失败：" + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
    
    /**
     * 下载回执（成功扣次/失败不扣）
     */
    @Transactional
    public Map<String, Object> downloadCommit(String downloadToken, Map<String, Object> downloadResult, 
                                             Map<String, Object> clientInfo, String ip, String ua) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 0. 限流检查
            if (!rateLimitService.allowGlobal()) {
                result.put("ok", false);
                result.put("error", "系统繁忙，请稍后重试");
                result.put("code", 429);
                return result;
            }
            // 1. 检查令牌是否存在且有效
            Optional<DownloadToken> tokenOpt = downloadTokenRepository.findByToken(downloadToken);
            if (!tokenOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "下载令牌不存在");
                result.put("code", 404);
                return result;
            }
            
            DownloadToken token = tokenOpt.get();
            
            // 检查是否过期
            if (token.getExpireAt().isBefore(LocalDateTime.now())) {
                result.put("ok", false);
                result.put("error", "下载令牌已过期");
                result.put("code", 410);
                return result;
            }
            
            // 2. 检查是否已经提交过（幂等性）
            Optional<DownloadEvent> existingEvent = downloadEventRepository.findByToken(downloadToken);
            if (existingEvent.isPresent()) {
                DownloadEvent event = existingEvent.get();
                result.put("ok", true);
                result.put("deducted", event.getDeducted() ? event.getDelta() : 0);
                
                // 获取当前剩余额度
                Optional<License> licenseOpt = licenseRepository.findById(event.getLicenseId());
                if (licenseOpt.isPresent()) {
                    result.put("remaining", licenseOpt.get().getDownloadQuotaRemaining());
                }
                result.put("event_id", event.getId());
                return result;
            }
            
            // 3. 获取许可证信息
            Optional<License> licenseOpt = licenseRepository.findById(token.getLicenseId());
            if (!licenseOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "许可证不存在");
                result.put("code", 404);
                return result;
            }
            
            License license = licenseOpt.get();
            
            // 许可证级别限流
            if (!rateLimitService.allowCommit(license.getId().toString())) {
                result.put("ok", false);
                result.put("error", "回执请求过于频繁，请稍后重试");
                result.put("code", 429);
                return result;
            }
            
            // 4. 处理下载结果
            Boolean ok = (Boolean) downloadResult.get("ok");
            Long size = downloadResult.get("size") != null ? ((Number) downloadResult.get("size")).longValue() : null;
            String sha256 = (String) downloadResult.get("sha256");
            
            boolean deducted = false;
            int delta = 0;
            
            if (Boolean.TRUE.equals(ok)) {
                // 下载成功，尝试扣次
                int updateCount = downloadTokenRepository.markAsUsed(downloadToken);
                if (updateCount > 0) {
                    // 首次使用，扣除额度
                    boolean allowGrace = license.getPlanAllowGrace() != null && license.getPlanAllowGrace();
                    
                    if (allowGrace) {
                        licenseRepository.decrementQuotaWithGrace(license.getId());
                    } else {
                        licenseRepository.decrementQuota(license.getId());
                    }
                    
                    deducted = true;
                    delta = 1;
                    
                    // 更新license对象的剩余额度
                    license.setDownloadQuotaRemaining(license.getDownloadQuotaRemaining() - 1);
                }
            }
            
            // 5. 记录下载事件
            DownloadEvent event = new DownloadEvent(
                license.getId(), downloadToken, token.getFileId(),
                Boolean.TRUE.equals(ok), deducted, delta, ip, ua
            );
            event.setSize(size);
            event.setSha256(sha256);
            
            Long eventId = downloadEventRepository.insert(event);
            
            // 6. 记录审计日志
            auditLogRepository.insert(new AuditLog(
                "user", "download_commit",
                "license:" + license.getId(),
                "下载回执，文件：" + token.getFileId() + "，成功：" + ok + "，扣次：" + deducted
            ));
            
            result.put("ok", true);
            result.put("deducted", delta);
            result.put("remaining", license.getDownloadQuotaRemaining());
            result.put("event_id", eventId);
            
            return result;
            
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "回执处理失败：" + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
    
    // 辅助方法
    private Map<String, Object> buildLicenseResponse(License license) {
        return Map.of(
            "payload", license.getLicensePayload(),
            "sig", license.getLicenseSig(),
            "alg", "Ed25519",
            "kid", license.getKid()
        );
    }
    
    private Map<String, Object> buildPlanResponse(Plan plan) {
        return Map.of(
            "name", plan.getName(),
            "duration", plan.getDurationHours() + "h",
            "quota", plan.getInitQuota()
        );
    }
    
    private Map<String, Object> buildPlanResponse(License license) {
        return Map.of(
            "name", license.getPlanName() != null ? license.getPlanName() : "Unknown",
            "duration", (license.getPlanDurationHours() != null ? license.getPlanDurationHours() : 0) + "h",
            "quota", license.getDownloadQuotaTotal()
        );
    }
    
    private Map<String, Object> buildQuotaResponse(License license) {
        return Map.of(
            "total", license.getDownloadQuotaTotal(),
            "remaining", license.getDownloadQuotaRemaining()
        );
    }
    
    /**
     * 通过hwid获取许可证信息（简化版本）
     */
    public Map<String, Object> getLicenseInfoByHwid(String hwid) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 查询数据库中的许可证
            Optional<License> licenseOpt = licenseRepository.findByHwid(hwid);
            if (!licenseOpt.isPresent()) {
                result.put("ok", false);
                result.put("error", "许可证不存在");
                result.put("code", 404);
                return result;
            }
            
            License license = licenseOpt.get();
            
            // 检查状态
            String status = "ok";
            LocalDateTime now = LocalDateTime.now();
            
            if ("revoked".equals(license.getStatus())) {
                status = "revoked";
            } else if (license.getValidTo().isBefore(now)) {
                status = "expired";
            }
            
            result.put("ok", true);
            result.put("status", status);
            result.put("valid_from", license.getValidFrom());
            result.put("valid_to", license.getValidTo());
            result.put("server_ip", license.getServerIp());
            result.put("quota", Map.of(
                "total", license.getDownloadQuotaTotal(),
                "remaining", license.getDownloadQuotaRemaining(),
                "used", license.getDownloadQuotaTotal() - license.getDownloadQuotaRemaining()
            ));
            
            return result;
            
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "查询失败：" + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
}
