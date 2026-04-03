#!/bin/bash
# ============================================================
# 智能运维系统 - 环境验证脚本 (Bash)
# ============================================================
#
# 用途：验证 Docker 环境和服务状态
#
# 使用方法：
#   chmod +x scripts/verify-env.sh
#   ./scripts/verify-env.sh
#
# Windows 用户：
#   Git Bash: ./scripts/verify-env.sh
#   PowerShell: 见 verify-env.ps1
#
# 作者：刘一舟
# 更新时间：2026-04-03
# ============================================================

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印函数
print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

print_info() {
    echo -e "    $1"
}

# 检查命令是否存在
check_command() {
    if command -v $1 &> /dev/null; then
        return 0
    else
        return 1
    fi
}

# 错误计数
ERRORS=0
WARNINGS=0

# ============================================================
# 1. 检查 Docker 环境
# ============================================================
print_header "检查 Docker 环境"

# 检查 Docker 是否安装
if check_command docker; then
    DOCKER_VERSION=$(docker --version)
    print_success "Docker 已安装: $DOCKER_VERSION"
else
    print_error "Docker 未安装"
    print_info "请访问 https://docs.docker.com/get-docker/ 安装 Docker"
    ((ERRORS++))
fi

# 检查 Docker 是否运行
if docker info &> /dev/null; then
    print_success "Docker 服务正在运行"
else
    print_error "Docker 服务未运行"
    print_info "请启动 Docker Desktop"
    ((ERRORS++))
fi

# 检查 Docker Compose
if check_command docker-compose; then
    COMPOSE_VERSION=$(docker-compose --version)
    print_success "Docker Compose 已安装: $COMPOSE_VERSION"
elif docker compose version &> /dev/null; then
    COMPOSE_VERSION=$(docker compose version)
    print_success "Docker Compose (V2) 已安装: $COMPOSE_VERSION"
else
    print_error "Docker Compose 未安装"
    ((ERRORS++))
fi

# 检查 Docker 资源
print_header "检查 Docker 资源配置"

# 获取 Docker 分配的内存
DOCKER_MEMORY=$(docker info 2>/dev/null | grep "Total Memory" | awk '{print $3}')
if [ ! -z "$DOCKER_MEMORY" ]; then
    # 转换为整数（去除单位）
    MEMORY_NUM=$(echo $DOCKER_MEMORY | sed 's/[^0-9.]//g')
    MEMORY_UNIT=$(echo $DOCKER_MEMORY | sed 's/[0-9.]//g')

    # 检查内存是否足够（至少 8GB）
    if [ "$MEMORY_UNIT" = "GiB" ] || [ "$MEMORY_UNIT" = "GB" ]; then
        if (( $(echo "$MEMORY_NUM >= 8" | bc -l) )); then
            print_success "Docker 内存分配: $DOCKER_MEMORY (充足)"
        else
            print_warning "Docker 内存分配: $DOCKER_MEMORY (建议至少 8GB，Milvus 需要较多内存)"
            print_info "请在 Docker Desktop 设置中增加内存分配"
            ((WARNINGS++))
        fi
    else
        print_warning "Docker 内存分配: $DOCKER_MEMORY"
    fi
fi

# ============================================================
# 2. 检查端口占用
# ============================================================
print_header "检查端口占用"

check_port() {
    local port=$1
    local service=$2

    if netstat -an 2>/dev/null | grep -q ":$port " || \
       ss -tuln 2>/dev/null | grep -q ":$port "; then
        print_error "端口 $port ($service) 已被占用"
        print_info "请关闭占用该端口的服务，或修改 .env 中的端口配置"
        ((ERRORS++))
    else
        print_success "端口 $port ($service) 可用"
    fi
}

# 检查所需端口
check_port 3306 "MySQL"
check_port 6379 "Redis"
check_port 19530 "Milvus gRPC"
check_port 9091 "Milvus Metrics"
check_port 11434 "Ollama"
check_port 9000 "MinIO API"
check_port 9001 "MinIO Console"

# ============================================================
# 3. 检查配置文件
# ============================================================
print_header "检查配置文件"

check_file() {
    local file=$1
    local required=$2

    if [ -f "$file" ]; then
        print_success "配置文件存在: $file"
    else
        if [ "$required" = "required" ]; then
            print_error "缺少必要配置文件: $file"
            ((ERRORS++))
        else
            print_warning "可选配置文件不存在: $file"
            ((WARNINGS++))
        fi
    fi
}

check_file "docker-compose.yml" "required"
check_file ".env" "optional"
check_file ".env.example" "optional"
check_file "config/mysql/my.cnf" "optional"
check_file "config/redis/redis.conf" "optional"

# 检查 .env 文件
if [ ! -f ".env" ]; then
    print_warning "未找到 .env 文件"
    print_info "请执行: cp .env.example .env"
    print_info "然后修改 .env 中的密码配置"
    ((WARNINGS++))
fi

# ============================================================
# 4. 检查数据目录
# ============================================================
print_header "检查数据目录"

check_dir() {
    local dir=$1
    if [ -d "$dir" ]; then
        print_success "数据目录存在: $dir"
        # 检查权限
        if [ -w "$dir" ]; then
            print_info "目录可写"
        else
            print_error "目录不可写: $dir"
            ((ERRORS++))
        fi
    else
        print_info "数据目录不存在，将由 Docker 自动创建: $dir"
    fi
}

check_dir "data/mysql"
check_dir "data/redis"
check_dir "data/milvus"
check_dir "data/ollama"

# ============================================================
# 5. 检查已运行的服务
# ============================================================
print_header "检查已运行的服务"

if docker ps &> /dev/null; then
    RUNNING_CONTAINERS=$(docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null)

    if echo "$RUNNING_CONTAINERS" | grep -q "netdata-ops"; then
        print_success "发现正在运行的服务:"
        echo "$RUNNING_CONTAINERS" | grep "netdata-ops" | while read line; do
            print_info "$line"
        done
    else
        print_info "未发现正在运行的服务"
        print_info "启动服务: docker-compose up -d"
    fi
fi

# ============================================================
# 6. 健康检查函数
# ============================================================
check_service_health() {
    local service=$1
    local container="netdata-ops-$service"

    if docker ps --format "{{.Names}}" | grep -q "^$container$"; then
        HEALTH=$(docker inspect --format='{{.State.Health.Status}}' $container 2>/dev/null)
        if [ "$HEALTH" = "healthy" ]; then
            print_success "$service: 健康"
        elif [ "$HEALTH" = "starting" ]; then
            print_warning "$service: 启动中"
        else
            print_error "$service: 不健康 ($HEALTH)"
            print_info "查看日志: docker-compose logs $service"
            ((ERRORS++))
        fi
    fi
}

# 如果有服务在运行，检查健康状态
if docker ps --format "{{.Names}}" | grep -q "netdata-ops"; then
    print_header "服务健康检查"
    check_service_health "mysql"
    check_service_health "redis"
    check_service_health "milvus-standalone"
    check_service_health "ollama"
fi

# ============================================================
# 7. 总结
# ============================================================
print_header "环境检查总结"

echo -e "错误数: ${RED}$ERRORS${NC}"
echo -e "警告数: ${YELLOW}$WARNINGS${NC}\n"

if [ $ERRORS -eq 0 ]; then
    if [ $WARNINGS -eq 0 ]; then
        print_success "环境检查通过！"
        echo -e "\n${GREEN}下一步操作:${NC}"
        echo "  1. 复制配置: cp .env.example .env"
        echo "  2. 修改密码: 编辑 .env 文件"
        echo "  3. 启动服务: docker-compose up -d"
        echo "  4. 查看日志: docker-compose logs -f"
    else
        print_warning "环境检查通过，但有警告项"
        print_info "建议解决警告后再启动服务"
    fi
else
    print_error "环境检查失败"
    print_info "请解决上述错误后重新运行此脚本"
    exit 1
fi

# ============================================================
# 8. 快速连接测试
# ============================================================
print_header "快速连接测试"

# 测试 MySQL 连接
if docker ps --format "{{.Names}}" | grep -q "netdata-ops-mysql"; then
    echo -e "${BLUE}MySQL 连接测试:${NC}"
    print_info "docker exec -it netdata-ops-mysql mysql -u ops_user -p"
fi

# 测试 Redis 连接
if docker ps --format "{{.Names}}" | grep -q "netdata-ops-redis"; then
    echo -e "\n${BLUE}Redis 连接测试:${NC}"
    print_info "docker exec -it netdata-ops-redis redis-cli -a redis123456 ping"
fi

# 测试 Milvus 连接
if docker ps --format "{{.Names}}" | grep -q "netdata-ops-milvus"; then
    echo -e "\n${BLUE}Milvus 连接测试:${NC}"
    print_info "curl http://localhost:9091/healthz"
fi

# 测试 Ollama 连接
if docker ps --format "{{.Names}}" | grep -q "netdata-ops-ollama"; then
    echo -e "\n${BLUE}Ollama 连接测试:${NC}"
    print_info "curl http://localhost:11434/api/tags"
fi

echo -e "\n${GREEN}脚本执行完毕${NC}"
