# start-rag.ps1
# 一键拉起 Jhermes 的 RAG 数据库环境（PostgreSQL + pgvector 容器）
# 自动探测两种 Docker 环境：Windows 本机（Docker Desktop）→ 失败回退 WSL
# 用法: powershell -ExecutionPolicy Bypass -File tools\start-rag.ps1 [-Down]
param(
    # 加 -Down 表示停止并移除容器（数据卷保留，索引不丢）
    [switch]$Down
)
$ErrorActionPreference = 'Stop'

# 含中文输出的脚本必须存为带 BOM 的 UTF-8（PS 5.1 兼容）
$repoRoot = Split-Path -Parent $PSScriptRoot

function Test-WindowsDocker {
    try {
        $null = & docker info 2>$null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

function Test-WslDocker {
    try {
        & wsl -d Ubuntu -- docker info *> $null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

function ConvertTo-WslPath([string]$winPath) {
    # Windows 路径 → WSL /mnt 路径：C:\Users\xxx → /mnt/c/Users/xxx
    # 注意：不能用 wslpath 转换——PS 5.1 调 wsl.exe 传参会吃掉反斜杠，
    # 含中文/反斜杠的路径会变成 "C:Usersadmin..." 乱码（实测坑）。
    # 纯 PowerShell 字符串替换最可靠，中文目录也安全。
    $full = (Resolve-Path -LiteralPath $winPath).Path
    $drive = $full.Substring(0, 1).ToLower()
    $rest = $full.Substring(2) -replace '\\', '/'
    return "/mnt/$drive$rest"
}

$action = if ($Down) { 'down' } else { 'up -d' }
# down 不加 -v：保留命名卷 hermes_pgdata（索引数据），彻底清库用 down -v

# ---------- 端口冲突预检（Windows 侧；WSL2 docker 的端口最终由 wslrelay 映射到 Windows）----------
# 典型场景：本机已有其他项目（如 Spring Boot 版 Hermes）的 postgres 容器占用 5432
function Test-PortAvailable([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -eq $conn)
}

# 读取 .env 里的 PG_PORT（若存在），与 compose 默认值 5432 对齐
$pgPort = 5432
$envFile = Join-Path $repoRoot '.env'
if (Test-Path $envFile) {
    $m = Select-String -Path $envFile -Pattern '^\s*PG_PORT\s*=\s*(\d+)' | Select-Object -First 1
    if ($m) { $pgPort = [int]$m.Matches[0].Groups[1].Value }
}

if (-not $Down -and -not (Test-PortAvailable $pgPort)) {
    # 先分清占用者：如果是本项目自己的 hermes-pgvector 已在跑 → 幂等，直接成功
    $selfRunning = ''
    try {
        $selfRunning = & {
            if (Test-WindowsDocker) { docker ps --filter name=hermes-pgvector --format '{{.Status}}' 2>$null }
            elseif (Test-WslDocker) { & wsl -d Ubuntu -- docker ps --filter name=hermes-pgvector --format '{{.Status}}' 2>$null }
        } | Out-String
    } catch { }
    if ($selfRunning.Trim()) {
        Write-Host "[OK] 本项目 RAG 容器已在运行（$($selfRunning.Trim())），无需重复启动" -ForegroundColor Green
        exit 0
    }

    # 看占用者是不是容器，给出精准建议
    $hint = ''
    try {
        $users = & {
            if (Test-WindowsDocker) { docker ps --format '{{.Names}} {{.Ports}}' 2>$null }
            elseif (Test-WslDocker) { & wsl -d Ubuntu -- docker ps --format '{{.Names}} {{.Ports}}' 2>$null }
        }
        $hit = $users | Where-Object { $_ -match ":$pgPort->" } | Select-Object -First 1
        if ($hit) { $hint = "占用者似乎是容器: $hit" }
    } catch { }

    Write-Host @"
[错误] 端口 $pgPort 已被占用，无法启动本项目容器。
$hint
两种解决方式（二选一）：
  A. 复用已有的 PostgreSQL（若它就是 pgvector 且账号兼容）：
     确认 ~/.jhermes/config.yaml 的 rag.pgvector url/username/password
     与已有库一致即可，无需启动本 compose。
  B. 换端口跑本项目：在仓库根目录建 .env 写入一行  PG_PORT=5433
     并把 config.yaml 的 rag.pgvector.url 改为 jdbc:postgresql://localhost:5433/hermes_rag
     然后重跑本脚本。
"@ -ForegroundColor Red
    exit 1
}

if (Test-WindowsDocker) {
    Write-Host "[docker] 使用 Windows 本机 Docker" -ForegroundColor Cyan
    Push-Location $repoRoot
    try { Invoke-Expression "docker compose $action" } finally { Pop-Location }
}
elseif (Test-WslDocker) {
    Write-Host "[docker] Windows 无 Docker，回退到 WSL Ubuntu" -ForegroundColor Yellow
    $wslPath = ConvertTo-WslPath $repoRoot
    & wsl -d Ubuntu -- bash -lc "cd '$wslPath' && docker compose $action"
}
else {
    Write-Host @"
未检测到可用的 Docker 环境。二选一安装：
  A. Docker Desktop for Windows: https://www.docker.com/products/docker-desktop/
     （Settings → Resources → WSL Integration 勾选 Ubuntu）
  B. WSL Ubuntu 内直接装: https://docs.docker.com/engine/install/ubuntu/
安装后重跑本脚本即可。
"@ -ForegroundColor Red
    exit 1
}

if (-not $Down) {
    Write-Host ''
    Write-Host '等待 pgvector 就绪...' -ForegroundColor Cyan
    $ok = $false
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 2
        $probe = & {
            if (Test-WindowsDocker) {
                docker inspect --format '{{.State.Health.Status}}' hermes-pgvector 2>$null
            } else {
                (& wsl -d Ubuntu -- docker inspect --format '{{.State.Health.Status}}' hermes-pgvector 2>$null)
            }
        }
        if ("$probe".Trim() -eq 'healthy') { $ok = $true; break }
    }
    if ($ok) {
        Write-Host '[OK] RAG 数据库已就绪 (localhost:5432 / hermes_rag)' -ForegroundColor Green
        Write-Host '     现在直接运行 Jhermes，启动日志不会再出现 PgVectorStore WARN。' -ForegroundColor Green
    } else {
        Write-Host '[WARN] 60 秒内未达到 healthy，请手动检查: docker logs hermes-pgvector' -ForegroundColor Yellow
        exit 1
    }
} else {
    Write-Host '[OK] 容器已停止（索引数据保留在 hermes_pgdata 卷中）' -ForegroundColor Green
}
