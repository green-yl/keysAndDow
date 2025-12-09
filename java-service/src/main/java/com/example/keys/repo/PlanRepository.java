package com.example.keys.repo;

import com.example.keys.model.Plan;
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
public class PlanRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private static final RowMapper<Plan> PLAN_ROW_MAPPER = new RowMapper<Plan>() {
        @Override
        public Plan mapRow(ResultSet rs, int rowNum) throws SQLException {
            Plan plan = new Plan();
            plan.setId(rs.getLong("id"));
            plan.setName(rs.getString("name"));
            plan.setDurationHours(rs.getInt("duration_hours"));
            plan.setInitQuota(rs.getInt("init_quota"));
            plan.setAllowGrace(rs.getBoolean("allow_grace"));
            plan.setFeatures(rs.getString("features"));
            
            // 处理时间戳解析问题
            try {
                plan.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            } catch (Exception e) {
                // 如果时间戳解析失败，使用字符串解析
                String timeStr = rs.getString("created_at");
                if (timeStr != null) {
                    try {
                        plan.setCreatedAt(LocalDateTime.parse(timeStr.replace(" ", "T")));
                    } catch (Exception ex) {
                        plan.setCreatedAt(LocalDateTime.now());
                    }
                } else {
                    plan.setCreatedAt(LocalDateTime.now());
                }
            }
            
            return plan;
        }
    };
    
    public Long insert(Plan plan) {
        String sql = "INSERT INTO plans (name, duration_hours, init_quota, allow_grace, features, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.getName());
            ps.setInt(2, plan.getDurationHours());
            ps.setInt(3, plan.getInitQuota());
            ps.setBoolean(4, plan.getAllowGrace() != null ? plan.getAllowGrace() : false);
            ps.setString(5, plan.getFeatures());
            ps.setObject(6, LocalDateTime.now());
            return ps;
        }, keyHolder);
        
        return keyHolder.getKey().longValue();
    }
    
    public List<Plan> findAll() {
        String sql = "SELECT * FROM plans ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, PLAN_ROW_MAPPER);
    }
    
    public Optional<Plan> findById(Long id) {
        String sql = "SELECT * FROM plans WHERE id = ?";
        List<Plan> results = jdbcTemplate.query(sql, PLAN_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
    
    public int update(Plan plan) {
        String sql = "UPDATE plans SET name = ?, duration_hours = ?, init_quota = ?, " +
                     "allow_grace = ?, features = ? WHERE id = ?";
        return jdbcTemplate.update(sql, plan.getName(), plan.getDurationHours(), 
                                  plan.getInitQuota(), plan.getAllowGrace(), 
                                  plan.getFeatures(), plan.getId());
    }
    
    public int deleteById(Long id) {
        String sql = "DELETE FROM plans WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }
}
