package com.example.keys.repo;

import com.example.keys.model.SourcePackage;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SourcePackageRepository {
    private final JdbcTemplate jdbc;

    public SourcePackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SourcePackage> findAll(String q) {
        return findAll(q, "update_time", "desc");
    }
    
    /**
     * 支持排序的源码列表查询
     * @param q 搜索关键词
     * @param sortBy 排序字段: download_count, update_time, upload_time
     * @param sortOrder 排序方向: asc, desc
     */
    public List<SourcePackage> findAll(String q, String sortBy, String sortOrder) {
        // 验证排序字段，防止SQL注入
        String orderColumn = switch (sortBy) {
            case "download_count" -> "download_count";
            case "upload_time" -> "upload_time";
            case "file_size" -> "file_size";
            default -> "update_time";
        };
        String order = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
        
        if (q == null || q.isBlank()) {
            return jdbc.query("SELECT * FROM source_packages WHERE is_active = 1 ORDER BY " + orderColumn + " " + order,
                    new BeanPropertyRowMapper<>(SourcePackage.class));
        }
        String like = "%" + q + "%";
        return jdbc.query("SELECT * FROM source_packages WHERE is_active=1 AND (name LIKE ? OR code_name LIKE ? OR version LIKE ? OR sha256 LIKE ?) ORDER BY " + orderColumn + " " + order,
                new BeanPropertyRowMapper<>(SourcePackage.class), like, like, like, like);
    }

    public SourcePackage findById(String id) {
        List<SourcePackage> list = jdbc.query("SELECT * FROM source_packages WHERE id=? AND is_active=1",
                new BeanPropertyRowMapper<>(SourcePackage.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public int insert(SourcePackage sp) {
        // 让 upload_time 与 is_active 使用表默认值，避免列数与值数不匹配
        return jdbc.update("INSERT INTO source_packages (id,name,code_name,version,description,country,website,sha256,bucket_rel_path,package_ext,package_path,artifact_url,thumbnail_path,thumbnail_url,logo_path,logo_url,preview_path,preview_url,file_size,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sp.getId(), sp.getName(), sp.getCodeName(), sp.getVersion(), sp.getDescription(), sp.getCountry(), sp.getWebsite(), sp.getSha256(), sp.getBucketRelPath(), sp.getPackageExt(), sp.getPackagePath(), sp.getArtifactUrl(), sp.getThumbnailPath(), sp.getThumbnailUrl(), sp.getLogoPath(), sp.getLogoUrl(), sp.getPreviewPath(), sp.getPreviewUrl(), sp.getFileSize(), sp.getStatus());
    }

    public int markDeleted(String id) {
        return jdbc.update("UPDATE source_packages SET is_active=0, update_time=datetime('now') WHERE id=?", id);
    }
    
    /**
     * 物理删除源码包记录
     */
    public int hardDelete(String id) {
        return jdbc.update("DELETE FROM source_packages WHERE id=?", id);
    }

    public int updateStatus(String id, String status) {
        return jdbc.update("UPDATE source_packages SET status=?, update_time=datetime('now') WHERE id=?", status, id);
    }

    public int updateExtracted(String id, String extractedPath) {
        return jdbc.update("UPDATE source_packages SET extracted_path=?, status='extracted', update_time=datetime('now') WHERE id=?", extractedPath, id);
    }

    public int updateMeta(String id, String name, String codeName, String description, String country, String website) {
        return jdbc.update("UPDATE source_packages SET name=?, code_name=?, description=?, country=?, website=?, update_time=datetime('now') WHERE id=?",
                name, codeName, description, country, website, id);
    }
    
    public int updateVersion(String id, String version) {
        return jdbc.update("UPDATE source_packages SET version=?, update_time=datetime('now') WHERE id=?", version, id);
    }

    public int updateThumbnail(String id, String thumbnailPath, String thumbnailUrl) {
        return jdbc.update("UPDATE source_packages SET thumbnail_path=?, thumbnail_url=?, update_time=datetime('now') WHERE id=?",
                thumbnailPath, thumbnailUrl, id);
    }

    public int updateLogo(String id, String path, String url) {
        return jdbc.update("UPDATE source_packages SET logo_path=?, logo_url=?, update_time=datetime('now') WHERE id=?",
                path, url, id);
    }
    
    /**
     * 增加下载计数
     */
    public int incrementDownloadCount(String sha256) {
        return jdbc.update("UPDATE source_packages SET download_count = COALESCE(download_count, 0) + 1 WHERE sha256=? AND is_active=1", sha256);
    }
    
    /**
     * 根据ID增加下载计数
     */
    public int incrementDownloadCountById(String id) {
        return jdbc.update("UPDATE source_packages SET download_count = COALESCE(download_count, 0) + 1 WHERE id=? AND is_active=1", id);
    }

    public int updatePreview(String id, String path, String url) {
        return jdbc.update("UPDATE source_packages SET preview_path=?, preview_url=?, update_time=datetime('now') WHERE id=?",
                path, url, id);
    }

    public int replacePackage(String id, String version, String sha256, String bucketRelPath, String packageExt,
                              String packagePath, String artifactUrl, Long fileSize) {
        return jdbc.update("UPDATE source_packages SET version=?, sha256=?, bucket_rel_path=?, package_ext=?, package_path=?, artifact_url=?, file_size=?, extracted_path=NULL, status='uploaded', update_time=datetime('now') WHERE id=?",
                version, sha256, bucketRelPath, packageExt, packagePath, artifactUrl, fileSize, id);
    }

    public boolean existsByCodeNameAndVersion(String codeName, String version, String excludeId) {
        Integer cnt;
        if (excludeId == null) {
            cnt = jdbc.queryForObject("SELECT COUNT(*) FROM source_packages WHERE code_name=? AND version=? AND is_active=1",
                    Integer.class, codeName, version);
        } else {
            cnt = jdbc.queryForObject("SELECT COUNT(*) FROM source_packages WHERE code_name=? AND version=? AND is_active=1 AND id<>?",
                    Integer.class, codeName, version, excludeId);
        }
        return cnt != null && cnt > 0;
    }

    public SourcePackage findByCodeAndVersion(String codeName, String version) {
        List<SourcePackage> list = jdbc.query("SELECT * FROM source_packages WHERE code_name=? AND version=? AND is_active=1 LIMIT 1",
                new BeanPropertyRowMapper<>(SourcePackage.class), codeName, version);
        return list.isEmpty() ? null : list.get(0);
    }

    public SourcePackage findLatestByCodeName(String codeName) {
        List<SourcePackage> list = jdbc.query("SELECT * FROM source_packages WHERE code_name=? AND is_active=1 ORDER BY upload_time DESC LIMIT 1",
                new BeanPropertyRowMapper<>(SourcePackage.class), codeName);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 根据SHA256查找源码包（用于去重检查）
     * 注意：现在使用物理删除，只需要检查活跃记录
     */
    public SourcePackage findBySha256(String sha256) {
        List<SourcePackage> list = jdbc.query("SELECT * FROM source_packages WHERE sha256=? AND is_active=1 LIMIT 1",
                new BeanPropertyRowMapper<>(SourcePackage.class), sha256);
        return list.isEmpty() ? null : list.get(0);
    }
    
    /**
     * 查找指定codeName的所有版本
     */
    public List<SourcePackage> findAllByCodeName(String codeName) {
        return jdbc.query("SELECT * FROM source_packages WHERE code_name=? AND is_active=1 ORDER BY upload_time DESC",
                new BeanPropertyRowMapper<>(SourcePackage.class), codeName);
    }
}


