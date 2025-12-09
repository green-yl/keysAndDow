package com.example.keys.repo;

import com.example.keys.model.License;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;

@Repository
public class LicenseRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public LicenseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<License> LICENSE_ROW_MAPPER = new RowMapper<License>() {
        @Override
        public License mapRow(ResultSet rs, int rowNum) throws SQLException {
            License license = new License();
            license.setId(rs.getLong("id"));
            license.setCode(rs.getString("code"));
            license.setSub(rs.getString("sub"));
            license.setHwid(rs.getString("hwid"));
            license.setServerIp(rs.getString("server_ip"));
            
            // Parse last_server_switch_at timestamp
            try {
                Timestamp switchTimestamp = rs.getTimestamp("last_server_switch_at");
                if (switchTimestamp != null) {
                    license.setLastServerSwitchAt(switchTimestamp.toLocalDateTime());
                }
            } catch (Exception e) {
                String switchTimeStr = rs.getString("last_server_switch_at");
                if (switchTimeStr != null && !switchTimeStr.isEmpty()) {
                    try {
                        license.setLastServerSwitchAt(LocalDateTime.parse(switchTimeStr.replace(" ", "T")));
                    } catch (Exception ex) {
                        // Ignore parsing errors for optional field
                    }
                }
            }
            
            license.setPlanId(rs.getLong("plan_id"));
            
            // 处理时间戳解析问题
            license.setValidFrom(parseTimestamp(rs, "valid_from"));
            license.setValidTo(parseTimestamp(rs, "valid_to"));
            
            license.setKid(rs.getString("kid"));
            license.setLicensePayload(rs.getString("license_payload"));
            license.setLicenseSig(rs.getString("license_sig"));
            license.setStatus(rs.getString("status"));
            license.setDownloadQuotaTotal(rs.getInt("download_quota_total"));
            license.setDownloadQuotaRemaining(rs.getInt("download_quota_remaining"));
            
            license.setCreatedAt(parseTimestamp(rs, "created_at"));
            license.setUpdatedAt(parseTimestamp(rs, "updated_at"));
            
            // Plan info if joined
            try {
                license.setPlanName(rs.getString("plan_name"));
                license.setPlanDurationHours(rs.getInt("plan_duration_hours"));
                license.setPlanAllowGrace(rs.getBoolean("plan_allow_grace"));
            } catch (SQLException e) {
                // No plan info in this query
            }
            
            return license;
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
    
    public Long insert(License license) {
        String sql = "INSERT INTO licenses (code, sub, hwid, server_ip, plan_id, valid_from, valid_to, kid, " +
                     "license_payload, license_sig, status, download_quota_total, download_quota_remaining, " +
                     "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, license.getCode());
            ps.setString(2, license.getSub());
            ps.setString(3, license.getHwid());
            ps.setString(4, license.getServerIp());
            ps.setLong(5, license.getPlanId());
            ps.setObject(6, license.getValidFrom());
            ps.setObject(7, license.getValidTo());
            ps.setString(8, license.getKid());
            ps.setString(9, license.getLicensePayload());
            ps.setString(10, license.getLicenseSig());
            ps.setString(11, license.getStatus());
            ps.setInt(12, license.getDownloadQuotaTotal());
            ps.setInt(13, license.getDownloadQuotaRemaining());
            ps.setObject(14, now);
            ps.setObject(15, now);
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public List<License> findAllWithPlan() {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id " +
                     "ORDER BY l.created_at DESC";
        return jdbcTemplate.query(sql, LICENSE_ROW_MAPPER);
    }
    
    public Optional<License> findByCodeAndHwid(String code, String hwid) {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id WHERE l.code = ? AND l.hwid = ?";
        List<License> results = jdbcTemplate.query(sql, LICENSE_ROW_MAPPER, code, hwid);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public List<License> findByCode(String code) {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id WHERE l.code = ?";
        return jdbcTemplate.query(sql, LICENSE_ROW_MAPPER, code);
    }
    
    public Optional<License> findByHwid(String hwid) {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id WHERE l.hwid = ? ORDER BY l.created_at DESC LIMIT 1";
        List<License> results = jdbcTemplate.query(sql, LICENSE_ROW_MAPPER, hwid);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public Optional<License> findById(Long id) {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id WHERE l.id = ?";
        List<License> results = jdbcTemplate.query(sql, LICENSE_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public int updateStatus(Long id, String status) {
        String sql = "UPDATE licenses SET status = ?, updated_at = ? WHERE id = ?";
        return jdbcTemplate.update(sql, status, LocalDateTime.now(), id);
    }
    
    public int addQuota(Long id, Integer extraQuota) {
        String sql = "UPDATE licenses SET download_quota_total = download_quota_total + ?, " +
                     "download_quota_remaining = download_quota_remaining + ?, updated_at = ? WHERE id = ?";
        return jdbcTemplate.update(sql, extraQuota, extraQuota, LocalDateTime.now(), id);
    }
    
    public int renewLicense(Long id, LocalDateTime newValidTo, Boolean resetQuota, Integer newQuota) {
        if (resetQuota && newQuota != null) {
            String sql = "UPDATE licenses SET valid_to = ?, download_quota_total = ?, " +
                         "download_quota_remaining = ?, updated_at = ? WHERE id = ?";
            return jdbcTemplate.update(sql, newValidTo, newQuota, newQuota, LocalDateTime.now(), id);
        } else {
            String sql = "UPDATE licenses SET valid_to = ?, updated_at = ? WHERE id = ?";
            return jdbcTemplate.update(sql, newValidTo, LocalDateTime.now(), id);
        }
    }
    
    public int rebindHwid(Long id, String newHwid) {
        String sql = "UPDATE licenses SET hwid = ?, updated_at = ? WHERE id = ?";
        return jdbcTemplate.update(sql, newHwid, LocalDateTime.now(), id);
    }
    
    public int decrementQuota(Long id) {
        String sql = "UPDATE licenses SET download_quota_remaining = download_quota_remaining - 1, " +
                     "updated_at = ? WHERE id = ? AND download_quota_remaining > 0";
        return jdbcTemplate.update(sql, LocalDateTime.now(), id);
    }
    
    public int decrementQuotaWithGrace(Long id) {
        String sql = "UPDATE licenses SET download_quota_remaining = download_quota_remaining - 1, " +
                     "updated_at = ? WHERE id = ? AND download_quota_remaining >= 0";
        return jdbcTemplate.update(sql, LocalDateTime.now(), id);
    }
    
    public int deleteById(Long id) {
        String sql = "DELETE FROM licenses WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }
    
    /**
     * 根据激活码吊销许可证
     */
    public int revokeByCode(String code, String reason) {
        String sql = "UPDATE licenses SET status = 'revoked', updated_at = ? WHERE code = ? AND status = 'ok'";
        return jdbcTemplate.update(sql, LocalDateTime.now(), code);
    }
    
    /**
     * 查找激活码对应的最新许可证
     */
    public Optional<License> findLatestByCode(String code) {
        String sql = "SELECT l.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.allow_grace as plan_allow_grace FROM licenses l " +
                     "JOIN plans p ON l.plan_id = p.id WHERE l.code = ? " +
                     "ORDER BY l.created_at DESC LIMIT 1";
        List<License> results = jdbcTemplate.query(sql, LICENSE_ROW_MAPPER, code);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public void updateServerIp(Long id, String serverIp) {
        String sql = "UPDATE licenses SET server_ip = ?, last_server_switch_at = ?, updated_at = ? WHERE id = ?";
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(sql, serverIp, now, now, id);
    }
    
    public License findByCodeAndHwidDirect(String code, String hwid) {
        String sql = "SELECT * FROM licenses WHERE code = ? AND hwid = ? AND status = 'ok'";
        try {
            return jdbcTemplate.queryForObject(sql, LICENSE_ROW_MAPPER, code, hwid);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
