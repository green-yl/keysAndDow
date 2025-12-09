@echo off
chcp 65001 > nul
echo 启动 Java 源码管理服务
echo ======================

where mvn >nul 2>&1
if errorlevel 1 (
  echo 未检测到 Maven，请安装 Maven 后重试
  pause
  exit /b 1
)

echo 构建中...
mvn -q -e -DskipTests package
if errorlevel 1 (
  echo 构建失败
  pause
  exit /b 1
)

set SOURCE_CACHE_DIR=
set RELEASES_POOL_DIR=

echo 运行中...
mvn -q -DskipTests spring-boot:run
pause







