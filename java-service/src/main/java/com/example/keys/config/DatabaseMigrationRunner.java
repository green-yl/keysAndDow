package com.example.keys.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatabaseMigrationRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private final JdbcTemplate jdbc;

    public DatabaseMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        ensureColumn("source_packages", "artifact_url", "TEXT");
        ensureColumn("source_packages", "thumbnail_url", "TEXT");
        ensureColumn("source_packages", "country", "TEXT");
        ensureColumn("source_packages", "website", "TEXT");
        ensureColumn("source_packages", "logo_path", "TEXT");
        ensureColumn("source_packages", "logo_url", "TEXT");
        ensureColumn("source_packages", "preview_path", "TEXT");
        ensureColumn("source_packages", "preview_url", "TEXT");
        
        // 添加 licenses 表的服务器IP相关字段
        ensureColumn("licenses", "server_ip", "VARCHAR(45)");
        ensureColumn("licenses", "last_server_switch_at", "DATETIME");
    }

    private void ensureColumn(String table, String column, String type) {
        try {
            if (!columnExists(table, column)) {
                String sql = "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type;
                jdbc.execute(sql);
                log.info("DB migration: added column {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("DB migration: failed to add column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null && column.equalsIgnoreCase(String.valueOf(name))) {
                return true;
            }
        }
        return false;
    }
}


