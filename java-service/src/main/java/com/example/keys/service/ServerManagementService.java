package com.example.keys.service;

import com.example.keys.model.License;
import com.example.keys.model.AuditLog;
import com.example.keys.repo.LicenseRepository;
import com.example.keys.repo.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Service
public class ServerManagementService {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerManagementService.class);
    
    @Value("${app.security.server-switch-cooldown-minutes:30}")
    private int serverSwitchCooldownMinutes;
    
    @Value("${app.security.enable-server-ip-binding:true}")
    private boolean enableServerIpBinding;
    
    @Autowired
    private LicenseRepository licenseRepository;
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    /**
     * 检查服务器IP绑定状态并处理服务器切换
     */
    @Transactional
    public Map<String, Object> checkServerBinding(String code, String hwid, String currentServerIp) {
        Map<String, Object> result = new HashMap<>();
        
        // 如果禁用了服务器IP绑定功能，直接允许
        if (!enableServerIpBinding) {
            result.put("ok", true);
            result.put("action", "disabled");
            result.put("message", "服务器IP绑定功能已禁用");
            return result;
        }
        
        try {
            License license = licenseRepository.findByCodeAndHwidDirect(code, hwid);
            
            if (license == null) {
                result.put("ok", false);
                result.put("error", "许可证不存在或已失效");
                result.put("code", 404);
                return result;
            }
            
            String boundServerIp = license.getServerIp();
            
            // 如果没有绑定服务器IP，直接绑定当前服务器
            if (boundServerIp == null || boundServerIp.isEmpty()) {
                licenseRepository.updateServerIp(license.getId(), currentServerIp);
                
                auditLogRepository.insert(new AuditLog("system", "server_bind", 
                    "license:" + license.getId(), 
                    "首次绑定服务器IP: " + currentServerIp));
                
                result.put("ok", true);
                result.put("action", "bound");
                result.put("server_ip", currentServerIp);
                result.put("message", "许可证已绑定到当前服务器");
                return result;
            }
            
            // 如果当前服务器IP与绑定的IP相同，允许使用
            if (currentServerIp.equals(boundServerIp)) {
                result.put("ok", true);
                result.put("action", "allowed");
                result.put("server_ip", currentServerIp);
                result.put("message", "当前服务器已授权使用此许可证");
                return result;
            }
            
            // 检查是否可以切换服务器（30分钟冷却时间）
            long cooldownRemaining = calculateCooldownRemaining(license);
            if (cooldownRemaining > 0) {
                result.put("ok", false);
                result.put("error", "服务器切换冷却中，请等待 " + cooldownRemaining + " 分钟后再试");
                result.put("code", 429);
                result.put("cooldown_remaining", cooldownRemaining);
                result.put("bound_server", boundServerIp);
                return result;
            }
            
            // 允许切换服务器
            // 1. 尝试终止之前服务器的Java进程
            terminateRemoteJavaProcess(boundServerIp);
            
            // 2. 更新许可证绑定的服务器IP
            licenseRepository.updateServerIp(license.getId(), currentServerIp);
            
            // 3. 记录审计日志
            auditLogRepository.insert(new AuditLog("system", "server_switch", 
                "license:" + license.getId(), 
                "服务器切换: " + boundServerIp + " -> " + currentServerIp));
            
            result.put("ok", true);
            result.put("action", "switched");
            result.put("server_ip", currentServerIp);
            result.put("previous_server", boundServerIp);
            result.put("message", "许可证已切换到当前服务器，之前服务器已终止");
            
            return result;
            
        } catch (Exception e) {
            logger.error("检查服务器绑定失败", e);
            result.put("ok", false);
            result.put("error", "服务器绑定检查失败: " + e.getMessage());
            result.put("code", 500);
            return result;
        }
    }
    
    /**
     * 尝试终止远程服务器的Java进程
     * 注意：这是一个模拟实现，实际环境中需要根据具体的部署架构来实现
     */
    private void terminateRemoteJavaProcess(String serverIp) {
        try {
            logger.info("尝试终止服务器 {} 上的Java进程", serverIp);
            
            // 这里是一个模拟实现
            // 实际实现可能需要：
            // 1. SSH连接到远程服务器执行 pkill java
            // 2. 调用容器编排系统API（如Docker、K8s）
            // 3. 调用云服务商API终止实例
            // 4. 发送信号给远程应用让其自行关闭
            
            // 模拟实现：记录日志
            auditLogRepository.insert(new AuditLog("system", "remote_terminate", 
                "server:" + serverIp, 
                "尝试终止远程Java进程"));
                
            logger.warn("远程进程终止功能需要根据实际部署环境实现");
            
        } catch (Exception e) {
            logger.error("终止远程Java进程失败: " + serverIp, e);
        }
    }
    
    /**
     * 获取许可证的服务器绑定信息
     */
    public Map<String, Object> getServerBindingInfo(String code, String hwid) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            License license = licenseRepository.findByCodeAndHwidDirect(code, hwid);
            
            if (license == null) {
                result.put("ok", false);
                result.put("error", "许可证不存在");
                return result;
            }
            
            result.put("ok", true);
            result.put("license_id", license.getId());
            result.put("bound_server", license.getServerIp());
            result.put("last_switch_time", license.getLastServerSwitchAt());
            
            // 计算冷却时间
            long cooldownRemaining = calculateCooldownRemaining(license);
            result.put("cooldown_remaining", cooldownRemaining);
            result.put("can_switch", cooldownRemaining == 0);
            
            return result;
            
        } catch (Exception e) {
            logger.error("获取服务器绑定信息失败", e);
            result.put("ok", false);
            result.put("error", "获取绑定信息失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 计算服务器切换冷却时间剩余分钟数
     */
    private long calculateCooldownRemaining(License license) {
        if (license.getServerIp() == null) {
            return 0; // 未绑定服务器，无冷却时间
        }
        
        LocalDateTime cooldownBaseTime = license.getLastServerSwitchAt() != null ? 
            license.getLastServerSwitchAt() : license.getCreatedAt();
            
        if (cooldownBaseTime == null) {
            return 0;
        }
        
        long minutesSinceLastSwitch = ChronoUnit.MINUTES.between(cooldownBaseTime, LocalDateTime.now());
        return Math.max(0, serverSwitchCooldownMinutes - minutesSinceLastSwitch);
    }
}
