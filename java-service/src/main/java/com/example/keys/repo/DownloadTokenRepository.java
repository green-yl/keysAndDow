package com.example.keys.repo;

import com.example.keys.model.DownloadToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DownloadTokenRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public DownloadTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<DownloadToken> DOWNLOAD_TOKEN_ROW_MAPPER = new RowMapper<DownloadToken>() {
        @Override
        public DownloadToken mapRow(ResultSet rs, int rowNum) throws SQLException {
            DownloadToken token = new DownloadToken();
            token.setId(rs.getLong("id"));
            token.setToken(rs.getString("token"));
            token.setLicenseId(rs.getLong("license_id"));
            token.setFileId(rs.getString("file_id"));
            
            // 处理时间戳解析问题
            token.setExpireAt(parseTimestamp(rs, "expire_at"));
            token.setUsed(rs.getBoolean("used"));
            token.setCreatedAt(parseTimestamp(rs, "created_at"));
            
            // 新增字段（兼容旧数据）
            try {
                token.setIsUpdate(rs.getBoolean("is_update"));
            } catch (SQLException e) {
                token.setIsUpdate(false);
            }
            try {
                token.setFromVersion(rs.getString("from_version"));
            } catch (SQLException e) {
                token.setFromVersion(null);
            }
            
            return token;
        }
        
        private LocalDateTime parseTimestamp(ResultSet rs, String columnName) throws SQLException {
            try {
                return rs.getTimestamp(columnName).toLocalDateTime();
            } catch (Exception e) {
                String timeStr = rs.getString(columnName);
                if (timeStr != null) {
                    try {
                        return LocalDateTime.parse(timeStr.replace(" ", "T"));
                    } catch (Exception ex) {
                        return LocalDateTime.now();
                    }
                }
                return LocalDateTime.now();
            }
        }
    };
    
    public Long insert(DownloadToken downloadToken) {
        String sql = "INSERT INTO download_tokens (token, license_id, file_id, expire_at, used, is_update, from_version, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, downloadToken.getToken());
            ps.setLong(2, downloadToken.getLicenseId());
            ps.setString(3, downloadToken.getFileId());
            ps.setObject(4, downloadToken.getExpireAt());
            ps.setBoolean(5, downloadToken.getUsed());
            ps.setBoolean(6, downloadToken.getIsUpdate() != null ? downloadToken.getIsUpdate() : false);
            ps.setString(7, downloadToken.getFromVersion());
            ps.setObject(8, LocalDateTime.now());
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public Optional<DownloadToken> findByToken(String token) {
        String sql = "SELECT * FROM download_tokens WHERE token = ?";
        List<DownloadToken> results = jdbcTemplate.query(sql, DOWNLOAD_TOKEN_ROW_MAPPER, token);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public int markAsUsed(String token) {
        String sql = "UPDATE download_tokens SET used = TRUE WHERE token = ? AND used = FALSE";
        return jdbcTemplate.update(sql, token);
    }
    
    public int cleanupExpired() {
        String sql = "DELETE FROM download_tokens WHERE expire_at < ?";
        return jdbcTemplate.update(sql, LocalDateTime.now());
    }
    
    public List<DownloadToken> findByLicenseId(Long licenseId) {
        String sql = "SELECT * FROM download_tokens WHERE license_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, DOWNLOAD_TOKEN_ROW_MAPPER, licenseId);
    }
    
    public int deleteById(Long id) {
        String sql = "DELETE FROM download_tokens WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }
}
