package com.example.keys.web;

import com.example.keys.service.AuthorizationService;
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
    
    /**
     * 激活许可证
     */
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
        
        String ip = getClientIpAddress(httpRequest);
        Map<String, Object> result = authorizationService.activateLicense(code, hwid, sub, clientInfo, ip);
        
        Integer statusCode = (Integer) result.get("code");
        if (statusCode != null && statusCode != 200) {
            if (statusCode == 400) {
                return ResponseEntity.badRequest().body(result);
            } else if (statusCode == 401) {
                return ResponseEntity.status(401).body(result);
            } else if (statusCode == 403) {
                return ResponseEntity.status(403).body(result);
            } else if (statusCode == 409) {
                return ResponseEntity.status(409).body(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 查看许可证状态
     */
    @PostMapping("/status")
    public ResponseEntity<?> status(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> license = (Map<String, Object>) request.get("license");
        String hwid = (String) request.get("hwid");
        
        if (license == null || hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：license 和 hwid"
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
        
        Integer statusCode = (Integer) result.get("code");
        if (statusCode != null && statusCode != 200) {
            if (statusCode == 401) {
                return ResponseEntity.status(401).body(result);
            } else if (statusCode == 403) {
                return ResponseEntity.status(403).body(result);
            } else if (statusCode == 404) {
                return ResponseEntity.status(404).body(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取许可证信息（简化接口，只需要hwid）
     */
    @PostMapping("/info")
    public ResponseEntity<?> info(@RequestBody Map<String, Object> request) {
        String hwid = (String) request.get("hwid");
        
        if (hwid == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：hwid"
            ));
        }
        
        Map<String, Object> result = authorizationService.getLicenseInfoByHwid(hwid);
        
        Integer statusCode = (Integer) result.get("code");
        if (statusCode != null && statusCode != 200) {
            if (statusCode == 401) {
                return ResponseEntity.status(401).body(result);
            } else if (statusCode == 403) {
                return ResponseEntity.status(403).body(result);
            } else if (statusCode == 404) {
                return ResponseEntity.status(404).body(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        }
        
        return ResponseEntity.ok(result);
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0];
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}
