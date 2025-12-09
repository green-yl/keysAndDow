# 前端源码批量管理系统

## 系统架构设计

### 1. 目录结构
```
keysAndDwd/
└── java-service/         # Java(Spring Boot) 独立后端服务（唯一后端）
```

### 2. 数据库表设计

#### source_packages 表 - 源码包信息
- id: 主键
- name: 源码名称
- code_name: 代码名（唯一标识）
- version: 版本号
- package_path: 压缩包路径
- extracted_path: 解压路径
- thumbnail_path: 缩略图路径
- upload_time: 上传时间
- status: 状态（uploaded/extracted/deployed）

#### domain_configs 表 - 域名配置
- id: 主键
- domain_name: 域名
- source_id: 关联的源码ID
- ssl_enabled: 是否启用SSL
- ssl_cert_path: SSL证书路径
- ssl_key_path: SSL私钥路径
- status: 状态（active/inactive）
- created_time: 创建时间

### 3. 功能模块

1. **源码管理模块**
   - 上传压缩包
   - 自动解压
   - 版本管理
   - 缩略图生成

2. **域名配置模块**
   - 域名绑定源码
   - SSL证书申请
   - 批量操作

3. **反向代理模块**
   - 动态nginx配置
   - 自动重载配置
   - 负载均衡

## 实现思路

1. **文件存储策略**: 按源码代码名创建独立目录，支持多版本管理
2. **域名解析**: 使用nginx反向代理，动态生成配置文件
3. **SSL管理**: 集成Let's Encrypt自动申请和续期
4. **安全考虑**: 文件上传验证、路径安全检查、权限控制

## Java 服务（唯一后端）

- API：/api/sources 上传/导入/列表/删除/校验/解压/下载（端口默认3001）
- 去重缓存：默认 Windows D:/srv/source_cache，Linux /srv/source_cache（可覆盖）
- 发布目录：默认 Windows D:/srv/releases_pool，Linux /srv/releases_pool
- 数据库：SQLite（默认复用 keysAndDwd/database.sqlite）
