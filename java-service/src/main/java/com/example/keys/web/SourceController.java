package com.example.keys.web;

import com.example.keys.model.SourcePackage;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.service.StorageService;
import com.example.keys.service.S3Service;
import com.example.keys.service.ZipService;
import com.example.keys.service.AuthorizationService;
import com.example.keys.service.ServerManagementService;
import com.example.keys.service.LicenseSignatureService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;

import org.springframework.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*", maxAge = 3600, allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@RequestMapping("/api")
public class SourceController {
    private final SourcePackageRepository repo;
    private final StorageService storage;
    private final ZipService zip;
    private final S3Service s3;
    private final AuthorizationService authorizationService;
    private final ServerManagementService serverManagementService;
    private final LicenseSignatureService licenseSignatureService;

    public SourceController(SourcePackageRepository repo, StorageService storage, ZipService zip, 
                           S3Service s3, AuthorizationService authorizationService, 
                           ServerManagementService serverManagementService,
                           LicenseSignatureService licenseSignatureService) {
        this.repo = repo; 
        this.storage = storage; 
        this.zip = zip; 
        this.s3 = s3;
        this.authorizationService = authorizationService;
        this.serverManagementService = serverManagementService;
        this.licenseSignatureService = licenseSignatureService;
    }

    @GetMapping("/sources/by-sha/{sha256}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadBySha(@PathVariable String sha256) throws Exception {
        // 先根据数据库记录的 package_path 精确下载
        var sp = repo.findBySha256(sha256);
        if (sp != null && sp.getPackagePath() != null) {
            java.nio.file.Path p = java.nio.file.Path.of(sp.getPackagePath());
            if (!p.isAbsolute()) {
                // 相对路径时相对于应用目录
                p = java.nio.file.Paths.get(".").resolve(sp.getPackagePath()).normalize();
            }
            if (java.nio.file.Files.exists(p)) {
                String fname = p.getFileName().toString();
                var res = new org.springframework.core.io.PathResource(p);
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=" + fname)
                        .contentLength(java.nio.file.Files.size(p))
                        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                        .body(res);
            }
        }
        // 回退到分桶目录的通用命名
        String[] names = new String[]{"artifact.zip", "artifact.tgz", "artifact.tar.gz", "artifact.tar", "artifact.gz"};
        java.nio.file.Path bucket = storage.bucketize(sha256);
        for (String n : names) {
            java.nio.file.Path p = bucket.resolve(n);
            if (java.nio.file.Files.exists(p)) {
                var res = new org.springframework.core.io.PathResource(p);
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=" + n)
                        .contentLength(java.nio.file.Files.size(p))
                        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                        .body(res);
            }
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    // 短链接形式：/d/{sha256} - 需要授权
    @GetMapping("/d/{sha256}")
    public org.springframework.http.ResponseEntity<?> shortDownload(@PathVariable String sha256, 
                                                                   @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                                   @RequestParam(value = "license_payload", required = false) String licensePayload,
                                                                   @RequestParam(value = "license_sig", required = false) String licenseSig,
                                                                   @RequestParam(value = "hwid", required = false) String hwid,
                                                                   HttpServletRequest request) throws Exception {
        
        // 验证授权参数
        if (licensePayload == null || licenseSig == null || hwid == null) {
            return org.springframework.http.ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "需要授权参数：license_payload, license_sig, hwid"));
        }
        
        // 验证许可证和服务器IP绑定
        String serverIp = getClientIpAddress(request);
        Map<String, Object> authResult = validateLicenseAndServerBinding(licensePayload, licenseSig, hwid, serverIp);
        
        if (!(Boolean) authResult.get("ok")) {
            return org.springframework.http.ResponseEntity.status((Integer) authResult.getOrDefault("code", 403))
                .body(authResult);
        }
        
        // 授权验证通过，执行下载
        return downloadBySha(sha256);
    }

    // 按代码名与版本下载（外部可CORS使用）- 需要授权
    @GetMapping("/download")
    public org.springframework.http.ResponseEntity<?> downloadByCode(@RequestParam("code") String codeName,
                                                                     @RequestParam(value = "version", required = false) String version,
                                                                     @RequestParam(value = "license_payload", required = false) String licensePayload,
                                                                     @RequestParam(value = "license_sig", required = false) String licenseSig,
                                                                     @RequestParam(value = "hwid", required = false) String hwid,
                                                                     HttpServletRequest request) throws Exception {
        
        // 验证授权参数
        if (licensePayload == null || licenseSig == null || hwid == null) {
            return org.springframework.http.ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "需要授权参数：license_payload, license_sig, hwid"));
        }
        
        // 验证许可证和服务器IP绑定
        String serverIp = getClientIpAddress(request);
        Map<String, Object> authResult = validateLicenseAndServerBinding(licensePayload, licenseSig, hwid, serverIp);
        
        if (!(Boolean) authResult.get("ok")) {
            return org.springframework.http.ResponseEntity.status((Integer) authResult.getOrDefault("code", 403))
                .body(authResult);
        }
        
        // 查找源码包
        SourcePackage sp = (version == null || version.isBlank()) ? repo.findLatestByCodeName(codeName) : repo.findByCodeAndVersion(codeName, version);
        if (sp == null) return org.springframework.http.ResponseEntity.status(404).body(Map.of("error","not found"));
        
        // 如果有S3直链，重定向到S3（302）；否则返回本地下载流
        if (sp.getArtifactUrl() != null && !sp.getArtifactUrl().isBlank()) {
            return org.springframework.http.ResponseEntity.status(302).header("Location", sp.getArtifactUrl()).build();
        }
        return downloadBySha(sp.getSha256());
    }

    @GetMapping("/sources/by-sha/{sha256}/thumbnail")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> thumbnailBySha(@PathVariable String sha256) throws Exception {
        java.nio.file.Path bucket = storage.bucketize(sha256);
        String[] names = new String[]{"thumbnail.png", "thumbnail.jpg", "thumbnail.jpeg", "thumbnail.webp"};
        for (String n : names) {
            java.nio.file.Path p = bucket.resolve(n);
            if (java.nio.file.Files.exists(p)) {
                var res = new org.springframework.core.io.PathResource(p);
                org.springframework.http.MediaType mt = n.endsWith("png") ? org.springframework.http.MediaType.IMAGE_PNG :
                        (n.endsWith("webp") ? org.springframework.http.MediaType.valueOf("image/webp") : org.springframework.http.MediaType.IMAGE_JPEG);
                return org.springframework.http.ResponseEntity.ok().contentType(mt).body(res);
            }
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    @GetMapping("/sources")
    public Map<String, Object> list(@RequestParam(value = "q", required = false) String q) {
        List<SourcePackage> data = repo.findAll(q);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true); resp.put("data", data); return resp;
    }

    @GetMapping("/sources/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        SourcePackage sp = repo.findById(id);
        Map<String, Object> resp = new HashMap<>();
        if (sp == null) { resp.put("error", "not found"); return resp; }
        resp.put("success", true); resp.put("data", sp); return resp;
    }

    @PostMapping(value = "/sources/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("package") MultipartFile file,
                                      @RequestParam String name,
                                      @RequestParam String codeName,
                                      @RequestParam String version,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) String country,
                                      @RequestParam(required = false) String website,
                                      @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
                                      @RequestParam(value = "logo", required = false) MultipartFile logo,
                                      @RequestParam(value = "preview", required = false) MultipartFile preview) throws Exception {
        try {
        var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
        // 去重：如已存在相同sha256的包，直接返回已有记录信息
        var existing = repo.findBySha256(info.sha256());
        if (existing != null) {
            Map<String,Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("dedup", true);
            resp.put("id", existing.getId());
            resp.put("sha256", existing.getSha256());
            if (existing.getArtifactUrl() != null) resp.put("artifactUrl", existing.getArtifactUrl());
            if (existing.getThumbnailUrl() != null) resp.put("thumbnailUrl", existing.getThumbnailUrl());
            return resp;
        }
        var bucketDir = storage.bucketize(info.sha256());
        SourcePackage sp = new SourcePackage();
        sp.setId(UUID.randomUUID().toString());
        sp.setName(name); sp.setCodeName(codeName); sp.setVersion(version);
        sp.setDescription(description); sp.setCountry(country); sp.setWebsite(website); sp.setSha256(info.sha256());
        sp.setBucketRelPath(bucketDir.toString());
        sp.setPackageExt(info.ext());
        sp.setPackagePath(info.finalPath().toString());
        sp.setFileSize(info.size()); sp.setStatus("uploaded");
        if (thumbnail != null && !thumbnail.isEmpty()) {
            var t = storage.saveThumbnail(thumbnail.getInputStream(), thumbnail.getOriginalFilename(), bucketDir);
            sp.setThumbnailPath(t.toString());
        }
        if (logo != null && !logo.isEmpty()) {
            var p = storage.saveImage(logo.getInputStream(), logo.getOriginalFilename(), bucketDir, "logo");
            sp.setLogoPath(p.toString());
        }
        if (preview != null && !preview.isEmpty()) {
            var p = storage.saveImage(preview.getInputStream(), preview.getOriginalFilename(), bucketDir, "preview");
            sp.setPreviewPath(p.toString());
        }
        // 可选：同步上传到S3（artifact）
        if (s3.isEnabled()) {
            try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                String key = sp.getSha256() + "/artifact" + sp.getPackageExt();
                String url = s3.putObject(key, in, sp.getFileSize(), "application/octet-stream");
                sp.setArtifactUrl(url);
            }
            // 缩略图
            if (sp.getThumbnailPath() != null) {
                var p = java.nio.file.Path.of(sp.getThumbnailPath());
                try (var in = java.nio.file.Files.newInputStream(p)) {
                    String ext = p.getFileName().toString().toLowerCase();
                    String ct = ext.endsWith("png") ? "image/png" : (ext.endsWith("webp") ? "image/webp" : "image/jpeg");
                    String url = s3.putObject(sp.getSha256() + "/" + p.getFileName(), in, java.nio.file.Files.size(p), ct);
                    sp.setThumbnailUrl(url);
                }
            }
            // logo
            if (sp.getLogoPath() != null) {
                var p = java.nio.file.Path.of(sp.getLogoPath());
                try (var in = java.nio.file.Files.newInputStream(p)) {
                    String ext = p.getFileName().toString().toLowerCase();
                    String ct = ext.endsWith("png") ? "image/png" : (ext.endsWith("webp") ? "image/webp" : "image/jpeg");
                    String url = s3.putObject(sp.getSha256() + "/" + p.getFileName(), in, java.nio.file.Files.size(p), ct);
                    sp.setLogoUrl(url);
                }
            }
            // preview
            if (sp.getPreviewPath() != null) {
                var p = java.nio.file.Path.of(sp.getPreviewPath());
                try (var in = java.nio.file.Files.newInputStream(p)) {
                    String ext = p.getFileName().toString().toLowerCase();
                    String ct = ext.endsWith("png") ? "image/png" : (ext.endsWith("webp") ? "image/webp" : "image/jpeg");
                    String url = s3.putObject(sp.getSha256() + "/" + p.getFileName(), in, java.nio.file.Files.size(p), ct);
                    sp.setPreviewUrl(url);
                }
            }
        }
        repo.insert(sp);
        Map<String,Object> resp = new HashMap<>();
        resp.put("success", true); resp.put("id", sp.getId()); resp.put("sha256", sp.getSha256());
        if (sp.getArtifactUrl() != null) resp.put("artifactUrl", sp.getArtifactUrl());
        if (sp.getThumbnailUrl() != null) resp.put("thumbnailUrl", sp.getThumbnailUrl());
        return resp;
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/sources/import")
    public Map<String, Object> importUrl(@RequestBody Map<String, String> body) throws Exception {
        String url = body.get("url");
        String name = body.get("name");
        String codeName = body.get("codeName");
        String version = body.get("version");
        String description = body.getOrDefault("description", "");
        if (!StringUtils.hasText(url) || !StringUtils.hasText(name) || !StringUtils.hasText(codeName) || !StringUtils.hasText(version)) {
            return Map.of("error", "missing params");
        }
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        // 简化：直接用body流
        String filename = Path.of(URI.create(url).getPath()).getFileName().toString();
        var info = storage.saveAndHash(resp.body(), filename);

        SourcePackage sp = new SourcePackage();
        sp.setId(UUID.randomUUID().toString());
        sp.setName(name); sp.setCodeName(codeName); sp.setVersion(version);
        sp.setDescription(description); sp.setSha256(info.sha256());
        sp.setBucketRelPath(storage.bucketize(info.sha256()).toString());
        sp.setPackageExt(info.ext());
        sp.setPackagePath(info.finalPath().toString());
        sp.setFileSize(info.size()); sp.setStatus("uploaded");
        repo.insert(sp);
        return Map.of("success", true, "id", sp.getId(), "sha256", sp.getSha256());
    }

    @DeleteMapping("/sources/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        repo.markDeleted(id);
        return Map.of("success", true);
    }

    @PostMapping(value = "/sources/{id}/meta", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> updateMeta(@PathVariable String id,
                                          @RequestParam String name,
                                          @RequestParam String codeName,
                                          @RequestParam(required = false) String description,
                                          @RequestParam(required = false) String country,
                                          @RequestParam(required = false) String website) {
        repo.updateMeta(id, name, codeName, description, country, website);
        return Map.of("success", true);
    }

    @PostMapping(value = "/sources/{id}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateThumbnail(@PathVariable String id, @RequestParam("thumbnail") MultipartFile thumbnail) throws Exception {
        var sp = repo.findById(id);
        if (sp == null) return Map.of("error", "not found");
        var bucketDir = storage.bucketize(sp.getSha256());
        var t = storage.saveThumbnail(thumbnail.getInputStream(), thumbnail.getOriginalFilename(), bucketDir);
        String thumbUrl = null;
        if (s3.isEnabled()) {
            try (var in = java.nio.file.Files.newInputStream(t)) {
                String fname = t.getFileName().toString();
                String ct = fname.endsWith("png") ? "image/png" : (fname.endsWith("webp") ? "image/webp" : "image/jpeg");
                thumbUrl = s3.putObject(sp.getSha256() + "/" + fname, in, java.nio.file.Files.size(t), ct);
            }
        }
        repo.updateThumbnail(id, t.toString(), thumbUrl);
        return Map.of("success", true, "thumbnailPath", t.toString(), "thumbnailUrl", thumbUrl);
    }

    @PostMapping(value = "/sources/{id}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> replacePackage(@PathVariable String id,
                                              @RequestParam String version,
                                              @RequestParam("package") MultipartFile file) throws Exception {
        var sp = repo.findById(id);
        if (sp == null) return Map.of("error", "not found");
        if (repo.existsByCodeNameAndVersion(sp.getCodeName(), version, id)) {
            return Map.of("error", "该代码名与版本已存在");
        }
        var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
        var bucketDir = storage.bucketize(info.sha256());
        String artifactUrl = null;
        if (s3.isEnabled()) {
            try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                String key = info.sha256() + "/artifact" + info.ext();
                artifactUrl = s3.putObject(key, in, info.size(), "application/octet-stream");
            }
        }
        repo.replacePackage(id, version, info.sha256(), bucketDir.toString(), info.ext(), info.finalPath().toString(), artifactUrl, info.size());
        return Map.of("success", true, "version", version, "sha256", info.sha256(), "artifactUrl", artifactUrl);
    }

    @GetMapping("/sources/{id}/verify")
    public Map<String, Object> verify(@PathVariable String id) throws Exception {
        SourcePackage sp = repo.findById(id);
        if (sp == null) return Map.of("error", "not found");
        var md = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(Path.of(sp.getPackagePath())); var dis = new java.security.DigestInputStream(in, md)) {
            byte[] buf = new byte[1024*1024]; while (dis.read(buf) != -1) {}
        }
        String actual = StorageService.toSha256Hex(md.digest());
        return Map.of("success", true, "match", actual.equalsIgnoreCase(sp.getSha256()), "actual", actual, "expected", sp.getSha256());
    }

    @PostMapping("/sources/{id}/extract")
    public Map<String, Object> extract(@PathVariable String id) throws Exception {
        SourcePackage sp = repo.findById(id);
        if (sp == null) return Map.of("error", "not found");
        repo.updateStatus(id, "extracting");
        Path dest = storage.releasesPath(sp.getCodeName(), sp.getVersion());
        if (sp.getPackageExt().equalsIgnoreCase(".zip") || sp.getPackagePath().toLowerCase().endsWith(".zip")) {
            zip.extractZip(Path.of(sp.getPackagePath()), dest);
        } else {
            throw new IllegalArgumentException("当前仅支持zip解压");
        }
        repo.updateExtracted(id, dest.toString());
        return Map.of("success", true, "extractedPath", dest.toString());
    }
    
    /**
     * 验证许可证和服务器IP绑定
     */
    private Map<String, Object> validateLicenseAndServerBinding(String licensePayload, String licenseSig, String hwid, String serverIp) {
        try {
            // 1. 验证许可证状态
            Map<String, Object> statusResult = authorizationService.getLicenseStatus(licensePayload, licenseSig, hwid);
            if (!(Boolean) statusResult.get("ok")) {
                return statusResult;
            }
            
            String status = (String) statusResult.get("status");
            if (!"ok".equals(status)) {
                return Map.of(
                    "ok", false,
                    "error", "许可证状态异常：" + status,
                    "code", "expired".equals(status) ? 403 : 402
                );
            }
            
            // 2. 获取许可证信息并验证服务器IP绑定
            Map<String, Object> payload = licenseSignatureService.parseLicensePayload(licensePayload);
            String code = (String) payload.get("code");
            
            Map<String, Object> serverBindingResult = serverManagementService.checkServerBinding(code, hwid, serverIp);
            if (!(Boolean) serverBindingResult.get("ok")) {
                return serverBindingResult;
            }
            
            return Map.of("ok", true);
            
        } catch (Exception e) {
            return Map.of(
                "ok", false,
                "error", "授权验证失败：" + e.getMessage(),
                "code", 500
            );
        }
    }
    
    /**
     * 获取客户端IP地址
     */
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


