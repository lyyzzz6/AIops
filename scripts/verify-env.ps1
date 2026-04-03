# ============================================================
# 智能运维系统 - 环境验证脚本 (PowerShell)
# ============================================================
#
# 用途：验证 Docker 环境和服务状态 (Windows PowerShell)
#
# 使用方法：
#   .\scripts\verify-env.ps1
#
# 如果执行策略限制，先运行：
#   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
#
# 作者：刘一舟
# 更新时间：2026-04-03
# ============================================================

# 错误计数
$script:Errors = 0
$script:Warnings = 0

# 颜色输出函数
function Write-Success { param($msg) Write-Host "[✓] " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-Error { param($msg) Write-Host "[✗] " -ForegroundColor Red -NoNewline; Write-Host $msg; $script:Errors++ }
function Write-Warning { param($msg) Write-Host "[!] " -ForegroundColor Yellow -NoNewline; Write-Host $msg; $script:Warnings++ }
function Write-Info { param($msg) Write-Host "    $msg" }
function Write-Header { param($msg)
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host $msg -ForegroundColor Blue
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host ""
}

# ============================================================
# 1. 检查 Docker 环境
# ============================================================
Write-Header "检查 Docker 环境"

# 检查 Docker 是否安装
if (Get-Command docker -ErrorAction SilentlyContinue) {
    $dockerVersion = docker --version
    Write-Success "Docker 已安装: $dockerVersion"
} else {
    Write-Error "Docker 未安装"
    Write-Info "请访问 https://docs.docker.com/desktop/install/windows-install/ 安装 Docker Desktop"
}

# 检查 Docker 是否运行
try {
    $dockerInfo = docker info 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Success "Docker 服务正在运行"
    } else {
        Write-Error "Docker 服务未运行"
        Write-Info "请启动 Docker Desktop"
    }
} catch {
    Write-Error "Docker 服务未运行"
    Write-Info "请启动 Docker Desktop"
}

# 检查 Docker Compose
if (Get-Command docker-compose -ErrorAction SilentlyContinue) {
    $composeVersion = docker-compose --version
    Write-Success "Docker Compose 已安装: $composeVersion"
} elseif (docker compose version 2>$null) {
    $composeVersion = docker compose version
    Write-Success "Docker Compose (V2) 已安装: $composeVersion"
} else {
    Write-Error "Docker Compose 未安装"
}

# 检查 Docker 内存分配
Write-Header "检查 Docker 资源配置"
$dockerMemory = docker info 2>$null | Select-String "Total Memory"
if ($dockerMemory) {
    Write-Info "Docker $dockerMemory"
    Write-Info "建议至少分配 8GB 内存（Milvus 需要较多内存）"
}

# ============================================================
# 2. 检查端口占用
# ============================================================
Write-Header "检查端口占用"

$ports = @(
    @{Port=3306; Service="MySQL"},
    @{Port=6379; Service="Redis"},
    @{Port=19530; Service="Milvus gRPC"},
    @{Port=9091; Service="Milvus Metrics"},
    @{Port=11434; Service="Ollama"},
    @{Port=9000; Service="MinIO API"},
    @{Port=9001; Service="MinIO Console"}
)

foreach ($portInfo in $ports) {
    $port = $portInfo.Port
    $service = $portInfo.Service
    $connection = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($connection) {
        Write-Error "端口 $port ($service) 已被占用"
        Write-Info "请关闭占用该端口的服务，或修改 .env 中的端口配置"
    } else {
        Write-Success "端口 $port ($service) 可用"
    }
}

# ============================================================
# 3. 检查配置文件
# ============================================================
Write-Header "检查配置文件"

$files = @(
    @{Path="docker-compose.yml"; Required=$true},
    @{Path=".env"; Required=$false},
    @{Path=".env.example"; Required=$false},
    @{Path="config\mysql\my.cnf"; Required=$false},
    @{Path="config\redis\redis.conf"; Required=$false}
)

foreach ($fileInfo in $files) {
    $path = $fileInfo.Path
    $required = $fileInfo.Required
    if (Test-Path $path) {
        Write-Success "配置文件存在: $path"
    } else {
        if ($required) {
            Write-Error "缺少必要配置文件: $path"
        } else {
            Write-Warning "可选配置文件不存在: $path"
        }
    }
}

if (-not (Test-Path ".env")) {
    Write-Warning "未找到 .env 文件"
    Write-Info "请执行: Copy-Item .env.example .env"
    Write-Info "然后修改 .env 中的密码配置"
}

# ============================================================
# 4. 检查数据目录
# ============================================================
Write-Header "检查数据目录"

$dirs = @("data\mysql", "data\redis", "data\milvus", "data\ollama")
foreach ($dir in $dirs) {
    if (Test-Path $dir) {
        Write-Success "数据目录存在: $dir"
    } else {
        Write-Info "数据目录不存在，将由 Docker 自动创建: $dir"
    }
}

# ============================================================
# 5. 检查已运行的服务
# ============================================================
Write-Header "检查已运行的服务"

$runningContainers = docker ps --format "{{.Names}} {{.Status}}" 2>$null
if ($runningContainers -match "netdata-ops") {
    Write-Success "发现正在运行的服务:"
    $runningContainers.Split("`n") | Where-Object { $_ -match "netdata-ops" } | ForEach-Object {
        Write-Info $_
    }
} else {
    Write-Info "未发现正在运行的服务"
    Write-Info "启动服务: docker-compose up -d"
}

# ============================================================
# 6. 服务健康检查
# ============================================================
$containers = docker ps --format "{{.Names}}" 2>$null
if ($containers -match "netdata-ops") {
    Write-Header "服务健康检查"

    $services = @("mysql", "redis", "milvus-standalone", "ollama")
    foreach ($service in $services) {
        $containerName = "netdata-ops-$service"
        if ($containers -match $containerName) {
            try {
                $health = docker inspect --format='{{.State.Health.Status}}' $containerName 2>$null
                if ($health -eq "healthy") {
                    Write-Success "$service : 健康"
                } elseif ($health -eq "starting") {
                    Write-Warning "$service : 启动中"
                } else {
                    Write-Error "$service : 不健康 ($health)"
                    Write-Info "查看日志: docker-compose logs $service"
                }
            } catch {
                Write-Info "$service : 无法获取健康状态"
            }
        }
    }
}

# ============================================================
# 7. 总结
# ============================================================
Write-Header "环境检查总结"

Write-Host "错误数: " -NoNewline
Write-Host $script:Errors -ForegroundColor $(if ($script:Errors -gt 0) { "Red" } else { "Green" })
Write-Host "警告数: " -NoNewline
Write-Host $script:Warnings -ForegroundColor $(if ($script:Warnings -gt 0) { "Yellow" } else { "Green" })
Write-Host ""

if ($script:Errors -eq 0) {
    if ($script:Warnings -eq 0) {
        Write-Success "环境检查通过！"
        Write-Host ""
        Write-Host "下一步操作:" -ForegroundColor Green
        Write-Info "1. 复制配置: Copy-Item .env.example .env"
        Write-Info "2. 修改密码: 编辑 .env 文件"
        Write-Info "3. 启动服务: docker-compose up -d"
        Write-Info "4. 查看日志: docker-compose logs -f"
    } else {
        Write-Warning "环境检查通过，但有警告项"
        Write-Info "建议解决警告后再启动服务"
    }
} else {
    Write-Error "环境检查失败"
    Write-Info "请解决上述错误后重新运行此脚本"
    exit 1
}

# ============================================================
# 8. 快速连接测试命令
# ============================================================
Write-Header "快速连接测试命令"

Write-Host "MySQL 连接测试:" -ForegroundColor Blue
Write-Info "docker exec -it netdata-ops-mysql mysql -u ops_user -p"

Write-Host ""
Write-Host "Redis 连接测试:" -ForegroundColor Blue
Write-Info "docker exec -it netdata-ops-redis redis-cli -a redis123456 ping"

Write-Host ""
Write-Host "Milvus 连接测试:" -ForegroundColor Blue
Write-Info "curl http://localhost:9091/healthz"

Write-Host ""
Write-Host "Ollama 连接测试:" -ForegroundColor Blue
Write-Info "curl http://localhost:11434/api/tags"

Write-Host ""
Write-Host "脚本执行完毕" -ForegroundColor Green
