package com.example.keys.web;

import com.example.keys.model.SourcePackage;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.service.AuthorizationService;
import com.example.keys.service.DownloadReceiptService;
import com.example.keys.service.SourceAccessService;
import com.example.keys.util.IpUtils;
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
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthorizedDownloadController {
    
    @Autowired
    private AuthorizationService authorizationService;
    
    @Autowired
    private SourcePackageRepository sourcePackageRepository;
    
    @Autowired
    private DownloadReceiptService downloadReceiptService;

    @Autowired
    private SourceAccessService sourceAccessService;

    @PostMapping("/source/{sha256}")
    public ResponseEntity<?> downloadSourceWithAuth(@PathVariable String sha256,
                                                   @RequestBody Map<String, Object> request,
                                                   HttpServletRequest httpRequest) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> license = (Map<String, Object>) request.get("license");
            String hwid = (String) request.get("hwid");
            @SuppressWarnings("unchecked")
            Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
            
            if (license == null || hwid == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", "缺少必要参数：license 和 hwid"));
            }
            
            String payload = (String) license.get("payload");
            String sig = (String) license.get("sig");
            
            if (payload == null || sig == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", "许可证格式错误"));
            }
            
            SourcePackage source = sourcePackageRepository.findBySha256(sha256);
            if (source == null) {
                return ResponseEntity.notFound().build();
            }
            
            String ip = IpUtils.getClientIpAddress(httpRequest);
            String installCode = sourceAccessService.extractInstallCode(httpRequest, request);
            
            Map<String, Object> preauthResult = authorizationService.downloadPreauth(
                payload, sig, hwid, sha256, clientInfo, ip, installCode);
            
            if (!(Boolean) preauthResult.get("ok")) {
                Integer statusCode = (Integer) preauthResult.get("code");
                return ResponseEntity.status(statusCode != null ? statusCode : 500).body(preauthResult);
            }
            
            String filePath = source.getPackagePath();
            if (filePath == null || !new File(filePath).exists()) {
                return ResponseEntity.notFound().build();
            }
            
            File file = new File(filePath);
            Resource resource = new FileSystemResource(file);
            
            String downloadToken = (String) preauthResult.get("download_token");
            downloadReceiptService.submitReceipt(downloadToken, true, file.length(),
                    sha256, ip, httpRequest.getHeader("User-Agent"), "authorized_source_download");
            
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
                "ok", false, "error", "下载失败：" + e.getMessage()));
        }
    }
    
    @GetMapping("/stats/{sha256}")
    public ResponseEntity<?> getSourceDownloadStats(@PathVariable String sha256,
                                                    HttpServletRequest httpRequest) {
        SourcePackage sourcePackage = sourcePackageRepository.findBySha256(sha256);
        if (sourcePackage == null) {
            return ResponseEntity.notFound().build();
        }
        if (sourcePackage.isHiddenEnabled() && !sourceAccessService.isAdminRequest(httpRequest)) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "source", Map.of(
                "name", sourcePackage.getName(),
                "codeName", sourcePackage.getCodeName(),
                "version", sourcePackage.getVersion(),
                "sha256", sourcePackage.getSha256()
            ),
            "stats", Map.of(
                "totalDownloads", sourcePackage.getDownloadCount() != null ? sourcePackage.getDownloadCount() : 0
            )
        ));
    }
}
