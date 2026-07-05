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
        ensureAppSettingsTable();
        ensureTgActivationOrdersTable();
        ensureTgPaymentUsersTable();
        ensureTgPaymentOrdersTable();
        ensureTgWalletTables();
        ensureTgTopupOrdersTable();
        ensureColumn("tg_payment_orders", "epusdt_trade_id", "TEXT");
        ensureColumn("tg_payment_orders", "epusdt_payment_url", "TEXT");
        ensureColumn("tg_payment_orders", "epusdt_receive_address", "TEXT");
        ensureColumn("tg_payment_orders", "epusdt_token", "TEXT");
        ensureColumn("tg_payment_orders", "epusdt_actual_amount", "TEXT");

        ensureColumn("source_packages", "artifact_url", "TEXT");
        ensureColumn("source_packages", "thumbnail_url", "TEXT");
        ensureColumn("source_packages", "country", "TEXT");
        ensureColumn("source_packages", "website", "TEXT");
        ensureColumn("source_packages", "logo_path", "TEXT");
        ensureColumn("source_packages", "logo_url", "TEXT");
        ensureColumn("source_packages", "preview_path", "TEXT");
        ensureColumn("source_packages", "preview_url", "TEXT");
        ensureColumn("source_packages", "is_hidden", "INTEGER DEFAULT 0");
        ensureColumn("source_packages", "install_code", "TEXT");
        syncSourcePackageDownloadCounts();
        
        // 添加 licenses 表的服务器IP相关字段
        ensureColumn("licenses", "server_ip", "VARCHAR(45)");
        ensureColumn("licenses", "last_server_switch_at", "DATETIME");
        ensureColumn("license_codes", "issue_limit", "INTEGER NOT NULL DEFAULT 1");
        ensureColumn("license_codes", "issue_count", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("download_tokens", "license_code", "TEXT");
        ensureColumn("download_tokens", "hwid", "TEXT");
        ensureColumn("download_tokens", "source_id", "TEXT");
        ensureIndex("idx_download_tokens_license_code", "download_tokens", "license_code");
        ensureIndex("idx_download_tokens_hwid", "download_tokens", "hwid");
    }


    private void syncSourcePackageDownloadCounts() {
        try {
            jdbc.update("""
                    UPDATE source_packages
                    SET download_count = (
                        SELECT COALESCE(MAX(s2.download_count), 0)
                        FROM source_packages s2
                        WHERE s2.code_name = source_packages.code_name AND s2.is_active=1
                    )
                    WHERE is_active=1
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to sync source package download_count: {}", e.getMessage());
        }
    }

    private void ensureAppSettingsTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS app_settings (
                        setting_key TEXT PRIMARY KEY,
                        setting_value TEXT,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure app_settings table: {}", e.getMessage());
        }
    }

    private void ensureTgActivationOrdersTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_activation_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        external_order_id TEXT NOT NULL UNIQUE,
                        tg_user_id TEXT,
                        tg_username TEXT,
                        plan_id INTEGER NOT NULL,
                        license_code TEXT NOT NULL,
                        amount TEXT,
                        currency TEXT,
                        payment_provider TEXT,
                        payment_payload TEXT,
                        created_at DATETIME DEFAULT (datetime('now')),
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (plan_id) REFERENCES plans(id),
                        FOREIGN KEY (license_code) REFERENCES license_codes(code)
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure tg_activation_orders table: {}", e.getMessage());
        }
    }

    private void ensureTgPaymentUsersTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_payment_users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tg_user_id TEXT NOT NULL UNIQUE,
                        tg_username TEXT,
                        total_paid_usdt TEXT NOT NULL DEFAULT '0',
                        member_level TEXT NOT NULL DEFAULT 'normal',
                        half_price INTEGER NOT NULL DEFAULT 0,
                        created_at DATETIME DEFAULT (datetime('now')),
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure tg_payment_users table: {}", e.getMessage());
        }
    }

    private void ensureTgPaymentOrdersTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_payment_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id TEXT NOT NULL UNIQUE,
                        tg_user_id TEXT NOT NULL,
                        tg_username TEXT,
                        plan_key TEXT NOT NULL,
                        plan_id INTEGER NOT NULL,
                        original_amount TEXT NOT NULL,
                        payable_amount TEXT NOT NULL,
                        paid_amount TEXT,
                        currency TEXT NOT NULL DEFAULT 'USDT',
                        status TEXT NOT NULL DEFAULT 'pending',
                        tx_id TEXT,
                        activation_code TEXT,
                        member_level_before TEXT,
                        member_level_after TEXT,
                        epusdt_trade_id TEXT,
                        epusdt_payment_url TEXT,
                        epusdt_receive_address TEXT,
                        epusdt_token TEXT,
                        epusdt_actual_amount TEXT,
                        created_at DATETIME DEFAULT (datetime('now')),
                        paid_at DATETIME,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (plan_id) REFERENCES plans(id),
                        FOREIGN KEY (activation_code) REFERENCES license_codes(code)
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure tg_payment_orders table: {}", e.getMessage());
        }
    }

    private void ensureTgWalletTables() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_wallets (
                        tg_user_id TEXT PRIMARY KEY,
                        balance_micros INTEGER NOT NULL DEFAULT 0,
                        created_at DATETIME DEFAULT (datetime('now')),
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_wallet_ledger (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tg_user_id TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        amount_micros INTEGER NOT NULL,
                        balance_after_micros INTEGER NOT NULL,
                        external_ref TEXT NOT NULL UNIQUE,
                        note TEXT,
                        created_at DATETIME DEFAULT (datetime('now'))
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure tg wallet tables: {}", e.getMessage());
        }
    }

    private void ensureTgTopupOrdersTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS tg_topup_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id TEXT NOT NULL UNIQUE,
                        tg_user_id TEXT NOT NULL,
                        tg_username TEXT,
                        amount_usdt TEXT NOT NULL,
                        amount_micros INTEGER NOT NULL,
                        payable_amount TEXT,
                        status TEXT NOT NULL DEFAULT 'pending',
                        tx_id TEXT,
                        epusdt_trade_id TEXT,
                        epusdt_payment_url TEXT,
                        epusdt_receive_address TEXT,
                        epusdt_token TEXT,
                        epusdt_actual_amount TEXT,
                        created_at DATETIME DEFAULT (datetime('now')),
                        paid_at DATETIME,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure tg_topup_orders table: {}", e.getMessage());
        }
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

    private void ensureIndex(String indexName, String table, String column) {
        try {
            if (columnExists(table, column)) {
                jdbc.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(" + column + ")");
            }
        } catch (Exception e) {
            log.warn("DB migration: failed to ensure index {}: {}", indexName, e.getMessage());
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
