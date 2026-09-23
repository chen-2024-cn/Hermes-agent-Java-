# install-hermes.ps1
# 一键把 sk-hermes 安装为全局命令 `Jhermes`，并注册文件夹右键菜单「在此处使用 Jhermes 聊天」
# 用法: powershell -ExecutionPolicy Bypass -File install-hermes.ps1
$ErrorActionPreference = 'Stop'

$repoRoot  = Split-Path -Parent $PSScriptRoot          # 项目根目录
$jarSrc    = Join-Path $repoRoot 'sk-hermes\target\sk-hermes-1.0-SNAPSHOT.jar'
$launcherSrc = Join-Path $repoRoot 'tools\Jhermes.cmd'
$installDir = Join-Path $env:USERPROFILE '.hermes'      # C:\Users\<you>\.hermes
$jarDst     = Join-Path $installDir 'hermes.jar'
$cmdDst     = Join-Path $installDir 'Jhermes.cmd'

# ---------- 0. 前置检查 ----------
if (-not (Test-Path $jarSrc))      { throw "找不到 $jarSrc，请先执行 mvn clean package -DskipTests" }
if (-not (Test-Path $launcherSrc)) { throw "找不到启动器 $launcherSrc" }

# ---------- 1. 复制 jar + launcher ----------
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
Copy-Item $jarSrc     $jarDst -Force
Copy-Item $launcherSrc $cmdDst -Force
# 清理旧命令名时代遗留的 hermes.cmd（命令已改名为 Jhermes，避免双命令共存困惑）
Remove-Item (Join-Path $installDir 'hermes.cmd') -Force -ErrorAction SilentlyContinue
Write-Host "[1/3] 已安装到 $installDir" -ForegroundColor Green

# ---------- 2. 追加用户 PATH（只改当前用户的，无需管理员） ----------
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $installDir), 'User')
    Write-Host "[2/3] 已把 $installDir 加入用户 PATH（新开终端生效）" -ForegroundColor Green
} else {
    Write-Host "[2/3] PATH 中已存在 $installDir，跳过" -ForegroundColor Yellow
}

# ---------- 3. 注册文件夹右键菜单 ----------
# HKCU\Software\Classes\Directory\Background\shell → 在文件夹「空白处」右键时出现
$keyPath = 'HKCU:\Software\Classes\Directory\Background\shell\HermesChat'
New-Item -Path $keyPath -Force | Out-Null
Set-ItemProperty -Path $keyPath -Name '(default)' -Value '在此处使用 Jhermes 聊天'
New-Item -Path "$keyPath\command" -Force | Out-Null
# %V = 当前文件夹路径（资源管理器背景右键专用变量）
Set-ItemProperty -Path "$keyPath\command" -Name '(default)' `
    -Value ('cmd /k cd /d "%V" && Jhermes')
Write-Host "[3/3] 已注册文件夹右键菜单「在此处使用 Jhermes 聊天」" -ForegroundColor Green

Write-Host ''
Write-Host '完成！新开一个终端，效果如下：' -ForegroundColor Cyan
Write-Host '  cd <任意项目文件夹>'
Write-Host '  Jhermes          # 直接进入对话（模型自动以当前目录为项目根）'
Write-Host '  Jhermes --help   # 查看全部子命令'
