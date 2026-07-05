package com.example.keys.web;

import com.example.keys.service.AuthorizationService;
import com.example.keys.service.DownloadReceiptService;
import com.example.keys.service.SourceAccessService;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.repo.DownloadTokenRepository;
import com.example.keys.repo.LicenseRepository;
import com.example.keys.model.DownloadToken;
import com.example.keys.model.License;
import com.example.keys.model.SourcePackage;
import com.example.keys.util.IpUtils;
import com.example.keys.util.ResponseHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.time.LocalDateTime;
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

    @Autowired
    private LicenseRepository licenseRepository;
    
    @Autowired
    private DownloadReceiptService downloadReceiptService;

    @Autowired
    private SourceAccessService sourceAccessService;

    @PostMapping("/preauth")
    public ResponseEntity<?> preauth(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> license = (Map<String, Object>) request.get("license");
        String hwid = (String) request.get("hwid");
        String fileId = (String) request.get("file_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> clientInfo = (Map<String, Object>) request.get("client");
        Boolean isUpdate = (Boolean) request.get("is_update");
        String fromVersion = (String) request.get("from_version");
        
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
        
        String ip = IpUtils.getClientIpAddress(httpRequest);
        String installCode = sourceAccessService.extractInstallCode(httpRequest, request);
        
        Map<String, Object> result;
        if (Boolean.TRUE.equals(isUpdate) && fromVersion != null) {
            result = authorizationService.downloadPreauthForUpdate(payload, sig, hwid, fileId, clientInfo, ip, fromVersion, installCode);
        } else {
            result = authorizationService.downloadPreauth(payload, sig, hwid, fileId, clientInfo, ip, installCode);
        }
        
        return ResponseHelper.fromServiceResult(result);
    }
    
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
        
        String ip = IpUtils.getClientIpAddress(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        
        Map<String, Object> commitResult = authorizationService.downloadCommit(downloadToken, result, clientInfo, ip, ua);
        return ResponseHelper.fromServiceResult(commitResult);
    }
    
    @GetMapping("/{token}")
    public ResponseEntity<?> downloadByToken(@PathVariable String token, HttpServletRequest httpRequest) {
        Optional<DownloadToken> tokenOpt = downloadTokenRepository.findByToken(token);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "下载令牌不存在"));
        }
        return serveTokenDownload(tokenOpt.get().getFileId(), token, httpRequest);
    }

    @GetMapping("/file/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId,
                                        @RequestParam String token,
                                        HttpServletRequest httpRequest) {
        return serveTokenDownload(fileId, token, httpRequest);
    }

    private ResponseEntity<?> serveTokenDownload(String fileId, String token, HttpServletRequest httpRequest) {
        try {
            Optional<DownloadToken> tokenOpt = downloadTokenRepository.findByToken(token);

            if (tokenOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false, "error", "下载令牌不存在"));
            }

            DownloadToken downloadToken = tokenOpt.get();

            if (downloadToken.getExpireAt().isBefore(LocalDateTime.now())) {
                return ResponseEntity.status(410).body(Map.of(
                    "ok", false, "error", "下载令牌已过期"));
            }

            if (Boolean.TRUE.equals(downloadToken.getUsed())) {
                return ResponseEntity.status(409).body(Map.of(
                    "ok", false, "error", "下载令牌已使用"));
            }

            if (!fileId.equals(downloadToken.getFileId()) ||
                    (downloadToken.getSourceId() != null && !fileId.equals(downloadToken.getSourceId()))) {
                return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", "文件ID不匹配"));
            }

            Optional<License> licenseOpt = licenseRepository.findById(downloadToken.getLicenseId());
            if (licenseOpt.isEmpty()) {
                return ResponseEntity.status(403).body(Map.of("ok", false, "error", "许可证不存在"));
            }
            License license = licenseOpt.get();
            if (!"ok".equals(license.getStatus()) || license.getValidTo().isBefore(LocalDateTime.now())) {
                return ResponseEntity.status(403).body(Map.of("ok", false, "error", "许可证状态异常"));
            }
            if (downloadToken.getLicenseCode() == null || downloadToken.getHwid() == null ||
                    !downloadToken.getLicenseCode().equals(license.getCode()) ||
                    !downloadToken.getHwid().equals(license.getHwid())) {
                return ResponseEntity.status(403).body(Map.of("ok", false, "error", "下载令牌绑定信息不匹配"));
            }

            SourcePackage source = sourcePackageRepository.findBySha256(fileId);
            if (source == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false, "error", "源码文件不存在"));
            }

            String filePath = source.getPackagePath();
            if (filePath == null || !new File(filePath).exists()) {
                return ResponseEntity.status(404).body(Map.of(
                    "ok", false, "error", "源码文件路径不存在"));
            }

            int reserved = downloadTokenRepository.markAsUsed(token);
            if (reserved <= 0) {
                return ResponseEntity.status(409).body(Map.of("ok", false, "error", "下载令牌已使用"));
            }

            File file = new File(filePath);
            Resource resource = new FileSystemResource(file);
            String filename = source.getName() + "_" + source.getVersion() + source.getPackageExt();

            String ip = IpUtils.getClientIpAddress(httpRequest);
            downloadReceiptService.submitReceipt(token, true, file.length(), fileId,
                    ip, httpRequest.getHeader("User-Agent"), "token_file_download");

            sourcePackageRepository.incrementDownloadCount(fileId);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header("X-Download-Token", token)
                    .header("X-Source-Name", source.getName())
                    .header("X-Source-Version", source.getVersion())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "ok", false, "error", "下载失败：" + e.getMessage()));
        }
    }
}
