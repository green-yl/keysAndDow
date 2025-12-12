-- 与 Node 版结构一致，若不存在则创建
CREATE TABLE IF NOT EXISTS source_packages (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    code_name TEXT NOT NULL,
    version TEXT NOT NULL,
    description TEXT,
    country TEXT,
    website TEXT,
    sha256 TEXT NOT NULL,
    bucket_rel_path TEXT NOT NULL,
    package_ext TEXT NOT NULL,
    package_path TEXT,
    artifact_url TEXT,
    extracted_path TEXT,
    thumbnail_path TEXT,
    thumbnail_url TEXT,
    logo_path TEXT,
    logo_url TEXT,
    preview_path TEXT,
    preview_url TEXT,
    file_size INTEGER,
    download_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'uploaded',
    upload_time DATETIME DEFAULT (datetime('now')),
    update_time DATETIME DEFAULT (datetime('now')),
    uploaded_by TEXT,
    is_active INTEGER DEFAULT 1,
    UNIQUE (code_name, version),
    UNIQUE (sha256)
);

CREATE INDEX IF NOT EXISTS idx_source_packages_code_name ON source_packages(code_name);
CREATE INDEX IF NOT EXISTS idx_source_packages_sha256 ON source_packages(sha256);
CREATE INDEX IF NOT EXISTS idx_source_packages_status ON source_packages(status);
CREATE INDEX IF NOT EXISTS idx_source_packages_download_count ON source_packages(download_count);
CREATE INDEX IF NOT EXISTS idx_source_packages_update_time ON source_packages(update_time);

-- API Key 表
CREATE TABLE IF NOT EXISTS api_keys (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    api_key TEXT UNIQUE NOT NULL,
    created_time DATETIME DEFAULT (datetime('now')),
    is_active INTEGER DEFAULT 1
);

-- Authorization system tables
CREATE TABLE IF NOT EXISTS plans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(64) NOT NULL,
    duration_hours INTEGER NOT NULL,
    init_quota INTEGER NOT NULL,
    allow_grace BOOLEAN DEFAULT FALSE,
    features TEXT,
    created_at DATETIME DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS license_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE,
    plan_id INTEGER NOT NULL,
    issue_limit INTEGER NOT NULL DEFAULT 1,
    issue_count INTEGER NOT NULL DEFAULT 0,
    exp_at DATETIME NOT NULL,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active','frozen','revoked')),
    note TEXT,
    created_at DATETIME DEFAULT (datetime('now')),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES plans(id)
);

CREATE TABLE IF NOT EXISTS licenses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code VARCHAR(64) NOT NULL,
    sub VARCHAR(128) NOT NULL,
    hwid VARCHAR(128) NOT NULL,
    server_ip VARCHAR(45),
    last_server_switch_at DATETIME,
    plan_id INTEGER NOT NULL,
    valid_from DATETIME NOT NULL,
    valid_to DATETIME NOT NULL,
    kid VARCHAR(64) NOT NULL,
    license_payload TEXT NOT NULL,
    license_sig VARCHAR(256) NOT NULL,
    status VARCHAR(20) DEFAULT 'ok' CHECK (status IN ('ok','revoked')),
    download_quota_total INTEGER NOT NULL,
    download_quota_remaining INTEGER NOT NULL,
    created_at DATETIME DEFAULT (datetime('now')),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (code, hwid),
    FOREIGN KEY (plan_id) REFERENCES plans(id)
);

CREATE TABLE IF NOT EXISTS download_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token VARCHAR(64) NOT NULL UNIQUE,
    license_id INTEGER NOT NULL,
    file_id VARCHAR(128) NOT NULL,
    expire_at DATETIME NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    is_update BOOLEAN DEFAULT FALSE,
    from_version VARCHAR(32),
    created_at DATETIME DEFAULT (datetime('now')),
    FOREIGN KEY (license_id) REFERENCES licenses(id)
);

CREATE TABLE IF NOT EXISTS download_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    license_id INTEGER NOT NULL,
    token VARCHAR(64) NOT NULL,
    file_id VARCHAR(128) NOT NULL,
    ok BOOLEAN NOT NULL,
    deducted BOOLEAN NOT NULL,
    delta INTEGER NOT NULL DEFAULT 0,
    size BIGINT,
    sha256 CHAR(64),
    ip VARCHAR(45),
    ua VARCHAR(255),
    created_at DATETIME DEFAULT (datetime('now')),
    UNIQUE (token),
    FOREIGN KEY (license_id) REFERENCES licenses(id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target VARCHAR(64),
    details TEXT,
    created_at DATETIME DEFAULT (datetime('now'))
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_licenses_hwid ON licenses(hwid);
CREATE INDEX IF NOT EXISTS idx_licenses_valid_to ON licenses(valid_to);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);
CREATE INDEX IF NOT EXISTS idx_download_tokens_license_id ON download_tokens(license_id);
CREATE INDEX IF NOT EXISTS idx_download_tokens_expire_at ON download_tokens(expire_at);
CREATE INDEX IF NOT EXISTS idx_download_events_license_id ON download_events(license_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor ON audit_logs(actor);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at);

