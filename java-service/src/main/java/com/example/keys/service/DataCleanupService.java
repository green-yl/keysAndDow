package com.example.keys.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimitService rateLimitService;

    @Scheduled(fixedRate = 3600_000)
    public void cleanExpiredTokens() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM download_tokens WHERE expire_at < datetime('now') AND used = 1");
            if (deleted > 0) {
                log.info("清理已使用且过期的下载令牌: {} 条", deleted);
            }
        } catch (Exception e) {
            log.error("清理过期下载令牌失败", e);
        }
    }

    @Scheduled(fixedRate = 86400_000)
    public void cleanOldAuditLogs() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM audit_logs WHERE created_at < datetime('now', '-90 days')");
            if (deleted > 0) {
                log.info("清理90天前的审计日志: {} 条", deleted);
            }
        } catch (Exception e) {
            log.error("清理旧审计日志失败", e);
        }
    }

    @Scheduled(fixedRate = 1800_000)
    public void cleanRateLimitBuckets() {
        int before = rateLimitService.getBucketCount();
        if (before > 5000) {
            rateLimitService.cleanup();
            log.info("清理限流桶: {} -> 0", before);
        }
    }
}
