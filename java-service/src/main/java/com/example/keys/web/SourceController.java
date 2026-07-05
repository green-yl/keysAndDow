package com.example.keys.web;

import com.example.keys.model.SourcePackage;
import com.example.keys.repo.SourcePackageRepository;
import com.example.keys.service.StorageService;
import com.example.keys.service.S3Service;
import com.example.keys.service.ZipService;
import com.example.keys.service.AuthorizationService;
import com.example.keys.service.DownloadReceiptService;
import com.example.keys.service.ServerManagementService;
import com.example.keys.service.LicenseSignatureService;
import com.example.keys.service.SourceAccessService;
import org.springframework.http.MediaType;

import org.springframework.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.keys.util.IpUtils;
import com.example.keys.util.VersionUtils;

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
    private final SourceAccessService sourceAccessService;
    private final DownloadReceiptService downloadReceiptService;

    public SourceController(SourcePackageRepository repo, StorageService storage, ZipService zip, 
                           S3Service s3, AuthorizationService authorizationService, 
                           ServerManagementService serverManagementService,
                           LicenseSignatureService licenseSignatureService,
                           SourceAccessService sourceAccessService,
                           DownloadReceiptService downloadReceiptService) {
        this.repo = repo; 
        this.storage = storage; 
        this.zip = zip; 
        this.s3 = s3;
        this.authorizationService = authorizationService;
        this.serverManagementService = serverManagementService;
        this.licenseSignatureService = licenseSignatureService;
        this.sourceAccessService = sourceAccessService;
        this.downloadReceiptService = downloadReceiptService;
    }

    @GetMapping("/sources/by-sha/{sha256}/download")
    public org.springframework.http.ResponseEntity<?> downloadBySha(@PathVariable String sha256,
                                                                   @RequestParam(value = "license_payload", required = false) String licensePayload,
                                                                   @RequestParam(value = "license_sig", required = false) String licenseSig,
                                                                   @RequestParam(value = "hwid", required = false) String hwid,
                                                                   HttpServletRequest request) throws Exception {
        var sp = repo.findBySha256(sha256);
        if (sp == null) {
            return org.springframework.http.ResponseEntity.status(404).body(Map.of("ok", false, "error", "源码文件不存在"));
        }

        Map<String, Object> accessResult = validateDownloadAccess(sp, licensePayload, licenseSig, hwid, request);
        if (!(Boolean) accessResult.get("ok")) {
            return org.springframework.http.ResponseEntity.status((Integer) accessResult.getOrDefault("code", 403)).body(accessResult);
        }

        return serveSourceFile(sp, accessResult, request, "by_sha_download");
    }

    private org.springframework.http.ResponseEntity<?> serveSourceFile(SourcePackage sp) throws Exception {
        return serveSourceFile(sp, null, null, null);
    }

    private org.springframework.http.ResponseEntity<?> serveSourceFile(SourcePackage sp,
                                                                      Map<String, Object> accessResult,
                                                                      HttpServletRequest request,
                                                                      String method) throws Exception {
        java.nio.file.Path p = resolveSourceFilePath(sp);
        if (p == null) {
            return org.springframework.http.ResponseEntity.status(404).body(Map.of("ok", false, "error", "源码文件路径不存在"));
        }

        if (accessResult != null && request != null) {
            Object token = accessResult.get("download_token");
            if (token instanceof String downloadToken && StringUtils.hasText(downloadToken)) {
                downloadReceiptService.submitReceipt(downloadToken, true, java.nio.file.Files.size(p),
                        sp.getSha256(), IpUtils.getClientIpAddress(request), request.getHeader("User-Agent"), method);
            }
        }

        String fname = p.getFileName().toString();
        var res = new org.springframework.core.io.PathResource(p);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + fname)
                .contentLength(java.nio.file.Files.size(p))
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    private java.nio.file.Path resolveSourceFilePath(SourcePackage sp) throws Exception {
        // 先根据数据库记录的 package_path 精确下载
        if (sp != null && sp.getPackagePath() != null) {
            java.nio.file.Path p = java.nio.file.Path.of(sp.getPackagePath());
            if (!p.isAbsolute()) {
                // 相对路径时相对于应用目录
                p = java.nio.file.Paths.get(".").resolve(sp.getPackagePath()).normalize();
            }
            if (java.nio.file.Files.exists(p)) return p;
        }
        // 回退到分桶目录的通用命名
        String[] names = new String[]{"artifact.zip", "artifact.tgz", "artifact.tar.gz", "artifact.tar", "artifact.gz"};
        java.nio.file.Path bucket = storage.bucketize(sp.getSha256());
        for (String n : names) {
            java.nio.file.Path p = bucket.resolve(n);
            if (java.nio.file.Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    // 短链接形式：/d/{sha256} - 需要授权
    @GetMapping("/d/{sha256}")
    public org.springframework.http.ResponseEntity<?> shortDownload(@PathVariable String sha256, 
                                                                   @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                                   @RequestParam(value = "license_payload", required = false) String licensePayload,
                                                                   @RequestParam(value = "license_sig", required = false) String licenseSig,
                                                                   @RequestParam(value = "hwid", required = false) String hwid,
                                                                   HttpServletRequest request) throws Exception {
        var sp = repo.findBySha256(sha256);
        if (sp == null) {
            return org.springframework.http.ResponseEntity.status(404).body(Map.of("ok", false, "error", "源码文件不存在"));
        }

        Map<String, Object> accessResult = validateDownloadAccess(sp, licensePayload, licenseSig, hwid, request);
        if (!(Boolean) accessResult.get("ok")) {
            return org.springframework.http.ResponseEntity.status((Integer) accessResult.getOrDefault("code", 403)).body(accessResult);
        }

        return serveSourceFile(sp, accessResult, request, "short_download");
    }

    @GetMapping("/download")
    public org.springframework.http.ResponseEntity<?> downloadByCode(@RequestParam("code") String codeName,
                                                                     @RequestParam(value = "version", required = false) String version,
                                                                     @RequestParam(value = "license_payload", required = false) String licensePayload,
                                                                     @RequestParam(value = "license_sig", required = false) String licenseSig,
                                                                     @RequestParam(value = "hwid", required = false) String hwid,
                                                                     HttpServletRequest request) throws Exception {
        // 查找源码包
        SourcePackage sp = (version == null || version.isBlank()) ? repo.findLatestByCodeName(codeName) : repo.findByCodeAndVersion(codeName, version);
        if (sp == null) return org.springframework.http.ResponseEntity.status(404).body(Map.of("error","not found"));
        
        Map<String, Object> accessResult = validateDownloadAccess(sp, licensePayload, licenseSig, hwid, request);
        if (!(Boolean) accessResult.get("ok")) {
            return org.springframework.http.ResponseEntity.status((Integer) accessResult.getOrDefault("code", 403)).body(accessResult);
        }

        // 隐藏源码不能跳转到外部 artifactUrl，必须走本服务授权链路。
        if (!sp.isHiddenEnabled() && sp.getArtifactUrl() != null && !sp.getArtifactUrl().isBlank()) {
            Object token = accessResult.get("download_token");
            if (token instanceof String downloadToken && StringUtils.hasText(downloadToken)) {
                downloadReceiptService.submitReceipt(downloadToken, true,
                        sp.getFileSize() != null ? sp.getFileSize() : 0L,
                        sp.getSha256(), IpUtils.getClientIpAddress(request), request.getHeader("User-Agent"), "code_s3_redirect");
            }
            return org.springframework.http.ResponseEntity.status(302).header("Location", sp.getArtifactUrl()).build();
        }
        return serveSourceFile(sp, accessResult, request, "code_download");
    }

    @GetMapping("/sources/by-sha/{sha256}/thumbnail")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> thumbnailBySha(@PathVariable String sha256,
                                                                                                      HttpServletRequest request) throws Exception {
        var source = repo.findBySha256(sha256);
        if (source == null || !canReadSourceImage(source, request)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> dbPathResponse = serveImagePath(source.getThumbnailPath());
        if (dbPathResponse != null) {
            return dbPathResponse;
        }
        java.nio.file.Path bucket = storage.bucketize(sha256);
        String[] names = new String[]{"thumbnail.png", "thumbnail.jpg", "thumbnail.jpeg", "thumbnail.webp"};
        for (String n : names) {
            org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> response = serveImagePath(bucket.resolve(n).toString());
            if (response != null) {
                return response;
            }
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    @GetMapping("/sources/by-sha/{sha256}/logo")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> logoBySha(@PathVariable String sha256,
                                                                                                  HttpServletRequest request) throws Exception {
        var source = repo.findBySha256(sha256);
        if (source == null || !canReadSourceImage(source, request)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> dbPathResponse = serveImagePath(source.getLogoPath());
        if (dbPathResponse != null) {
            return dbPathResponse;
        }
        java.nio.file.Path bucket = storage.bucketize(sha256);
        String[] names = new String[]{"logo.png", "logo.jpg", "logo.jpeg", "logo.webp"};
        for (String n : names) {
            org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> response = serveImagePath(bucket.resolve(n).toString());
            if (response != null) {
                return response;
            }
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    private org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> serveImagePath(String imagePath) {
        if (!StringUtils.hasText(imagePath)) {
            return null;
        }
        java.nio.file.Path path = java.nio.file.Path.of(imagePath);
        if (!java.nio.file.Files.exists(path) || !java.nio.file.Files.isRegularFile(path)) {
            return null;
        }
        String fileName = path.getFileName().toString().toLowerCase();
        org.springframework.http.MediaType mediaType = fileName.endsWith(".png") ? org.springframework.http.MediaType.IMAGE_PNG :
                (fileName.endsWith(".webp") ? org.springframework.http.MediaType.valueOf("image/webp") : org.springframework.http.MediaType.IMAGE_JPEG);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(mediaType)
                .body(new org.springframework.core.io.PathResource(path));
    }

    private boolean canReadSourceImage(SourcePackage source, HttpServletRequest request) {
        if (source == null || !source.isHiddenEnabled()) {
            return true;
        }
        if (sourceAccessService.isAdminRequest(request)) {
            return true;
        }
        String installCode = sourceAccessService.extractInstallCode(request, null);
        Map<String, Object> accessResult = sourceAccessService.validateInstallCode(source, installCode);
        return Boolean.TRUE.equals(accessResult.get("ok"));
    }

    @GetMapping("/sources")
    public org.springframework.http.ResponseEntity<?> list(@RequestParam(value = "q", required = false) String q,
                                                          @RequestParam(value = "sortBy", defaultValue = "update_time") String sortBy,
                                                          @RequestParam(value = "sortOrder", defaultValue = "desc") String sortOrder,
                                                          @RequestParam(value = "includeHidden", defaultValue = "false") boolean includeHidden,
                                                          HttpServletRequest request) {
        if (includeHidden && !sourceAccessService.isAdminRequest(request)) {
            return org.springframework.http.ResponseEntity.status(401).body(Map.of("success", false, "error", "未登录"));
        }
        List<SourcePackage> data = includeHidden ? repo.findAllIncludingHidden(q, sortBy, sortOrder) : repo.findAll(q, sortBy, sortOrder);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true); resp.put("data", data); return org.springframework.http.ResponseEntity.ok(resp);
    }

    @GetMapping("/sources/hidden/resolve")
    public org.springframework.http.ResponseEntity<?> resolveHiddenSource(@RequestParam(value = "installCode", required = false) String installCode,
                                                                         @RequestParam(value = "install_code", required = false) String installCodeSnake) {
        String code = StringUtils.hasText(installCode) ? installCode.trim() : installCodeSnake;
        if (!StringUtils.hasText(code)) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "缺少下载码"));
        }

        SourcePackage sp = repo.findHiddenByInstallCode(code.trim());
        if (sp == null) {
            return org.springframework.http.ResponseEntity.status(404)
                    .body(Map.of("success", false, "error", "下载码无效或源码不存在"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", sp.getId());
        data.put("name", sp.getName());
        data.put("codeName", sp.getCodeName());
        data.put("version", sp.getVersion());
        data.put("description", sp.getDescription());
        data.put("country", sp.getCountry());
        data.put("website", sp.getWebsite());
        data.put("sha256", sp.getSha256());
        data.put("fileSize", sp.getFileSize());
        data.put("uploadTime", sp.getUploadTime());
        data.put("updateTime", sp.getUpdateTime());

        return org.springframework.http.ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @GetMapping("/sources/{id}")
    public org.springframework.http.ResponseEntity<?> get(@PathVariable String id, HttpServletRequest request) {
        SourcePackage sp = repo.findById(id);
        Map<String, Object> resp = new HashMap<>();
        if (sp == null || (sp.isHiddenEnabled() && !sourceAccessService.isAdminRequest(request))) {
            resp.put("error", "not found");
            return org.springframework.http.ResponseEntity.status(404).body(resp);
        }
        resp.put("success", true); resp.put("data", sp); return org.springframework.http.ResponseEntity.ok(resp);
    }

    @PostMapping(value = "/sources/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("package") MultipartFile file,
                                      @RequestParam String name,
                                      @RequestParam String codeName,
                                      @RequestParam String version,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) String country,
                                      @RequestParam(required = false) String website,
                                      @RequestParam(value = "isHidden", required = false) String isHiddenParam,
                                      @RequestParam(value = "installCode", required = false) String installCode,
                                      @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
                                      @RequestParam(value = "logo", required = false) MultipartFile logo,
                                      @RequestParam(value = "preview", required = false) MultipartFile preview) throws Exception {
        try {
        var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
        // 去重：如已存在相同sha256的包，直接返回已有记录信息
        var existing = repo.findBySha256(info.sha256());
        if (existing != null) {
            boolean visibilityProvided = isHiddenParam != null || StringUtils.hasText(installCode);
            boolean hidden = visibilityProvided ? parseBooleanParam(isHiddenParam) : existing.isHiddenEnabled();
            String finalInstallCode = StringUtils.hasText(installCode) ? installCode.trim() : existing.getInstallCode();
            if (hidden && !StringUtils.hasText(finalInstallCode)) {
                return Map.of("success", false, "error", "隐藏源码必须设置安装码");
            }
            if (visibilityProvided) {
                repo.updateVisibilityForCodeName(existing.getCodeName(), hidden, finalInstallCode);
                if (hidden) {
                    removeArtifactObjectsForCodeName(existing.getCodeName());
                }
                existing.setIsHidden(hidden ? 1 : 0);
                existing.setInstallCode(hidden ? finalInstallCode : null);
            }
            Map<String,Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("dedup", true);
            resp.put("message", "该文件已存在，直接复用");
            resp.put("id", existing.getId());
            resp.put("sha256", existing.getSha256());
            if (!existing.isHiddenEnabled() && existing.getArtifactUrl() != null) resp.put("artifactUrl", existing.getArtifactUrl());
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
        var latestSameCode = repo.findLatestByCodeName(codeName);
        boolean visibilityProvided = isHiddenParam != null || StringUtils.hasText(installCode);
        boolean hidden = visibilityProvided ? parseBooleanParam(isHiddenParam) : latestSameCode != null && latestSameCode.isHiddenEnabled();
        String finalInstallCode = StringUtils.hasText(installCode) ? installCode.trim() : (latestSameCode != null ? latestSameCode.getInstallCode() : null);
        if (hidden && !StringUtils.hasText(finalInstallCode)) {
            return Map.of("success", false, "error", "隐藏源码必须设置安装码");
        }
        sp.setIsHidden(hidden ? 1 : 0);
        sp.setInstallCode(hidden ? finalInstallCode : null);
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
            if (!hidden) {
            try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                String key = sp.getSha256() + "/artifact" + sp.getPackageExt();
                String url = s3.putObject(key, in, sp.getFileSize(), "application/octet-stream");
                sp.setArtifactUrl(url);
            }
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
        if (visibilityProvided) {
            repo.updateVisibilityForCodeName(codeName, hidden, finalInstallCode);
            if (hidden) {
                removeArtifactObjectsForCodeName(codeName);
            }
        }
        Map<String,Object> resp = new HashMap<>();
        resp.put("success", true); resp.put("id", sp.getId()); resp.put("sha256", sp.getSha256());
        if (!sp.isHiddenEnabled() && sp.getArtifactUrl() != null) resp.put("artifactUrl", sp.getArtifactUrl());
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
        var latestSameCode = repo.findLatestByCodeName(codeName);
        boolean visibilityProvided = body.containsKey("isHidden") || body.containsKey("installCode") || body.containsKey("install_code");
        boolean hidden = parseBooleanParam(body.get("isHidden"));
        String installCode = body.get("installCode");
        if (!StringUtils.hasText(installCode)) {
            installCode = body.get("install_code");
        }
        if (!StringUtils.hasText(body.get("isHidden")) && latestSameCode != null) {
            hidden = latestSameCode.isHiddenEnabled();
            installCode = latestSameCode.getInstallCode();
        }
        if (hidden && !StringUtils.hasText(installCode)) {
            return Map.of("success", false, "error", "隐藏源码必须设置安装码");
        }
        sp.setIsHidden(hidden ? 1 : 0);
        sp.setInstallCode(hidden ? installCode.trim() : null);
        repo.insert(sp);
        if (visibilityProvided) {
            repo.updateVisibilityForCodeName(codeName, hidden, hidden ? installCode.trim() : null);
            if (hidden) {
                removeArtifactObjectsForCodeName(codeName);
            }
        }
        return Map.of("success", true, "id", sp.getId(), "sha256", sp.getSha256());
    }

    @DeleteMapping("/sources/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                      @RequestHeader(value = "X-Admin-Confirm", required = false) String confirm) {
        if (!"true".equalsIgnoreCase(confirm)) {
            return Map.of("success", false, "error", "请先确认危险操作");
        }
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
        if (repo.existsByCodeNameAndVersion(sp.getCodeName(), version, null)) {
            return Map.of("error", "该代码名与版本已存在");
        }
        SourcePackage latestForCode = repo.findLatestByCodeName(sp.getCodeName());
        if (latestForCode != null && VersionUtils.compare(version, latestForCode.getVersion()) <= 0) {
            return Map.of("error", "新版本必须大于当前最新版本 " + latestForCode.getVersion());
        }
        var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
        var bucketDir = storage.bucketize(info.sha256());
        String artifactUrl = null;
        if (s3.isEnabled() && !sp.isHiddenEnabled()) {
            try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                String key = info.sha256() + "/artifact" + info.ext();
                artifactUrl = s3.putObject(key, in, info.size(), "application/octet-stream");
            }
        }
        SourcePackage newVersion = new SourcePackage();
        newVersion.setId(UUID.randomUUID().toString());
        newVersion.setName(sp.getName());
        newVersion.setCodeName(sp.getCodeName());
        newVersion.setVersion(version);
        newVersion.setDescription(sp.getDescription());
        newVersion.setCountry(sp.getCountry());
        newVersion.setWebsite(sp.getWebsite());
        newVersion.setSha256(info.sha256());
        newVersion.setBucketRelPath(bucketDir.toString());
        newVersion.setPackageExt(info.ext());
        newVersion.setPackagePath(info.finalPath().toString());
        newVersion.setArtifactUrl(artifactUrl);
        newVersion.setThumbnailPath(sp.getThumbnailPath());
        newVersion.setThumbnailUrl(sp.getThumbnailUrl());
        newVersion.setLogoPath(sp.getLogoPath());
        newVersion.setLogoUrl(sp.getLogoUrl());
        newVersion.setPreviewPath(sp.getPreviewPath());
        newVersion.setPreviewUrl(sp.getPreviewUrl());
        newVersion.setFileSize(info.size());
        newVersion.setIsHidden(sp.getIsHidden());
        newVersion.setInstallCode(sp.isHiddenEnabled() ? sp.getInstallCode() : null);
        newVersion.setStatus("uploaded");
        repo.insert(newVersion);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("id", newVersion.getId());
        result.put("previousId", id);
        result.put("version", version);
        result.put("sha256", info.sha256());
        if (!sp.isHiddenEnabled() && artifactUrl != null) {
            result.put("artifactUrl", artifactUrl);
        }
        return result;
    }

    /**
     * 源码更新。上传新源码包时创建新版本记录，保留旧版本和旧文件；只改元信息时原地更新。
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
                                                   @RequestParam(value = "website", required = false) String website,
                                                   @RequestParam(value = "isHidden", required = false) String isHiddenParam,
                                                   @RequestParam(value = "installCode", required = false) String installCode) throws Exception {
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
            boolean hasPackageFile = file != null && !file.isEmpty();
            
            if (!hasPackageFile && (name != null || description != null || country != null || website != null)) {
                repo.updateMeta(id, finalName, sp.getCodeName(), finalDesc, finalCountry, finalWebsite);
                result.put("metaUpdated", true);
            }

            if (isHiddenParam != null || installCode != null) {
                boolean hidden = isHiddenParam != null ? parseBooleanParam(isHiddenParam) : sp.isHiddenEnabled();
                String finalInstallCode = StringUtils.hasText(installCode) ? installCode.trim() : sp.getInstallCode();
                if (hidden && !StringUtils.hasText(finalInstallCode)) {
                    return Map.of("success", false, "error", "隐藏源码必须设置安装码");
                }
                repo.updateVisibilityForCodeName(sp.getCodeName(), hidden, finalInstallCode);
                if (hidden) {
                    removeArtifactObjectsForCodeName(sp.getCodeName());
                }
                sp.setIsHidden(hidden ? 1 : 0);
                sp.setInstallCode(hidden ? finalInstallCode : null);
                result.put("visibilityUpdated", true);
            }
            
            // 更新版本号（如果提供了新版本）
            if (!hasPackageFile && version != null && !version.trim().isEmpty() && !version.equals(sp.getVersion())) {
                repo.updateVersion(id, version);
                result.put("versionUpdated", true);
                result.put("oldVersion", sp.getVersion());
            }
            
            // 更新缩略图
            if (!hasPackageFile && thumbnail != null && !thumbnail.isEmpty()) {
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
            if (!hasPackageFile && logo != null && !logo.isEmpty()) {
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
            
            // 如果上传了新源码包，创建新版本记录并保留旧版本文件。
            if (hasPackageFile) {
                // 保存新文件
                var info = storage.saveAndHash(file.getInputStream(), file.getOriginalFilename());
                
                // 检查SHA256是否与其他记录重复（排除自身）
                var existingBySha = repo.findBySha256(info.sha256());
                if (existingBySha != null && !existingBySha.getId().equals(id)) {
                    return Map.of("success", false, "error", "该文件已存在于其他源码包", 
                                 "existingId", existingBySha.getId(), 
                                 "existingVersion", existingBySha.getVersion());
                }
                
                SourcePackage latestForCode = repo.findLatestByCodeName(sp.getCodeName());
                SourcePackage versionBaseline = latestForCode != null ? latestForCode : sp;
                boolean autoVersion = version == null || version.trim().isEmpty()
                        || VersionUtils.compare(finalVersion, versionBaseline.getVersion()) <= 0;
                if (autoVersion) {
                    finalVersion = VersionUtils.increment(versionBaseline.getVersion());
                    while (repo.existsByCodeNameAndVersion(sp.getCodeName(), finalVersion, null)) {
                        finalVersion = VersionUtils.increment(finalVersion);
                    }
                    result.put("versionAutoIncremented", true);
                } else if (repo.existsByCodeNameAndVersion(sp.getCodeName(), finalVersion, null)) {
                    return Map.of("success", false, "error", "版本 " + finalVersion + " 已存在");
                }
                result.put("oldVersion", versionBaseline.getVersion());
                
                String oldSha256 = sp.getSha256();
                var bucketDir = storage.bucketize(info.sha256());
                
                String newThumbPath = sp.getThumbnailPath();
                String newThumbUrl = sp.getThumbnailUrl();
                String newLogoPath = sp.getLogoPath();
                String newLogoUrl = sp.getLogoUrl();
                
                if (thumbnail != null && !thumbnail.isEmpty()) {
                    var t = storage.saveThumbnail(thumbnail.getInputStream(), thumbnail.getOriginalFilename(), bucketDir);
                    newThumbPath = t.toString();
                    newThumbUrl = null;
                    if (s3.isEnabled() && !sp.isHiddenEnabled()) {
                        try (var in = java.nio.file.Files.newInputStream(t)) {
                            String fname = t.getFileName().toString();
                            String ct = fname.endsWith("png") ? "image/png" : (fname.endsWith("webp") ? "image/webp" : "image/jpeg");
                            newThumbUrl = s3.putObject(info.sha256() + "/" + fname, in, java.nio.file.Files.size(t), ct);
                        }
                    }
                }
                
                if (logo != null && !logo.isEmpty()) {
                    var p = storage.saveImage(logo.getInputStream(), logo.getOriginalFilename(), bucketDir, "logo");
                    newLogoPath = p.toString();
                    newLogoUrl = null;
                    if (s3.isEnabled() && !sp.isHiddenEnabled()) {
                        try (var in = java.nio.file.Files.newInputStream(p)) {
                            String fname = p.getFileName().toString();
                            String ct = fname.endsWith("png") ? "image/png" : (fname.endsWith("webp") ? "image/webp" : "image/jpeg");
                            newLogoUrl = s3.putObject(info.sha256() + "/" + fname, in, java.nio.file.Files.size(p), ct);
                        }
                    }
                }

                if (!java.nio.file.Files.exists(info.finalPath())) {
                    return Map.of("success", false, "error", "源码包保存失败，文件未落盘");
                }
                
                // 上传到S3
                String artifactUrl = null;
                if (s3.isEnabled() && !sp.isHiddenEnabled()) {
                    try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                        String key = info.sha256() + "/artifact" + info.ext();
                        artifactUrl = s3.putObject(key, in, info.size(), "application/octet-stream");
                    }
                }
                
                SourcePackage newVersion = new SourcePackage();
                newVersion.setId(UUID.randomUUID().toString());
                newVersion.setName(finalName);
                newVersion.setCodeName(sp.getCodeName());
                newVersion.setVersion(finalVersion);
                newVersion.setDescription(finalDesc);
                newVersion.setCountry(finalCountry);
                newVersion.setWebsite(finalWebsite);
                newVersion.setSha256(info.sha256());
                newVersion.setBucketRelPath(bucketDir.toString());
                newVersion.setPackageExt(info.ext());
                newVersion.setPackagePath(info.finalPath().toString());
                newVersion.setArtifactUrl(artifactUrl);
                newVersion.setThumbnailPath(newThumbPath);
                newVersion.setThumbnailUrl(newThumbUrl);
                newVersion.setLogoPath(newLogoPath);
                newVersion.setLogoUrl(newLogoUrl);
                newVersion.setPreviewPath(sp.getPreviewPath());
                newVersion.setPreviewUrl(sp.getPreviewUrl());
                newVersion.setFileSize(info.size());
                newVersion.setIsHidden(sp.getIsHidden());
                newVersion.setInstallCode(sp.isHiddenEnabled() ? sp.getInstallCode() : null);
                newVersion.setStatus("uploaded");
                repo.insert(newVersion);
                
                result.put("packageUpdated", true);
                result.put("newId", newVersion.getId());
                result.put("oldSha256", oldSha256);
                result.put("newSha256", info.sha256());
                result.put("fileSize", info.size());
                if (!sp.isHiddenEnabled() && artifactUrl != null) result.put("artifactUrl", artifactUrl);
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
            if (VersionUtils.compare(version, latestSp.getVersion()) <= 0) {
                return Map.of("success", false, "error", "新版本必须大于当前最新版本 " + latestSp.getVersion());
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
            sp.setIsHidden(latestSp.getIsHidden());
            sp.setInstallCode(latestSp.isHiddenEnabled() ? latestSp.getInstallCode() : null);
            
            // 上传到S3
            if (s3.isEnabled()) {
                if (!sp.isHiddenEnabled()) {
                try (var in = java.nio.file.Files.newInputStream(info.finalPath())) {
                    String key = sp.getSha256() + "/artifact" + sp.getPackageExt();
                    String url = s3.putObject(key, in, sp.getFileSize(), "application/octet-stream");
                    sp.setArtifactUrl(url);
                }
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
            if (!sp.isHiddenEnabled() && sp.getArtifactUrl() != null) result.put("artifactUrl", sp.getArtifactUrl());
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
    public org.springframework.http.ResponseEntity<?> getVersionsByCodeName(@PathVariable String codeName,
                                                                           HttpServletRequest request) {
        List<SourcePackage> versions = repo.findAllByCodeName(codeName);
        if (versions == null || versions.isEmpty()) {
            return org.springframework.http.ResponseEntity.status(404).body(Map.of("success", false, "error", "未找到代码名为 " + codeName + " 的源码包"));
        }
        if (versions.get(0).isHiddenEnabled() && !sourceAccessService.isAdminRequest(request)) {
            return org.springframework.http.ResponseEntity.status(404).body(Map.of("success", false, "error", "未找到代码名为 " + codeName + " 的源码包"));
        }
        return org.springframework.http.ResponseEntity.ok(Map.of("success", true, "data", versions, "count", versions.size()));
    }
    
    /**
     * 检查源码是否有新版本可用
     * 根据 codeName 和当前版本号检查是否有更新
     */
    @GetMapping("/sources/check-update")
    public org.springframework.http.ResponseEntity<?> checkUpdate(@RequestParam String codeName,
                                           @RequestParam String currentVersion,
                                           @RequestParam(value = "license_payload", required = false) String licensePayload,
                                           @RequestParam(value = "license_sig", required = false) String licenseSig,
                                           @RequestParam(value = "hwid", required = false) String hwid,
                                           HttpServletRequest request) {
        return checkUpdateInternal(codeName, currentVersion, licensePayload, licenseSig, hwid, request);
    }

    @PostMapping("/sources/check-update")
    public org.springframework.http.ResponseEntity<?> checkUpdatePost(@RequestBody Map<String, Object> body,
                                                                     HttpServletRequest request) {
        String codeName = body.get("codeName") instanceof String v ? v : null;
        String currentVersion = body.get("currentVersion") instanceof String v ? v : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> license = body.get("license") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        String licensePayload = license != null && license.get("payload") instanceof String v ? v : null;
        String licenseSig = license != null && license.get("sig") instanceof String v ? v : null;
        String hwid = body.get("hwid") instanceof String v ? v : null;
        return checkUpdateInternal(codeName, currentVersion, licensePayload, licenseSig, hwid, request);
    }

    private org.springframework.http.ResponseEntity<?> checkUpdateInternal(String codeName,
                                                                          String currentVersion,
                                                                          String licensePayload,
                                                                          String licenseSig,
                                                                          String hwid,
                                                                          HttpServletRequest request) {
        try {
            if (!StringUtils.hasText(codeName) || !StringUtils.hasText(currentVersion)) {
                return org.springframework.http.ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "hasUpdate", false,
                    "error", "缺少必要参数：codeName 和 currentVersion"
                ));
            }
            SourcePackage latest = repo.findLatestByCodeName(codeName);
            
            if (latest == null) {
                return org.springframework.http.ResponseEntity.status(404).body(Map.of(
                    "success", false, 
                    "hasUpdate", false,
                    "error", "未找到代码名为 " + codeName + " 的源码包"
                ));
            }

            if (latest.isHiddenEnabled()) {
                String finalPayload = resolveAuthValue(request, licensePayload, "X-License-Payload", "license_payload");
                String finalSig = resolveAuthValue(request, licenseSig, "X-License-Sig", "license_sig");
                String finalHwid = resolveAuthValue(request, hwid, "X-HWID", "hwid");
                Map<String, Object> authResult = validateLicenseAndServerBinding(finalPayload, finalSig, finalHwid, IpUtils.getClientIpAddress(request));
                if (!(Boolean) authResult.get("ok")) {
                    return org.springframework.http.ResponseEntity.status((Integer) authResult.getOrDefault("code", 403)).body(new HashMap<>(authResult));
                }
            }
            
            boolean hasUpdate = VersionUtils.compare(latest.getVersion(), currentVersion) > 0;
            
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
                // 添加 logo 和 thumbnail URL
                result.put("latestLogoUrl", latest.getLogoUrl());
                result.put("latestThumbnailUrl", latest.getThumbnailUrl());
            }
            
            return org.springframework.http.ResponseEntity.ok(result);
            
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500).body(Map.of("success", false, "error", "检查更新失败: " + e.getMessage()));
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
            if (!StringUtils.hasText(licensePayload) || !StringUtils.hasText(licenseSig) || !StringUtils.hasText(hwid)) {
                return Map.of(
                    "ok", false,
                    "error", "需要有效授权参数：license_payload, license_sig, hwid",
                    "code", 401
                );
            }
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

    private Map<String, Object> validateDownloadAccess(SourcePackage sourcePackage,
                                                       String licensePayload,
                                                       String licenseSig,
                                                       String hwid,
                                                       HttpServletRequest request) {
        String finalPayload = resolveAuthValue(request, licensePayload, "X-License-Payload", "license_payload");
        String finalSig = resolveAuthValue(request, licenseSig, "X-License-Sig", "license_sig");
        String finalHwid = resolveAuthValue(request, hwid, "X-HWID", "hwid");

        return authorizationService.downloadPreauth(finalPayload, finalSig, finalHwid, sourcePackage.getSha256(),
                Map.of("download_method", "direct_source_download"), IpUtils.getClientIpAddress(request),
                sourceAccessService.extractInstallCode(request, null));
    }

    private String resolveAuthValue(HttpServletRequest request, String explicitValue, String headerName, String parameterName) {
        if (StringUtils.hasText(explicitValue)) {
            return explicitValue.trim();
        }
        if (request == null) {
            return null;
        }
        String header = request.getHeader(headerName);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String parameter = request.getParameter(parameterName);
        return StringUtils.hasText(parameter) ? parameter.trim() : null;
    }

    private void removeArtifactObjectsForCodeName(String codeName) {
        if (!s3.isEnabled() || !StringUtils.hasText(codeName)) {
            return;
        }
        for (SourcePackage version : repo.findAllByCodeName(codeName)) {
            if (!StringUtils.hasText(version.getSha256()) || !StringUtils.hasText(version.getPackageExt())) {
                continue;
            }
            try {
                s3.deleteObject(version.getSha256() + "/artifact" + version.getPackageExt());
            } catch (Exception ignored) {
            }
        }
    }

    private boolean parseBooleanParam(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("on") || normalized.equals("yes");
    }

}
