# 授权端 Cloudflare / WAF 规则整理

适用域名：

- API 域名：`auth-api.coolpay.top`
- 后台域名：`auth-admin.coolpay.top`

目标：API 域名只开放授权/API/源码下载链路，后台页面只允许后台域名访问；限流由 Cloudflare 外层 + Java 内层双保险完成。

## 规则顺序

### 1. API 域名禁止访问后台页面

动作：`Block`

表达式：

```text
(http.host eq "auth-api.coolpay.top" and (http.request.uri.path eq "/admin-login.html" or http.request.uri.path eq "/admin.html" or starts_with(http.request.uri.path, "/admin/") or starts_with(http.request.uri.path, "/assets/admin")))
```

### 2. 阻断常见探测路径

动作：`Block`

表达式：

```text
(http.request.uri.path contains "/.git" or http.request.uri.path contains "/.env" or http.request.uri.path contains "/actuator" or http.request.uri.path contains "/swagger" or http.request.uri.path contains "/v3/api-docs" or http.request.uri.path contains "/wp-admin" or http.request.uri.path contains "/xmlrpc.php")
```

### 3. 授权激活接口加强挑战

动作：`Managed Challenge`

表达式：

```text
(http.host eq "auth-api.coolpay.top" and http.request.method eq "POST" and http.request.uri.path eq "/api/license/activate")
```

说明：如果客户服务器调用 Cloudflare Challenge 不稳定，就把动作从 `Managed Challenge` 改为 Cloudflare Rate Limiting；Java 内层已经有细分限流兜底。

### 4. 下载预授权和回执接口限速

动作：优先使用 Cloudflare `Rate Limiting Rules`，超过阈值后 `Block` 1 分钟。

匹配表达式：

```text
(http.host eq "auth-api.coolpay.top" and http.request.method eq "POST" and (http.request.uri.path eq "/api/download/preauth" or http.request.uri.path eq "/api/download/commit"))
```

建议阈值：

```text
同 IP 60 秒 120 次
```

### 5. 下载令牌接口限速

动作：Cloudflare `Rate Limiting Rules`，超过阈值后 `Block` 1 分钟。

匹配表达式：

```text
(http.host eq "auth-api.coolpay.top" and http.request.method eq "GET" and starts_with(http.request.uri.path, "/api/download/"))
```

建议阈值：

```text
同 IP 60 秒 120 次
```

### 6. 源码列表与更新检查限速

动作：Cloudflare `Rate Limiting Rules`，超过阈值后 `Block` 1 分钟。

匹配表达式：

```text
(http.host eq "auth-api.coolpay.top" and (http.request.uri.path eq "/api/sources" or http.request.uri.path eq "/api/sources/check-update"))
```

建议阈值：

```text
同 IP 60 秒 160 次
```

## Cache Rules

授权 API、源码下载、后台页面都不应被缓存。

表达式：

```text
(http.host in {"auth-api.coolpay.top" "auth-admin.coolpay.top"} and starts_with(http.request.uri.path, "/api/"))
```

动作：`Bypass cache`

## Java 内层限流对应关系

| 接口 | Java 内层维度 |
| --- | --- |
| `/api/license/activate` | IP / 激活码 / 设备 / 全局 |
| `/api/license/status` | IP / HWID |
| `/api/license/info` | IP / HWID |
| `/api/sources` | IP / HWID |
| `/api/sources/check-update` | IP / HWID |
| `/api/download/preauth` | IP / HWID / License |
| `/api/download/commit` | IP / Token / License |
| `/api/download/{token}` | IP / Token |

## 验证命令

```bash
curl -I https://auth-api.coolpay.top/api/health
curl -I https://auth-api.coolpay.top/api/system/ping
curl -I https://auth-api.coolpay.top/admin-login.html
curl -I https://auth-admin.coolpay.top/admin-login.html
```

预期：

- API health / ping 正常返回。
- `auth-api.coolpay.top/admin-login.html` 被拦截。
- `auth-admin.coolpay.top/admin-login.html` 可访问后台。
