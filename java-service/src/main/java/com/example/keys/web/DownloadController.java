package com.example.keys.web;

import com.example.keys.service.AuthorizationService;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.repo.DownloadTokenRepository;
import com.example.keys.model.DownloadToken;
import com.example.keys.model.SourcePackage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/download")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DownloadController {
    
    @Autowired
    private AuthorizationService authorizationService;
    
    @Autowired
    private SourcePackageRepository sourcePackageRepository;
    
    @Autowired
    private DownloadTokenRepository downloadTokenRepository;
    
    /**
     * 下载预授权
     */
    @PostMapping("/preauth")
    public ResponseEntity<?> preauth(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> license = (Map<String, Object>) request.get("license");
        String hwid = (String) request.get("hwid");
        String fileId = (String) request.get("file_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
        
        if (license == null || hwid == null || fileId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：license、hwid 和 file_id"
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
        
        String ip = getClientIpAddress(httpRequest);
        Map<String, Object> result = authorizationService.downloadPreauth(payload, sig, hwid, fileId, clientInfo, ip);
        
        Integer statusCode = (Integer) result.get("code");
        if (statusCode != null && statusCode != 200) {
            if (statusCode == 401) {
                return ResponseEntity.status(401).body(result);
            } else if (statusCode == 402) {
                return ResponseEntity.status(402).body(result);
            } else if (statusCode == 403) {
                return ResponseEntity.status(403).body(result);
            } else if (statusCode == 409) {
                return ResponseEntity.status(409).body(result);
            } else if (statusCode == 429) {
                return ResponseEntity.status(429).body(result);
            } else {
                return ResponseEntity.status(500).body(result);
            }
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 下载回执
     */
    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String downloadToken = (String) request.get("download_token");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) request.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
        
        if (downloadToken == null || result == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "缺少必要参数：download_token 和 result"
            ));
        }
        
        String ip = getClientIpAddress(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        
        Map<String, Object> commitResult = authorizationService.downloadCommit(downloadToken, result, clientInfo, ip, ua);
        
        Integer statusCode = (Integer) commitResult.get("code");
        if (statusCode != null && statusCode != 200) {
            if (statusCode == 404) {
                return ResponseEntity.status(404).body(commitResult);
            } else if (statusCode == 410) {
                return ResponseEntity.status(410).body(commitResult);
            } else {
                return ResponseEntity.status(500).body(commitResult);
            }
        }
        
        return ResponseEntity.ok(commitResult);
    }
    
    /**
     * 基于令牌下载文件
     */
    @GetMapping("/file/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId, 
                                        @RequestParam String token,
                                        HttpServletRequest httpRequest) {
        try {
            // 1. 验证下载令牌
            Optional<DownloadToken> tokenOpt = downloadTokenRepository.findByToken(token);
            
            if (!tokenOpt.isPresent()) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false,
                    "error", "下载令牌不存在"
                ));
            }
            
            DownloadToken downloadToken = tokenOpt.get();
            
            // 2. 检查令牌是否过期
            if (downloadToken.getExpireAt().isBefore(java.time.LocalDateTime.now())) {
                return ResponseEntity.status(410).body(Map.of(
                    "ok", false,
                    "error", "下载令牌已过期"
                ));
            }
            
            // 3. 检查fileId是否匹配
            if (!fileId.equals(downloadToken.getFileId())) {
                return ResponseEntity.status(400).body(Map.of(
                    "ok", false,
                    "error", "文件ID不匹配"
                ));
            }
            
            // 4. 根据fileId查找源码文件
            SourcePackage source = sourcePackageRepository.findBySha256(fileId);
            
            if (source == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false,
                    "error", "源码文件不存在"
                ));
            }
            
            // 5. 检查文件是否存在
            String filePath = source.getPackagePath();
            if (filePath == null || !new java.io.File(filePath).exists()) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false,
                    "error", "源码文件路径不存在: " + filePath
                ));
            }
            
            // 6. 准备文件下载
            java.io.File file = new java.io.File(filePath);
            org.springframework.core.io.Resource resource = 
                new org.springframework.core.io.FileSystemResource(file);
            
            String filename = source.getName() + "_" + source.getVersion() + source.getPackageExt();
            
            // 7. 异步提交下载回执
            String ip = getClientIpAddress(httpRequest);
            submitDownloadCommit(token, true, file.length(), fileId, ip, httpRequest.getHeader("User-Agent"));
            
            // 8. 返回文件
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, 
                           "attachment; filename=\"" + filename + "\"")
                    .header("X-Download-Token", token)
                    .header("X-Source-Name", source.getName())
                    .header("X-Source-Version", source.getVersion())
                    .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
                    
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "ok", false,
                "error", "下载失败：" + e.getMessage()
            ));
        }
    }
    
    /**
     * 异步提交下载回执
     */
    private void submitDownloadCommit(String downloadToken, boolean success, long fileSize, 
                                     String fileId, String ip, String ua) {
        // 在新线程中异步提交回执，避免阻塞文件下载
        new Thread(() -> {
            try {
                Map<String, Object> downloadResult = Map.of(
                    "ok", success,
                    "size", fileSize,
                    "sha256", fileId
                );
                
                Map<String, Object> clientInfo = Map.of(
                    "download_method", "token_file_download"
                );
                
                authorizationService.downloadCommit(downloadToken, downloadResult, clientInfo, ip, ua);
                
            } catch (Exception e) {
                System.err.println("提交下载回执失败: " + e.getMessage());
            }
        }).start();
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
