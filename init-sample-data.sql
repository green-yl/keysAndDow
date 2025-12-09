-- 初始化示例数据
-- 使用方法: sqlite3 database.sqlite < init-sample-data.sql

-- 清空现有数据（谨慎使用）
DELETE FROM download_events;
DELETE FROM download_tokens;
DELETE FROM licenses;
DELETE FROM license_codes;
DELETE FROM plans;
DELETE FROM audit_logs;
DELETE FROM api_keys;
DELETE FROM source_packages;

-- 插入示例计划
INSERT INTO plans (id, name, duration_hours, init_quota, allow_grace, features, created_at)
VALUES 
(1, '基础版', 720, 10, 0, '{"max_downloads": 10}', datetime('now')),
(2, '标准版', 1440, 50, 1, '{"max_downloads": 50}', datetime('now')),
(3, '专业版', 4320, 200, 1, '{"max_downloads": 200, "priority_support": true}', datetime('now'));

-- 插入示例许可证代码
INSERT INTO license_codes (code, plan_id, issue_limit, issue_count, exp_at, status, note, created_at)
VALUES 
('DEMO-TEST-2025', 1, 100, 0, datetime('now', '+90 days'), 'active', '示例测试码', datetime('now')),
('STANDARD-2025', 2, 50, 0, datetime('now', '+180 days'), 'active', '标准版测试码', datetime('now'));

-- 插入示例API密钥
INSERT INTO api_keys (id, name, api_key, created_time, is_active)
VALUES 
('1', 'Default API Key', 'test-api-key-12345678', datetime('now'), 1);

-- 插入审计日志
INSERT INTO audit_logs (actor, action, target, details, created_at)
VALUES 
('system', 'init', 'database', '初始化示例数据', datetime('now'));

SELECT '示例数据初始化完成！' as message;
SELECT '计划数量: ' || COUNT(*) as result FROM plans;
SELECT '许可证代码数量: ' || COUNT(*) as result FROM license_codes;
SELECT 'API密钥数量: ' || COUNT(*) as result FROM api_keys;






