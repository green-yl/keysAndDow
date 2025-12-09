package com.example.keys.service;

import com.example.keys.model.*;
import com.example.keys.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AdminService {
    
    @Autowired
    private PlanRepository planRepository;
    
    @Autowired
    private LicenseCodeRepository licenseCodeRepository;
    
    @Autowired
    private LicenseRepository licenseRepository;
    
    @Autowired
    private DownloadEventRepository downloadEventRepository;
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    /**
     * 套餐管理
     */
    public List<Plan> getAllPlans() {
        return planRepository.findAll();
    }
    
    public Plan createPlan(String name, Integer durationHours, Integer initQuota, Boolean allowGrace, String features) {
        Plan plan = new Plan(name, durationHours, initQuota);
        plan.setAllowGrace(allowGrace != null ? allowGrace : false);
        plan.setFeatures(features);
        
        Long id = planRepository.insert(plan);
        plan.setId(id);
        
        auditLogRepository.insert(new AuditLog(
            "admin", "create_plan", 
            "plan:" + id,
            "创建套餐：" + name
        ));
        
        return plan;
    }
    
    public boolean updatePlan(Long id, String name, Integer durationHours, Integer initQuota, 
                             Boolean allowGrace, String features) {
        Optional<Plan> planOpt = planRepository.findById(id);
        if (!planOpt.isPresent()) {
            return false;
        }
        
        Plan plan = planOpt.get();
        plan.setName(name);
        plan.setDurationHours(durationHours);
        plan.setInitQuota(initQuota);
        plan.setAllowGrace(allowGrace);
        plan.setFeatures(features);
        
        int updated = planRepository.update(plan);
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "update_plan",
                "plan:" + id,
                "更新套餐：" + name
            ));
        }
        
        return updated > 0;
    }
    
    public boolean deletePlan(Long id) {
        int deleted = planRepository.deleteById(id);
        
        if (deleted > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "delete_plan",
                "plan:" + id,
                "删除套餐"
            ));
        }
        
        return deleted > 0;
    }
    
    /**
     * 激活码管理
     */
    public List<LicenseCode> getAllLicenseCodes() {
        return licenseCodeRepository.findAllWithPlan();
    }
    
    public LicenseCode createLicenseCode(String code, Long planId, Integer issueLimit, 
                                        LocalDateTime expAt, String note) {
        if (code == null || code.trim().isEmpty()) {
            code = generateActivationCode();
        }
        
        LicenseCode licenseCode = new LicenseCode(code, planId, issueLimit, expAt);
        licenseCode.setNote(note);
        
        Long id = licenseCodeRepository.insert(licenseCode);
        licenseCode.setId(id);
        
        auditLogRepository.insert(new AuditLog(
            "admin", "create_code",
            "code:" + code,
            "创建激活码：" + code
        ));
        
        return licenseCode;
    }
    
    public List<LicenseCode> batchCreateLicenseCodes(Long planId, Integer issueLimit, 
                                                    LocalDateTime expAt, String note, Integer count) {
        List<LicenseCode> codes = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            String code = generateActivationCode();
            LicenseCode licenseCode = createLicenseCode(code, planId, issueLimit, expAt, note);
            codes.add(licenseCode);
        }
        
        auditLogRepository.insert(new AuditLog(
            "admin", "batch_create_codes",
            "plan:" + planId,
            "批量创建激活码，数量：" + count
        ));
        
        return codes;
    }
    
    public boolean updateLicenseCodeStatus(String code, String status) {
        int updated = licenseCodeRepository.updateStatus(code, status);
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "update_code_status",
                "code:" + code,
                "更新激活码状态：" + status
            ));
        }
        
        return updated > 0;
    }
    
    /**
     * 删除激活码及其相关的所有许可证
     */
    public Map<String, Object> deleteLicenseCodeAndRelatedLicenses(String code) {
        // 1. 先查找该激活码对应的所有许可证
        List<License> relatedLicenses = licenseRepository.findByCode(code);
        int deletedLicenses = 0;
        
        // 2. 删除所有相关许可证
        for (License license : relatedLicenses) {
            try {
                licenseRepository.deleteById(license.getId());
                deletedLicenses++;
                
                // 记录审计日志
                auditLogRepository.insert(new AuditLog(
                    "admin", "delete_license",
                    "license_id:" + license.getId(),
                    String.format("删除许可证ID: %d, 激活码: %s", license.getId(), code)
                ));
            } catch (Exception e) {
                System.err.println("删除许可证失败: " + e.getMessage());
            }
        }
        
        // 3. 删除激活码
        boolean codeDeleted = licenseCodeRepository.deleteByCode(code) > 0;
        
        if (codeDeleted) {
            // 记录审计日志
            auditLogRepository.insert(new AuditLog(
                "admin", "delete_code",
                "code:" + code,
                String.format("删除激活码: %s, 同时删除 %d 个相关许可证", code, deletedLicenses)
            ));
        }
        
        return Map.of(
            "ok", codeDeleted,
            "deletedLicenses", deletedLicenses,
            "message", codeDeleted ? 
                String.format("激活码删除成功，同时删除了 %d 个相关许可证", deletedLicenses) : 
                "激活码删除失败"
        );
    }
    
    /**
     * 许可证管理
     */
    public List<License> getAllLicenses() {
        return licenseRepository.findAllWithPlan();
    }
    
    @Transactional
    public boolean renewLicense(Long licenseId, Integer extraDays, Boolean resetQuota) {
        Optional<License> licenseOpt = licenseRepository.findById(licenseId);
        if (!licenseOpt.isPresent()) {
            return false;
        }
        
        License license = licenseOpt.get();
        LocalDateTime newValidTo = license.getValidTo().plusDays(extraDays);
        
        Integer newQuota = null;
        if (Boolean.TRUE.equals(resetQuota)) {
            newQuota = license.getDownloadQuotaTotal();
        }
        
        int updated = licenseRepository.renewLicense(licenseId, newValidTo, resetQuota, newQuota);
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "renew_license",
                "license:" + licenseId,
                "续期许可证，延长：" + extraDays + "天，重置额度：" + resetQuota
            ));
        }
        
        return updated > 0;
    }
    
    @Transactional
    public boolean addQuota(Long licenseId, Integer extraQuota) {
        int updated = licenseRepository.addQuota(licenseId, extraQuota);
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "add_quota",
                "license:" + licenseId,
                "增加下载额度：" + extraQuota
            ));
        }
        
        return updated > 0;
    }
    
    @Transactional
    public boolean revokeLicense(Long licenseId, String reason) {
        int updated = licenseRepository.updateStatus(licenseId, "revoked");
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "revoke_license",
                "license:" + licenseId,
                "吊销许可证，原因：" + reason
            ));
        }
        
        return updated > 0;
    }
    
    @Transactional
    public boolean rebindLicense(Long licenseId, String newHwid) {
        int updated = licenseRepository.rebindHwid(licenseId, newHwid);
        
        if (updated > 0) {
            auditLogRepository.insert(new AuditLog(
                "admin", "rebind_license",
                "license:" + licenseId,
                "换绑设备：" + newHwid
            ));
        }
        
        return updated > 0;
    }
    
    /**
     * 下载统计
     */
    public Map<String, Object> getDownloadStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 今日下载次数
        Long todayCount = downloadEventRepository.countTotalDownloadsToday();
        stats.put("today_downloads", todayCount);
        
        // 最近下载记录
        List<DownloadEvent> recentEvents = downloadEventRepository.findSuccessfulDownloads();
        stats.put("recent_downloads", recentEvents.subList(0, Math.min(10, recentEvents.size())));
        
        return stats;
    }
    
    public List<DownloadEvent> getDownloadEventsByLicense(Long licenseId) {
        return downloadEventRepository.findByLicenseId(licenseId);
    }
    
    public List<DownloadEvent> getDownloadEventsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return downloadEventRepository.findByDateRange(startDate, endDate);
    }
    
    /**
     * 审计日志
     */
    public List<AuditLog> getAuditLogs(int limit) {
        return auditLogRepository.findAll(limit);
    }
    
    public List<AuditLog> getAuditLogsByActor(String actor, int limit) {
        return auditLogRepository.findByActor(actor, limit);
    }
    
    public List<AuditLog> getAuditLogsByAction(String action, int limit) {
        return auditLogRepository.findByAction(action, limit);
    }
    
    /**
     * 分页查询审计日志
     */
    public Map<String, Object> getAuditLogsPaginated(int page, int pageSize) {
        List<AuditLog> logs = auditLogRepository.findAllPaginated(page, pageSize);
        int total = auditLogRepository.count();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        
        Map<String, Object> result = new HashMap<>();
        result.put("logs", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        
        return result;
    }
    
    public Map<String, Object> getAuditLogsByActorPaginated(String actor, int page, int pageSize) {
        List<AuditLog> logs = auditLogRepository.findByActorPaginated(actor, page, pageSize);
        int total = auditLogRepository.countByActor(actor);
        int totalPages = (int) Math.ceil((double) total / pageSize);
        
        Map<String, Object> result = new HashMap<>();
        result.put("logs", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        result.put("actor", actor);
        
        return result;
    }
    
    public Map<String, Object> getAuditLogsByActionPaginated(String action, int page, int pageSize) {
        List<AuditLog> logs = auditLogRepository.findByActionPaginated(action, page, pageSize);
        int total = auditLogRepository.countByAction(action);
        int totalPages = (int) Math.ceil((double) total / pageSize);
        
        Map<String, Object> result = new HashMap<>();
        result.put("logs", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        result.put("action", action);
        
        return result;
    }
    
    /**
     * 清空所有审计日志
     */
    public Map<String, Object> clearAllAuditLogs() {
        int deleted = auditLogRepository.deleteAll();
        
        // 记录清空操作
        auditLogRepository.insert(new AuditLog(
            "admin", "clear_audit_logs",
            "audit_logs",
            "清空所有审计日志，删除记录数：" + deleted
        ));
        
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("deleted", deleted);
        result.put("message", "已清空 " + deleted + " 条审计日志");
        
        return result;
    }
    
    /**
     * 清理指定时间之前的审计日志
     */
    public Map<String, Object> cleanupAuditLogs(LocalDateTime beforeDate) {
        int deleted = auditLogRepository.cleanupOldLogs(beforeDate);
        
        auditLogRepository.insert(new AuditLog(
            "admin", "cleanup_audit_logs",
            "audit_logs",
            "清理审计日志，删除记录数：" + deleted + "，清理时间点：" + beforeDate
        ));
        
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("deleted", deleted);
        result.put("message", "已清理 " + deleted + " 条审计日志");
        
        return result;
    }
    
    /**
     * 生成激活码
     */
    private String generateActivationCode() {
        // 生成64位随机激活码
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        
        for (int i = 0; i < 64; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return code.toString();
    }
}