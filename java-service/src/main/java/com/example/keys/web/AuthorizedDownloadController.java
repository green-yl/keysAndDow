package com.example.keys.web;

import com.example.keys.model.SourcePackage;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.service.AuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.Map;


@RestController
@RequestMapping("/api/authorized-download")
public class AuthorizedDownloadController {
    
    @Autowired
    private AuthorizationService authorizationService;
    
    @Autowired
    private SourcePackageRepository sourcePackageRepository;
    
    /**
     * 授权下载源码 - 需要许可证验证，会消耗下载次数
     */
    @PostMapping("/source/{sha256}")
    public ResponseEntity<?> downloadSourceWithAuth(@PathVariable String sha256,
                                                   @RequestBody Map<String, Object> request,
                                                   HttpServletRequest httpRequest) {
        try {
            // 1. 验证请求参数
            @SuppressWarnings("unchecked")
            Map<String, Object> license = (Map<String, Object>) request.get("license");
            String hwid = (String) request.get("hwid");
            @SuppressWarnings("unchecked")
            Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
            
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
            
            // 2. 查找源码包
            SourcePackage source = sourcePackageRepository.findBySha256(sha256);
            if (source == null) {
                return ResponseEntity.notFound().build();
            }
            
            String ip = getClientIpAddress(httpRequest);
            
            // 3. 下载预授权
            Map<String, Object> preauthResult = authorizationService.downloadPreauth(
                payload, sig, hwid, source.getCodeName() + "_" + source.getVersion(), clientInfo, ip
            );
            
            if (!(Boolean) preauthResult.get("ok")) {
                Integer statusCode = (Integer) preauthResult.get("code");
                return ResponseEntity.status(statusCode != null ? statusCode : 500).body(preauthResult);
            }
            
            // 4. 获取文件路径
            String filePath = source.getPackagePath();
            if (filePath == null || !new File(filePath).exists()) {
                return ResponseEntity.notFound().build();
            }
            
            // 5. 准备文件下载
            File file = new File(filePath);
            Resource resource = new FileSystemResource(file);
            
            // 6. 异步提交下载回执（假设下载成功）
            String downloadToken = (String) preauthResult.get("download_token");
            submitDownloadReceipt(downloadToken, true, file.length(), sha256, ip, httpRequest.getHeader("User-Agent"));
            
            // 7. 返回文件
            String filename = source.getName() + "_" + source.getVersion() + source.getPackageExt();
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header("X-Download-Token", downloadToken)
                    .header("X-Source-Name", source.getName())
                    .header("X-Source-Version", source.getVersion())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
                    
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "ok", false,
                "error", "下载失败：" + e.getMessage()
            ));
        }
    }
    
    /**
     * 获取源码下载统计信息
     */
    @GetMapping("/stats/{sha256}")
    public ResponseEntity<?> getSourceDownloadStats(@PathVariable String sha256) {
        try {
            SourcePackage sourcePackage = sourcePackageRepository.findBySha256(sha256);
            if (sourcePackage == null) {
                return ResponseEntity.notFound().build();
            }
            

            
            // TODO: 实现下载统计查询
            // 这里可以添加查询该源码的总下载次数、最近下载记录等
            
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "source", Map.of(
                    "name", sourcePackage.getName(),
                    "codeName", sourcePackage.getCodeName(),
                    "version", sourcePackage.getVersion(),
                    "sha256", sourcePackage.getSha256()
                ),
                "stats", Map.of(
                    "totalDownloads", 0, // TODO: 实际统计
                    "recentDownloads", 0 // TODO: 实际统计
                )
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "ok", false,
                "error", "获取统计失败：" + e.getMessage()
            ));
        }
    }
    
    /**
     * 异步提交下载回执
     */
    private void submitDownloadReceipt(String downloadToken, boolean success, long fileSize, 
                                     String sha256, String ip, String ua) {
        // 在新线程中异步提交回执，避免阻塞文件下载
        new Thread(() -> {
            try {
                Map<String, Object> downloadResult = Map.of(
                    "ok", success,
                    "size", fileSize,
                    "sha256", sha256
                );
                
                Map<String, Object> clientInfo = Map.of(
                    "download_method", "authorized_source_download"
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
