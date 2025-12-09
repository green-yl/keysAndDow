package com.example.keys.repo;

import com.example.keys.model.LicenseCode;
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
public class LicenseCodeRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public LicenseCodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<LicenseCode> LICENSE_CODE_ROW_MAPPER = new RowMapper<LicenseCode>() {
        @Override
        public LicenseCode mapRow(ResultSet rs, int rowNum) throws SQLException {
            LicenseCode code = new LicenseCode();
            code.setId(rs.getLong("id"));
            code.setCode(rs.getString("code"));
            code.setPlanId(rs.getLong("plan_id"));
            code.setIssueLimit(rs.getInt("issue_limit"));
            code.setIssueCount(rs.getInt("issue_count"));
            
            // 处理时间戳解析问题
            code.setExpAt(parseTimestamp(rs, "exp_at"));
            code.setStatus(rs.getString("status"));
            code.setNote(rs.getString("note"));
            code.setCreatedAt(parseTimestamp(rs, "created_at"));
            code.setUpdatedAt(parseTimestamp(rs, "updated_at"));
            
            // Plan info if joined
            try {
                code.setPlanName(rs.getString("plan_name"));
                code.setPlanDurationHours(rs.getInt("plan_duration_hours"));
                code.setPlanInitQuota(rs.getInt("plan_init_quota"));
            } catch (SQLException e) {
                // No plan info in this query
            }
            
            return code;
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
    
    public Long insert(LicenseCode licenseCode) {
        String sql = "INSERT INTO license_codes (code, plan_id, issue_limit, issue_count, exp_at, " +
                     "status, note, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, licenseCode.getCode());
            ps.setLong(2, licenseCode.getPlanId());
            ps.setInt(3, licenseCode.getIssueLimit());
            ps.setInt(4, licenseCode.getIssueCount());
            ps.setObject(5, licenseCode.getExpAt());
            ps.setString(6, licenseCode.getStatus());
            ps.setString(7, licenseCode.getNote());
            ps.setObject(8, now);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public List<LicenseCode> findAllWithPlan() {
        String sql = "SELECT lc.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.init_quota as plan_init_quota FROM license_codes lc " +
                     "JOIN plans p ON lc.plan_id = p.id " +
                     "ORDER BY lc.created_at DESC";
        return jdbcTemplate.query(sql, LICENSE_CODE_ROW_MAPPER);
    }
    
    public Optional<LicenseCode> findByCode(String code) {
        String sql = "SELECT lc.*, p.name as plan_name, p.duration_hours as plan_duration_hours, " +
                     "p.init_quota as plan_init_quota FROM license_codes lc " +
                     "JOIN plans p ON lc.plan_id = p.id WHERE lc.code = ?";
        List<LicenseCode> results = jdbcTemplate.query(sql, LICENSE_CODE_ROW_MAPPER, code);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public Optional<LicenseCode> findById(Long id) {
        String sql = "SELECT * FROM license_codes WHERE id = ?";
        List<LicenseCode> results = jdbcTemplate.query(sql, LICENSE_CODE_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public int updateStatus(String code, String status) {
        String sql = "UPDATE license_codes SET status = ?, updated_at = ? WHERE code = ?";
        return jdbcTemplate.update(sql, status, LocalDateTime.now(), code);
    }
    
    public int incrementIssueCount(String code) {
        String sql = "UPDATE license_codes SET issue_count = issue_count + 1, updated_at = ? WHERE code = ?";
        return jdbcTemplate.update(sql, LocalDateTime.now(), code);
    }
    
    public int update(LicenseCode licenseCode) {
        String sql = "UPDATE license_codes SET plan_id = ?, issue_limit = ?, exp_at = ?, " +
                     "status = ?, note = ?, updated_at = ? WHERE id = ?";
        return jdbcTemplate.update(sql, licenseCode.getPlanId(), licenseCode.getIssueLimit(),
                                  licenseCode.getExpAt(), licenseCode.getStatus(), 
                                  licenseCode.getNote(), LocalDateTime.now(), licenseCode.getId());
    }
    
    public int deleteById(Long id) {
        String sql = "DELETE FROM license_codes WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }
    
    public int deleteByCode(String code) {
        String sql = "DELETE FROM license_codes WHERE code = ?";
        return jdbcTemplate.update(sql, code);
    }
}
