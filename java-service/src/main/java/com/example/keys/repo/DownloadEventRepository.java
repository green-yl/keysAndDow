package com.example.keys.repo;

import com.example.keys.model.DownloadEvent;
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
public class DownloadEventRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public DownloadEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<DownloadEvent> DOWNLOAD_EVENT_ROW_MAPPER = new RowMapper<DownloadEvent>() {
        @Override
        public DownloadEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            DownloadEvent event = new DownloadEvent();
            event.setId(rs.getLong("id"));
            event.setLicenseId(rs.getLong("license_id"));
            event.setToken(rs.getString("token"));
            event.setFileId(rs.getString("file_id"));
            event.setOk(rs.getBoolean("ok"));
            event.setDeducted(rs.getBoolean("deducted"));
            event.setDelta(rs.getInt("delta"));
            event.setSize(rs.getLong("size"));
            event.setSha256(rs.getString("sha256"));
            event.setIp(rs.getString("ip"));
            event.setUa(rs.getString("ua"));
            
            // 处理时间戳解析问题
            event.setCreatedAt(parseTimestamp(rs, "created_at"));
            
            return event;
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
    
    public Long insert(DownloadEvent downloadEvent) {
        String sql = "INSERT INTO download_events (license_id, token, file_id, ok, deducted, delta, " +
                     "size, sha256, ip, ua, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, downloadEvent.getLicenseId());
            ps.setString(2, downloadEvent.getToken());
            ps.setString(3, downloadEvent.getFileId());
            ps.setBoolean(4, downloadEvent.getOk());
            ps.setBoolean(5, downloadEvent.getDeducted());
            ps.setInt(6, downloadEvent.getDelta());
            if (downloadEvent.getSize() != null) {
                ps.setLong(7, downloadEvent.getSize());
            } else {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            ps.setString(8, downloadEvent.getSha256());
            ps.setString(9, downloadEvent.getIp());
            ps.setString(10, downloadEvent.getUa());
            ps.setObject(11, LocalDateTime.now());
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public Optional<DownloadEvent> findByToken(String token) {
        String sql = "SELECT * FROM download_events WHERE token = ?";
        List<DownloadEvent> results = jdbcTemplate.query(sql, DOWNLOAD_EVENT_ROW_MAPPER, token);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public List<DownloadEvent> findByLicenseId(Long licenseId) {
        String sql = "SELECT * FROM download_events WHERE license_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, DOWNLOAD_EVENT_ROW_MAPPER, licenseId);
    }
    
    public List<DownloadEvent> findByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        String sql = "SELECT * FROM download_events WHERE created_at BETWEEN ? AND ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, DOWNLOAD_EVENT_ROW_MAPPER, startDate, endDate);
    }
    
    public List<DownloadEvent> findSuccessfulDownloads() {
        String sql = "SELECT * FROM download_events WHERE ok = TRUE AND deducted = TRUE ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, DOWNLOAD_EVENT_ROW_MAPPER);
    }
    
    public Long countSuccessfulDownloadsForLicense(Long licenseId) {
        String sql = "SELECT COUNT(*) FROM download_events WHERE license_id = ? AND ok = TRUE AND deducted = TRUE";
        return jdbcTemplate.queryForObject(sql, Long.class, licenseId);
    }
    
    public Long countTotalDownloadsToday() {
        String sql = "SELECT COUNT(*) FROM download_events WHERE DATE(created_at) = DATE('now') AND ok = TRUE AND deducted = TRUE";
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
