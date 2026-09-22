# release.ps1 — 一键发布 hermes.jar 到 GitHub Release（模式 B 的"上传端"）
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File tools\release.ps1            # 版本号取 npm-package\package.json
#   powershell -ExecutionPolicy Bypass -File tools\release.ps1 -Version 1.1.0
#
# 做的事:
#   1. 校验 gh 已登录、jar 已构建（缺失则自动 mvn package）
#   2. gh release create v<Version>（已存在则跳过创建）
#   3. 上传 hermes.jar 资产（--clobber 同名覆盖，可重复执行）
#
# 注意: 资产名固定为 hermes.jar —— 必须与 npm-package/package.json 里
#       hermes.jarUrl 的结尾一致，否则 postinstall 下载 404。
param(
    [string]$Version = ""
)
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$jarPath  = Join-Path $repoRoot 'sk-hermes\target\sk-hermes-1.0-SNAPSHOT.jar'
$pkgJson  = Join-Path $repoRoot 'npm-package\package.json'

# ---------- 0. 读取版本号 ----------
if (-not $Version) {
    $Version = (Get-Content $pkgJson -Raw | ConvertFrom-Json).version
}
$tag = "v$Version"
Write-Host "[release] 版本: $tag" -ForegroundColor Cyan

# ---------- 1. 前置检查 ----------
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) { throw "未安装 GitHub CLI (gh)。安装: winget install GitHub.cli，然后 gh auth login" }

# PS 5.1 的坑：$ErrorActionPreference='Stop' 时，native 命令的 stderr 被重定向(2>$null/2>&1)
# 会被包装成终止错误抛出（即使只是 `gh release view` 探测不存在）。故探测类调用必须临时切回 Continue。
function Invoke-GhProbe {
    param([scriptblock]$Cmd)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Cmd
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $prev
    }
}

$authed = Invoke-GhProbe { gh auth status 2>&1 | Out-Null }
if (-not $authed) { throw "gh 未登录，请先执行: gh auth login" }

# jar 不存在或比源码旧 → 自动构建（优先 PATH 里的 mvn，其次 .m2 wrapper）
if (-not (Test-Path $jarPath)) {
    Write-Host "[release] 未找到 jar，开始构建..." -ForegroundColor Yellow
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        & mvn -f (Join-Path $repoRoot 'sk-hermes\pom.xml') clean package -DskipTests -q
    } else {
        $wrapperMvn = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $wrapperMvn) { throw "找不到 mvn 也找不到 wrapper 里的 mvn.cmd，请先手动构建 jar" }
        & $wrapperMvn.FullName -f (Join-Path $repoRoot 'sk-hermes\pom.xml') clean package -DskipTests -q
    }
    if (-not (Test-Path $jarPath)) { throw "构建后仍未找到 $jarPath" }
}
Write-Host "[release] jar 就绪: $jarPath ($([math]::Round((Get-Item $jarPath).Length/1MB,1)) MB)" -ForegroundColor Green

# ---------- 2. 创建 Release（幂等：已存在则复用） ----------
$releaseExists = Invoke-GhProbe { gh release view $tag 2>&1 | Out-Null }
if ($releaseExists) {
    Write-Host "[release] Release $tag 已存在，跳过创建" -ForegroundColor Yellow
} else {
    gh release create $tag `
        --title "sk-Hermes $tag" `
        --notes "hermes.jar for npm distribution (sk-hermes-cli). Download by postinstall automatically." `
        --target master
    if ($LASTEXITCODE -ne 0) { throw "gh release create 失败" }
    Write-Host "[release] 已创建 $tag" -ForegroundColor Green
}

# ---------- 3. 上传资产 ----------
# 坑：gh release upload 的 "path#name" 语法里 #name 只是 Web UI 显示标签(label)，
# 资产真实文件名始终 = 源文件的 basename（实测 gh 2.97）。而 postinstall 下载地址
# 尾段必须是 hermes.jar，因此先把构建产物复制成物理文件名 hermes.jar 再上传。
$stagedJar = Join-Path $env:TEMP 'hermes.jar'
Copy-Item $jarPath $stagedJar -Force
Write-Host "[release] 上传中（$([math]::Round((Get-Item $stagedJar).Length/1MB,1)) MB，大文件视网速需数分钟）..." -ForegroundColor Yellow
gh release upload $tag $stagedJar --clobber
if ($LASTEXITCODE -ne 0) {
    Remove-Item $stagedJar -Force -ErrorAction SilentlyContinue
    throw "gh release upload 失败（大文件易超时，可直接重跑本脚本，幂等）"
}
Remove-Item $stagedJar -Force -ErrorAction SilentlyContinue

# ---------- 4. 核验资产真实落地（大文件上传可能静默失败，必须复查） ----------
$assetNames = gh release view $tag --json assets --jq ".assets[].name"
if ($assetNames -notcontains 'hermes.jar') {
    throw "上传后核验失败：Release $tag 的资产里没有 hermes.jar（实际: $assetNames）。请重跑本脚本。"
}

$url = "https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/$tag/hermes.jar"
Write-Host ""
Write-Host "[release] 完成! 资产 hermes.jar 已核验存在。下载地址:" -ForegroundColor Cyan
Write-Host "  $url"
Write-Host "验证: curl.exe -sIL `"$url`" | Select-Object -First 1"
Write-Host ""
Write-Host "下一步: 若 package.json 的 hermes.jarUrl 不是这个地址，请同步修改后再 npm publish" -ForegroundColor Yellow
