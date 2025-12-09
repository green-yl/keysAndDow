package com.example.keys.web;

import com.example.keys.model.*;
import com.example.keys.service.AdminService;
import com.example.keys.service.ServerManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    
    @Autowired
    private AdminService adminService;
    
    @Autowired
    private ServerManagementService serverManagementService;
    
    /**
     * 套餐管理
     */
    @GetMapping("/plans")
    public ResponseEntity<List<Plan>> getAllPlans() {
        return ResponseEntity.ok(adminService.getAllPlans());
    }
    
    @PostMapping("/plans")
    public ResponseEntity<?> createPlan(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        Integer durationHours = (Integer) request.get("duration_hours");
        Integer initQuota = (Integer) request.get("init_quota");
        Boolean allowGrace = (Boolean) request.get("allow_grace");
        String features = (String) request.get("features");
        
        if (name == null || durationHours == null || initQuota == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：name、duration_hours、init_quota"
            ));
        }
        
        Plan plan = adminService.createPlan(name, durationHours, initQuota, allowGrace, features);
        return ResponseEntity.ok(Map.of("ok", true, "plan", plan));
    }
    
    @PutMapping("/plans/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        Integer durationHours = (Integer) request.get("duration_hours");
        Integer initQuota = (Integer) request.get("init_quota");
        Boolean allowGrace = (Boolean) request.get("allow_grace");
        String features = (String) request.get("features");
        
        boolean updated = adminService.updatePlan(id, name, durationHours, initQuota, allowGrace, features);
        
        if (updated) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @DeleteMapping("/plans/{id}")
    public ResponseEntity<?> deletePlan(@PathVariable Long id) {
        boolean deleted = adminService.deletePlan(id);
        
        if (deleted) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 激活码管理
     */
    @GetMapping("/codes")
    public ResponseEntity<List<LicenseCode>> getAllLicenseCodes() {
        return ResponseEntity.ok(adminService.getAllLicenseCodes());
    }
    
    @PostMapping("/codes")
    public ResponseEntity<?> createLicenseCode(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("code");
        Long planId = ((Number) request.get("plan_id")).longValue();
        Integer issueLimit = (Integer) request.get("issue_limit");
        String expAtStr = (String) request.get("exp_at");
        String note = (String) request.get("note");
        
        if (planId == null || issueLimit == null || expAtStr == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：plan_id、issue_limit、exp_at"
            ));
        }
        
        LocalDateTime expAt = LocalDateTime.parse(expAtStr);
        
        if (code == null) {
            // 生成单个激活码
            LicenseCode licenseCode = adminService.createLicenseCode(
                null, planId, issueLimit, expAt, note
            );
            return ResponseEntity.ok(Map.of("ok", true, "code", licenseCode));
        } else {
            // 使用指定激活码
            LicenseCode licenseCode = adminService.createLicenseCode(
                code, planId, issueLimit, expAt, note
            );
            return ResponseEntity.ok(Map.of("ok", true, "code", licenseCode));
        }
    }
    
    @PostMapping("/codes/batch")
    public ResponseEntity<?> batchCreateLicenseCodes(@RequestBody Map<String, Object> request) {
        Long planId = ((Number) request.get("plan_id")).longValue();
        Integer issueLimit = (Integer) request.get("issue_limit");
        String expAtStr = (String) request.get("exp_at");
        String note = (String) request.get("note");
        Integer count = (Integer) request.get("count");
        
        if (planId == null || issueLimit == null || expAtStr == null || count == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：plan_id、issue_limit、exp_at、count"
            ));
        }
        
        LocalDateTime expAt = LocalDateTime.parse(expAtStr);
        
        List<LicenseCode> codes = adminService.batchCreateLicenseCodes(
            planId, issueLimit, expAt, note, count
        );
        
        return ResponseEntity.ok(Map.of("ok", true, "codes", codes));
    }
    
    @PutMapping("/codes/{code}/status")
    public ResponseEntity<?> updateLicenseCodeStatus(@PathVariable String code, 
                                                    @RequestBody Map<String, Object> request) {
        String status = (String) request.get("status");
        
        if (status == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：status"
            ));
        }
        
        boolean updated = adminService.updateLicenseCodeStatus(code, status);
        
        if (updated) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 删除激活码（连带删除对应的许可证）
     */
    @DeleteMapping("/codes/{code}")
    public ResponseEntity<?> deleteLicenseCode(@PathVariable String code) {
        try {
            Map<String, Object> result = adminService.deleteLicenseCodeAndRelatedLicenses(code);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "ok", false,
                "error", "删除失败：" + e.getMessage()
            ));
        }
    }
    
    /**
     * 许可证管理
     */
    @GetMapping("/licenses")
    public ResponseEntity<List<License>> getAllLicenses() {
        return ResponseEntity.ok(adminService.getAllLicenses());
    }
    
    @PostMapping("/licenses/{id}/renew")
    public ResponseEntity<?> renewLicense(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Integer extraDays = (Integer) request.get("extra_days");
        Boolean resetQuota = (Boolean) request.get("reset_quota");
        
        if (extraDays == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：extra_days"
            ));
        }
        
        boolean renewed = adminService.renewLicense(id, extraDays, resetQuota);
        
        if (renewed) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @PostMapping("/licenses/{id}/add-quota")
    public ResponseEntity<?> addQuota(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Integer extra = (Integer) request.get("extra");
        
        if (extra == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：extra"
            ));
        }
        
        boolean added = adminService.addQuota(id, extra);
        
        if (added) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @PostMapping("/licenses/{id}/revoke")
    public ResponseEntity<?> revokeLicense(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        String reason = (String) request.get("reason");
        
        boolean revoked = adminService.revokeLicense(id, reason);
        
        if (revoked) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @PostMapping("/licenses/{id}/rebind")
    public ResponseEntity<?> rebindLicense(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        String newHwid = (String) request.get("new_hwid");
        
        if (newHwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：new_hwid"
            ));
        }
        
        boolean rebound = adminService.rebindLicense(id, newHwid);
        
        if (rebound) {
            return ResponseEntity.ok(Map.of("ok", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 下载统计
     */
    @GetMapping("/stats/downloads")
    public ResponseEntity<?> getDownloadStats() {
        return ResponseEntity.ok(adminService.getDownloadStats());
    }
    
    @GetMapping("/licenses/{id}/downloads")
    public ResponseEntity<List<DownloadEvent>> getDownloadEventsByLicense(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getDownloadEventsByLicense(id));
    }
    
    @GetMapping("/downloads")
    public ResponseEntity<List<DownloadEvent>> getDownloadEventsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(adminService.getDownloadEventsByDateRange(startDate, endDate));
    }
    
    /**
     * 审计日志
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action) {
        
        // 如果指定了过滤条件，使用过滤查询
        if (actor != null && !actor.isEmpty()) {
            return ResponseEntity.ok(adminService.getAuditLogsByActorPaginated(actor, page, pageSize));
        }
        
        if (action != null && !action.isEmpty()) {
            return ResponseEntity.ok(adminService.getAuditLogsByActionPaginated(action, page, pageSize));
        }
        
        // 否则返回所有日志
        return ResponseEntity.ok(adminService.getAuditLogsPaginated(page, pageSize));
    }
    
    @GetMapping("/audit-logs/simple")
    public ResponseEntity<List<AuditLog>> getAuditLogsSimple(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(adminService.getAuditLogs(limit));
    }
    
    @GetMapping("/audit-logs/actor/{actor}")
    public ResponseEntity<List<AuditLog>> getAuditLogsByActor(@PathVariable String actor,
                                                             @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(adminService.getAuditLogsByActor(actor, limit));
    }
    
    @GetMapping("/audit-logs/action/{action}")
    public ResponseEntity<List<AuditLog>> getAuditLogsByAction(@PathVariable String action,
                                                              @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(adminService.getAuditLogsByAction(action, limit));
    }
    
    /**
     * 清空所有审计日志
     */
    @DeleteMapping("/audit-logs")
    public ResponseEntity<?> clearAllAuditLogs() {
        return ResponseEntity.ok(adminService.clearAllAuditLogs());
    }
    
    /**
     * 清理指定天数之前的审计日志
     */
    @DeleteMapping("/audit-logs/cleanup")
    public ResponseEntity<?> cleanupAuditLogs(@RequestParam int daysAgo) {
        if (daysAgo <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "daysAgo 必须大于 0"
            ));
        }
        
        LocalDateTime beforeDate = LocalDateTime.now().minusDays(daysAgo);
        return ResponseEntity.ok(adminService.cleanupAuditLogs(beforeDate));
    }
    
    /**
     * 服务器绑定管理
     */
    @PostMapping("/server-binding")
    public ResponseEntity<?> getServerBindingInfo(@RequestBody Map<String, String> request) {
        String code = request.get("code");
        String hwid = request.get("hwid");
        
        if (code == null || hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：code, hwid"
            ));
        }
        
        Map<String, Object> result = serverManagementService.getServerBindingInfo(code, hwid);
        return ResponseEntity.ok(result);
    }
}
