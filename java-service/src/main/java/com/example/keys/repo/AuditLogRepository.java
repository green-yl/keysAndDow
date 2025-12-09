package com.example.keys.repo;

import com.example.keys.model.AuditLog;
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

@Repository
public class AuditLogRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<AuditLog> AUDIT_LOG_ROW_MAPPER = new RowMapper<AuditLog>() {
        @Override
        public AuditLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            AuditLog log = new AuditLog();
            log.setId(rs.getLong("id"));
            log.setActor(rs.getString("actor"));
            log.setAction(rs.getString("action"));
            log.setTarget(rs.getString("target"));
            log.setDetails(rs.getString("details"));
            
            // 处理时间戳解析问题
            log.setCreatedAt(parseTimestamp(rs, "created_at"));
            
            return log;
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
    
    public Long insert(AuditLog auditLog) {
        String sql = "INSERT INTO audit_logs (actor, action, target, details, created_at) " +
                     "VALUES (?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, auditLog.getActor());
            ps.setString(2, auditLog.getAction());
            ps.setString(3, auditLog.getTarget());
            ps.setString(4, auditLog.getDetails());
            ps.setObject(5, LocalDateTime.now());
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public List<AuditLog> findAll(int limit) {
        String sql = "SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, limit);
    }
    
    public List<AuditLog> findByActor(String actor, int limit) {
        String sql = "SELECT * FROM audit_logs WHERE actor = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, actor, limit);
    }
    
    public List<AuditLog> findByAction(String action, int limit) {
        String sql = "SELECT * FROM audit_logs WHERE action = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, action, limit);
    }
    
    public List<AuditLog> findByTarget(String target, int limit) {
        String sql = "SELECT * FROM audit_logs WHERE target = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, target, limit);
    }
    
    public List<AuditLog> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, int limit) {
        String sql = "SELECT * FROM audit_logs WHERE created_at BETWEEN ? AND ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, startDate, endDate, limit);
    }
    
    public int cleanupOldLogs(LocalDateTime beforeDate) {
        String sql = "DELETE FROM audit_logs WHERE created_at < ?";
        return jdbcTemplate.update(sql, beforeDate);
    }
    
    /**
     * 分页查询审计日志
     */
    public List<AuditLog> findAllPaginated(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = "SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, pageSize, offset);
    }
    
    /**
     * 获取审计日志总数
     */
    public int count() {
        String sql = "SELECT COUNT(*) FROM audit_logs";
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
    
    /**
     * 清空所有审计日志
     */
    public int deleteAll() {
        String sql = "DELETE FROM audit_logs";
        return jdbcTemplate.update(sql);
    }
    
    /**
     * 根据条件分页查询
     */
    public List<AuditLog> findByActorPaginated(String actor, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = "SELECT * FROM audit_logs WHERE actor = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, actor, pageSize, offset);
    }
    
    public int countByActor(String actor) {
        String sql = "SELECT COUNT(*) FROM audit_logs WHERE actor = ?";
        return jdbcTemplate.queryForObject(sql, Integer.class, actor);
    }
    
    public List<AuditLog> findByActionPaginated(String action, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = "SELECT * FROM audit_logs WHERE action = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, action, pageSize, offset);
    }
    
    public int countByAction(String action) {
        String sql = "SELECT COUNT(*) FROM audit_logs WHERE action = ?";
        return jdbcTemplate.queryForObject(sql, Integer.class, action);
    }
}
