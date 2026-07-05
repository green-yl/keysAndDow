package com.example.keys.web;

import com.example.keys.service.AuthorizationService;
import com.example.keys.service.RateLimitService;
import com.example.keys.util.IpUtils;
import com.example.keys.util.ResponseHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/license")
@CrossOrigin(origins = "*", maxAge = 3600)
public class LicenseController {
    
    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RateLimitService rateLimitService;
    
    @PostMapping("/activate")
    public ResponseEntity<?> activate(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String code = (String) request.get("code");
        String hwid = (String) request.get("hwid");
        String sub = (String) request.get("sub");
        if (sub == null || sub.trim().isEmpty()) {
            sub = "user_" + System.currentTimeMillis();
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
        
        if (code == null || hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：code 和 hwid"
            ));
        }
        
        String ip = IpUtils.getClientIpAddress(httpRequest);
        Map<String, Object> result = authorizationService.activateLicense(code, hwid, sub, clientInfo, ip);
        return ResponseHelper.fromServiceResult(result);
    }
    
    @PostMapping("/status")
    public ResponseEntity<?> status(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> license = (Map<String, Object>) request.get("license");
        String hwid = (String) request.get("hwid");
        
        if (license == null || hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：license 和 hwid"
            ));
        }

        if (!rateLimitService.allowStatus(IpUtils.getClientIpAddress(httpRequest), hwid)) {
            return ResponseEntity.status(429).body(Map.of(
                "ok", false,
                "error", "状态查询过于频繁，请稍后重试",
                "code", 429
            ));
        }
        
        String payload = (String) license.get("payload");
        String sig = (String) license.get("sig");
        
        if (payload == null || sig == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "许可证格式错误"
            ));
        }
        
        Map<String, Object> result = authorizationService.getLicenseStatus(payload, sig, hwid);
        return ResponseHelper.fromServiceResult(result);
    }
    
    @PostMapping("/info")
    public ResponseEntity<?> info(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String hwid = (String) request.get("hwid");
        
        if (hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：hwid"
            ));
        }

        if (!rateLimitService.allowStatus(IpUtils.getClientIpAddress(httpRequest), hwid)) {
            return ResponseEntity.status(429).body(Map.of(
                "ok", false,
                "error", "详情查询过于频繁，请稍后重试",
                "code", 429
            ));
        }
        
        Map<String, Object> result = authorizationService.getLicenseInfoByHwid(hwid);
        return ResponseHelper.fromServiceResult(result);
    }
}
