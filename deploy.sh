#!/bin/bash
#======================================
# keysAndDwd 授权服务一键部署脚本
# 适用于 Ubuntu 22.04 / 20.04
#======================================

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 部署目录
DEPLOY_DIR="/opt/keysAndDwd"
GITHUB_REPO="https://github.com/green-yl/keysAndDow.git"
LOG_FILE="/var/log/keysAndDwd-deploy.log"

log_info "========================================="
log_info "keysAndDwd 授权服务部署开始"
log_info "========================================="

# 1. 检查并安装 Docker
install_docker() {
    if command -v docker &> /dev/null; then
        log_info "Docker 已安装: $(docker --version)"
    else
        log_info "安装 Docker..."
        apt-get update
        apt-get install -y ca-certificates curl gnupg
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg
        echo \
          "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
          $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
          tee /etc/apt/sources.list.d/docker.list > /dev/null
        apt-get update
        apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
        systemctl enable docker
        systemctl start docker
        log_info "Docker 安装完成"
    fi
}

# 2. 检查并安装 Docker Compose
install_docker_compose() {
    if docker compose version &> /dev/null; then
        log_info "Docker Compose 已安装: $(docker compose version)"
    else
        log_info "安装 Docker Compose..."
        apt-get install -y docker-compose-plugin
        log_info "Docker Compose 安装完成"
    fi
}

# 3. 安装 Git
install_git() {
    if command -v git &> /dev/null; then
        log_info "Git 已安装: $(git --version)"
    else
        log_info "安装 Git..."
        apt-get install -y git
        log_info "Git 安装完成"
    fi
}

# 4. 克隆或更新代码
clone_or_pull_code() {
    if [ -d "$DEPLOY_DIR" ]; then
        log_info "更新代码..."
        cd "$DEPLOY_DIR"
        git fetch origin main
        git reset --hard origin/main
    else
        log_info "克隆代码..."
        git clone "$GITHUB_REPO" "$DEPLOY_DIR"
    fi
    cd "$DEPLOY_DIR"
}

# 5. 创建必要目录
create_directories() {
    log_info "创建目录结构..."
    mkdir -p data sources/uploaded sources/extracted sources/thumbnails temp-uploads logs configs/ssl configs/domains
    chmod -R 755 data sources temp-uploads logs configs
}

# 6. 初始化数据库（如果不存在）
init_database() {
    if [ ! -f "data/database.sqlite" ]; then
        log_info "初始化 SQLite 数据库..."
        # 数据库会在首次启动时自动创建
        touch data/database.sqlite
    else
        log_info "数据库已存在，跳过初始化"
    fi
}

# 7. 构建并启动服务
build_and_start() {
    log_info "构建 Docker 镜像..."
    docker compose build --no-cache
    
    log_info "启动服务..."
    docker compose up -d
    
    log_info "等待服务启动..."
    sleep 15
    
    # 检查服务状态
    if docker compose ps | grep -q "Up"; then
        log_info "服务启动成功！"
    else
        log_error "服务启动失败，查看日志..."
        docker compose logs --tail=50
        exit 1
    fi
}

# 8. 显示访问信息
show_info() {
    SERVER_IP=$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')
    
    echo ""
    log_info "========================================="
    log_info "keysAndDwd 授权服务部署完成！"
    log_info "========================================="
    echo ""
    echo "访问地址："
    echo "  - 授权服务 API: http://${SERVER_IP}:3003"
    echo "  - 管理页面: http://${SERVER_IP}:3003/admin.html"
    echo "  - 健康检查: http://${SERVER_IP}:3003/api/health"
    echo ""
    echo "常用命令："
    echo "  - 查看状态: cd $DEPLOY_DIR/keysAndDwd && docker compose ps"
    echo "  - 查看日志: cd $DEPLOY_DIR/keysAndDwd && docker compose logs -f"
    echo "  - 重启服务: cd $DEPLOY_DIR/keysAndDwd && docker compose restart"
    echo "  - 停止服务: cd $DEPLOY_DIR/keysAndDwd && docker compose down"
    echo ""
    log_info "部署日志已保存到: $LOG_FILE"
}

# 主流程
main() {
    # 检查 root 权限
    if [ "$EUID" -ne 0 ]; then
        log_error "请使用 root 权限运行此脚本"
        log_info "使用: sudo bash deploy.sh"
        exit 1
    fi
    
    install_docker
    install_docker_compose
    install_git
    clone_or_pull_code
    create_directories
    init_database
    build_and_start
    show_info
}

# 执行并记录日志
main 2>&1 | tee -a "$LOG_FILE"

