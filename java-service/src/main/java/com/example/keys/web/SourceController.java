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

    @GetMapping("/sources/by-sha/{sha256}/logo")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> logoBySha(@PathVariable String sha256) throws Exception {
        java.nio.file.Path bucket = storage.bucketize(sha256);
        String[] names = new String[]{"logo.png", "logo.jpg", "logo.jpeg", "logo.webp"};
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
            resp.put("message", "该文件已存在，直接复用");
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
        try {
            // 获取源码包信息
            var sp = repo.findById(id);
            if (sp == null) {
                return Map.of("success", false, "error", "源码包不存在");
            }
            
            // 删除本地文件
            int filesDeleted = 0;
            
            // 删除源码包文件
            if (sp.getPackagePath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(sp.getPackagePath()));
                    filesDeleted++;
                } catch (Exception e) {
                    // 忽略文件删除错误
                }
            }
            
            // 删除缩略图
            if (sp.getThumbnailPath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(sp.getThumbnailPath()));
                    filesDeleted++;
                } catch (Exception e) {
                    // 忽略文件删除错误
                }
            }
            
            // 删除 logo
            if (sp.getLogoPath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(sp.getLogoPath()));
                    filesDeleted++;
                } catch (Exception e) {
                    // 忽略文件删除错误
                }
            }
            
            // 删除 preview
            if (sp.getPreviewPath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(sp.getPreviewPath()));
                    filesDeleted++;
                } catch (Exception e) {
                    // 忽略文件删除错误
                }
            }
            
            // 删除解压目录
            if (sp.getExtractedPath() != null) {
                try {
                    java.nio.file.Path extractedDir = java.nio.file.Path.of(sp.getExtractedPath());
                    if (java.nio.file.Files.exists(extractedDir)) {
                        java.nio.file.Files.walk(extractedDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try { java.nio.file.Files.delete(path); } catch (Exception e) {}
                            });
                        filesDeleted++;
                    }
                } catch (Exception e) {
                    // 忽略目录删除错误
                }
            }
            
            // 删除 bucket 目录（如果存在且为空）
            if (sp.getBucketRelPath() != null) {
                try {
                    java.nio.file.Path bucketDir = java.nio.file.Path.of(sp.getBucketRelPath());
                    if (java.nio.file.Files.exists(bucketDir) && java.nio.file.Files.isDirectory(bucketDir)) {
                        // 检查目录是否为空
                        try (var stream = java.nio.file.Files.list(bucketDir)) {
                            if (stream.findFirst().isEmpty()) {
                                java.nio.file.Files.delete(bucketDir);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略目录删除错误
                }
            }
            
            // 物理删除数据库记录
            repo.hardDelete(id);
            
            return Map.of("success", true, "message", "源码包已彻底删除", "filesDeleted", filesDeleted);
            
        } catch (Exception e) {
            return Map.of("success", false, "error", "删除失败: " + e.getMessage());
        }
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

    /**
     * 源码更新 - 覆盖更新现有源码包（直接替换文件，保持同一记录）
     * 如需创建新版本记录，请使用 /sources/new-version 接口
     */
    @PostMapping(value = "/sources/{id}/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateSourcePackage(@PathVariable String id,
                                                   @RequestParam(required = false) String version,
                                                   @RequestParam(value = "package", required = false) MultipartFile file,
                                                   @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
                                                   @RequestParam(value = "logo", required = false) MultipartFile logo,
                                                   @RequestParam(value = "name", required = false) String name,
                                                   @RequestParam(value = "description", required = false) String description,
                                                   @RequestParam(value = "country", required = false) String country,
                                                   @RequestParam(value = "website", required = false) String website) throws Exception {
        try {
            var sp = repo.findById(id);
            if (sp == null) {
                return Map.of("success", false, "error", "源码包不存在");
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("id", id);
            
            // 更新元信息
            String finalName = name != null && !name.trim().isEmpty() ? name : sp.getName();
            String finalDesc = description != null ? description : sp.getDescription();
            String finalCountry = country != null ? country : sp.getCountry();
            String finalWebsite = website != null ? website : sp.getWebsite();
            String finalVersion = version != null && !version.trim().isEmpty() ? version : sp.getVersion();
            
            if (name != null || description != null || country != null || website != null) {
                repo.updateMeta(id, finalName, sp.getCodeName(), finalDesc, finalCountry, finalWebsite);
                result.put("metaUpdated", true);
            }
            
            // 更新版本号（如果提供了新版本）
            if (version != null && !version.trim().isEmpty() && !version.equals(sp.getVersion())) {
                repo.updateVersion(id, version);
                result.put("versionUpdated", true);
                result.put("oldVersion", sp.getVersion());
            }
            
            // 更新缩略图
            if (thumbnail != null && !thumbnail.isEmpty()) {
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
                result.put("thumbnailUpdated", true);
            }
            
            // 更新 Logo
            if (logo != null && !logo.isEmpty()) {
                var bucketDir = storage.bucketize(sp.getSha256());
                var p = storage.saveImage(logo.getInputStream(), logo.getOriginalFilename(), bucketDir, "logo");
                String logoUrl = null;
                if (s3.isEnabled()) {
                    try (var in = java.nio.file.Files.newInputStream(p)) {
                        String fname = p.getFileName().toString();
                        String ct = fname.endsWith("png") ? "image/png" : (fname.endsWith("webp") ? "image/webp" : "image/jpeg");
                        logoUrl = s3.putObject(sp.getSha256() + "/" + fname, in, java.nio.file.Files.size(p), ct);
                    }
                }
                repo.updateLogo(id, p.toString(), logoUrl);
                result.put("logoUpdated", true);
            }
            
            // 如果上传了新源码包 - 覆盖更新
            if (file != null && !file.isEmpty()) {
                // 保存新文件
                var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
                
                // 检查SHA256是否与其他记录重复（排除自身）
                var existingBySha = repo.findBySha256(info.sha256());
                if (existingBySha != null && !existingBySha.getId().equals(id)) {
                    return Map.of("success", false, "error", "该文件已存在于其他源码包", 
                                 "existingId", existingBySha.getId(), 
                                 "existingVersion", existingBySha.getVersion());
                }
                
                // 删除旧文件
                String oldSha256 = sp.getSha256();
                if (sp.getPackagePath() != null) {
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(sp.getPackagePath())); } catch (Exception e) {}
                }
                
                var bucketDir = storage.bucketize(info.sha256());
                
                // 上传到S3
                String artifactUrl = null;
                if (s3.isEnabled()) {
                    try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                        String key = info.sha256() + "/artifact" + info.ext();
                        artifactUrl = s3.putObject(key, in, info.size(), "application/octet-stream");
                    }
                }
                
                // 更新数据库记录（覆盖旧的文件信息）
                repo.replacePackage(id, finalVersion, info.sha256(), bucketDir.toString(), 
                                   info.ext(), info.finalPath().toString(), artifactUrl, info.size());
                
                result.put("packageUpdated", true);
                result.put("oldSha256", oldSha256);
                result.put("newSha256", info.sha256());
                result.put("fileSize", info.size());
                if (artifactUrl != null) result.put("artifactUrl", artifactUrl);
            }
            
            result.put("version", finalVersion);
            result.put("codeName", sp.getCodeName());
            return result;
            
        } catch (Exception e) {
            return Map.of("success", false, "error", "更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 基于codeName创建新版本（快速发布新版本）
     */
    @PostMapping(value = "/sources/new-version", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createNewVersion(@RequestParam String codeName,
                                                @RequestParam String version,
                                                @RequestParam("package") MultipartFile file,
                                                @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
                                                @RequestParam(value = "changelog", required = false) String changelog) throws Exception {
        try {
            // 查找该codeName的最新版本
            var latestSp = repo.findLatestByCodeName(codeName);
            if (latestSp == null) {
                return Map.of("success", false, "error", "未找到代码名为 " + codeName + " 的源码包，请先创建");
            }
            
            // 检查版本是否已存在
            if (repo.existsByCodeNameAndVersion(codeName, version, null)) {
                return Map.of("success", false, "error", "版本 " + version + " 已存在");
            }
            
            // 保存新文件
            var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
            
            // 检查SHA256是否重复
            var existing = repo.findBySha256(info.sha256());
            if (existing != null) {
                return Map.of("success", false, "error", "该文件已存在", "existingId", existing.getId(),
                             "existingVersion", existing.getVersion());
            }
            
            var bucketDir = storage.bucketize(info.sha256());
            
            // 创建新的源码包记录，继承原有信息
            SourcePackage sp = new SourcePackage();
            sp.setId(UUID.randomUUID().toString());
            sp.setName(latestSp.getName());
            sp.setCodeName(codeName);
            sp.setVersion(version);
            sp.setDescription(changelog != null ? changelog : latestSp.getDescription());
            sp.setCountry(latestSp.getCountry());
            sp.setWebsite(latestSp.getWebsite());
            sp.setSha256(info.sha256());
            sp.setBucketRelPath(bucketDir.toString());
            sp.setPackageExt(info.ext());
            sp.setPackagePath(info.finalPath().toString());
            sp.setFileSize(info.size());
            sp.setStatus("uploaded");
            
            // 处理缩略图
            if (thumbnail != null && !thumbnail.isEmpty()) {
                var t = storage.saveThumbnail(thumbnail.getInputStream(), thumbnail.getOriginalFilename(), bucketDir);
                sp.setThumbnailPath(t.toString());
            } else if (latestSp.getThumbnailPath() != null) {
                // 复用原有缩略图
                sp.setThumbnailPath(latestSp.getThumbnailPath());
                sp.setThumbnailUrl(latestSp.getThumbnailUrl());
            }
            
            // 继承logo和preview
            sp.setLogoPath(latestSp.getLogoPath());
            sp.setLogoUrl(latestSp.getLogoUrl());
            sp.setPreviewPath(latestSp.getPreviewPath());
            sp.setPreviewUrl(latestSp.getPreviewUrl());
            
            // 上传到S3
            if (s3.isEnabled()) {
                try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                    String key = sp.getSha256() + "/artifact" + sp.getPackageExt();
                    String url = s3.putObject(key, in, sp.getFileSize(), "application/octet-stream");
                    sp.setArtifactUrl(url);
                }
                
                if (thumbnail != null && !thumbnail.isEmpty() && sp.getThumbnailPath() != null) {
                    var p = java.nio.file.Path.of(sp.getThumbnailPath());
                    try (var in = java.nio.file.Files.newInputStream(p)) {
                        String fname = p.getFileName().toString();
                        String ct = fname.endsWith("png") ? "image/png" : (fname.endsWith("webp") ? "image/webp" : "image/jpeg");
                        String url = s3.putObject(sp.getSha256() + "/" + fname, in, java.nio.file.Files.size(p), ct);
                        sp.setThumbnailUrl(url);
                    }
                }
            }
            
            repo.insert(sp);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("id", sp.getId());
            result.put("codeName", codeName);
            result.put("version", version);
            result.put("sha256", sp.getSha256());
            result.put("fileSize", sp.getFileSize());
            if (sp.getArtifactUrl() != null) result.put("artifactUrl", sp.getArtifactUrl());
            if (sp.getThumbnailUrl() != null) result.put("thumbnailUrl", sp.getThumbnailUrl());
            
            return result;
            
        } catch (Exception e) {
            return Map.of("success", false, "error", "创建新版本失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取源码包的所有版本
     */
    @GetMapping("/sources/versions/{codeName}")
    public Map<String, Object> getVersionsByCodeName(@PathVariable String codeName) {
        List<SourcePackage> versions = repo.findAllByCodeName(codeName);
        if (versions == null || versions.isEmpty()) {
            return Map.of("success", false, "error", "未找到代码名为 " + codeName + " 的源码包");
        }
        return Map.of("success", true, "data", versions, "count", versions.size());
    }
    
    /**
     * 检查源码是否有新版本可用
     * 根据 codeName 和当前版本号检查是否有更新
     */
    @GetMapping("/sources/check-update")
    public Map<String, Object> checkUpdate(@RequestParam String codeName, 
                                           @RequestParam String currentVersion) {
        try {
            SourcePackage latest = repo.findLatestByCodeName(codeName);
            
            if (latest == null) {
                return Map.of(
                    "success", false, 
                    "hasUpdate", false,
                    "error", "未找到代码名为 " + codeName + " 的源码包"
                );
            }
            
            // 比较版本号
            boolean hasUpdate = compareVersions(latest.getVersion(), currentVersion) > 0;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("hasUpdate", hasUpdate);
            result.put("currentVersion", currentVersion);
            result.put("latestVersion", latest.getVersion());
            result.put("codeName", codeName);
            
            if (hasUpdate) {
                result.put("latestSha256", latest.getSha256());
                result.put("latestName", latest.getName());
                result.put("latestDescription", latest.getDescription());
                result.put("latestFileSize", latest.getFileSize());
                result.put("latestUploadTime", latest.getUploadTime());
            }
            
            return result;
            
        } catch (Exception e) {
            return Map.of("success", false, "error", "检查更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 比较版本号 (返回 >0 表示 v1 > v2, <0 表示 v1 < v2, =0 表示相等)
     */
    private int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        
        // 去除 v 前缀
        v1 = v1.toLowerCase().replace("v", "").trim();
        v2 = v2.toLowerCase().replace("v", "").trim();
        
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (num1 != num2) {
                return num1 - num2;
            }
        }
        
        return 0;
    }
    
    /**
     * 解析版本号部分
     */
    private int parseVersionPart(String part) {
        try {
            // 移除非数字后缀 (如 1.0.0-beta)
            String numPart = part.replaceAll("[^0-9].*", "");
            return numPart.isEmpty() ? 0 : Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * 自动递增版本号
     */
    private String incrementVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "1.0.1";
        }
        
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length >= 3) {
                int patch = Integer.parseInt(parts[parts.length - 1]) + 1;
                parts[parts.length - 1] = String.valueOf(patch);
                return String.join(".", parts);
            } else if (parts.length == 2) {
                int minor = Integer.parseInt(parts[1]) + 1;
                return parts[0] + "." + minor + ".0";
            } else {
                return currentVersion + ".1";
            }
        } catch (NumberFormatException e) {
            return currentVersion + ".1";
        }
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


