# AC9 prepare script - runs INSIDE Windows Sandbox
# Usage:  cd C:\AC9 ; Set-ExecutionPolicy -Scope Process Bypass -Force ; .\prepare-sandbox.ps1
# What it does: unpack portable JRE -> set PATH -> install config.yaml -> self check.

$ErrorActionPreference = "Stop"
$AC9 = "C:\AC9"
$JreRoot = Join-Path $env:TEMP "jre21"

function Step($msg) { Write-Host "[*] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "[X] $msg" -ForegroundColor Red; exit 1 }

Step "AC9 environment prepare (Windows Sandbox)"
Write-Host "    sandbox user : $env:USERNAME"
Write-Host "    work dir     : $AC9"

# ---------- 1. verify mapped files ----------
if (-not (Test-Path $AC9)) { Die "work dir $AC9 not found - did the folder get mapped?" }
$jar = Join-Path $AC9 "Jhermes.jar"
if (-not (Test-Path $jar)) { Die "Jhermes.jar missing in $AC9 (see tutorial section 3.1)" }
$zip = Join-Path $AC9 "jre21.zip"
if (-not (Test-Path $zip)) { Die "jre21.zip missing in $AC9 (see tutorial section 3.2)" }
Ok "mapped files found: Jhermes.jar = $([math]::Round((Get-Item $jar).Length/1MB,1)) MB"

# ---------- 2. unpack portable JRE ----------
if (Test-Path $JreRoot) { Step "cleaning previous JRE unpack at $JreRoot"; Remove-Item $JreRoot -Recurse -Force }
Step "unpacking JRE (this takes ~30s)..."
$sw = [Diagnostics.Stopwatch]::StartNew()
Expand-Archive -Path $zip -DestinationPath $JreRoot -Force
$sw.Stop()
Ok "unpacked in $([int]$sw.Elapsed.TotalSeconds)s"

# locate java.exe (Temurin zip contains a jdk-21.x.x+y folder; our own Compress-Archive does not)
$javaExe = Get-ChildItem $JreRoot -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\bin\\java\.exe$" } | Select-Object -First 1
if (-not $javaExe) { Die "java.exe not found under $JreRoot" }
$javaPath = $javaExe.FullName
$javaHome = Split-Path (Split-Path $javaPath -Parent) -Parent
Set-Content -Path (Join-Path $AC9 "JAVA_PATH.txt") -Value $javaPath -Encoding ASCII
Ok "java.exe = $javaPath"

# make 'java' available in this session (script runs in-process, so caller inherits it)
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

# ---------- 3. install ~/.jhermes/config.yaml ----------
$home2 = Join-Path $env:USERPROFILE ".jhermes"
New-Item -ItemType Directory -Path $home2 -Force | Out-Null
$cfg = Join-Path $home2 "config.yaml"
$tpl = Join-Path $AC9 "config-template.yaml"
if (Test-Path $tpl) {
    Copy-Item $tpl $cfg -Force
    Ok "config installed -> $cfg (from template; RAG disabled, terminal enabled)"
} elseif (-not (Test-Path $cfg)) {
    Warn "config-template.yaml not found; Jhermes will auto-create a default config on first run"
} else {
    Ok "existing config kept -> $cfg"
}

# api_key status (never print the key itself)
if (Test-Path $cfg) {
    # Parse the api_key line without regex escaping tricks: PS single-quoted strings
    # escape a quote as '' (not \'), which is an easy source of syntax errors.
    $keyLine = ($raw = (Get-Content $cfg -Raw)) -split "`r?`n" |
        Where-Object { $_ -match '^\s*api_key:' } | Select-Object -First 1
    $keyValue = ""
    if ($keyLine) { $keyValue = ($keyLine -replace '^\s*api_key:\s*', '').Trim(' "',"'") }
    if ([string]::IsNullOrWhiteSpace($keyValue) -or $keyValue -eq 'YOUR_API_KEY_HERE') {
        Warn "api_key is empty/placeholder - layer 1 (security check) still works, layer 2 needs a real key"
    } else {
        Ok "api_key looks like it is configured"
    }
}

# ---------- 4. self checks ----------
Step "self check: java -version"
& $javaPath -version 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { Die "java -version failed" }

Step "self check: Jhermes --version"
& $javaPath -jar $jar --version 2>&1 | ForEach-Object { Write-Host "    $_" }

Step "self check: is 'security' subcommand available?"
$help = & $javaPath -jar $jar --help 2>&1 | Out-String
if ($help -match "security") {
    Ok "'security' subcommand present -> this jar is built AFTER P0-2"
} else {
    Warn "'security' subcommand NOT found -> jar was built BEFORE P0-2, re-copy it (tutorial 3.1)"
}

Write-Host ""
Ok "PREPARE OK"
Write-Host ""
Write-Host "Next step - run layer 1 verification (no token cost, executes nothing):" -ForegroundColor Cyan
Write-Host "    .\ac9-check.ps1" -ForegroundColor White
Write-Host ""
Write-Host "Then layer 2 - interactive session (needs a real api_key):" -ForegroundColor Cyan
Write-Host "    notepad `"$cfg`"    # fill in model.api_key first" -ForegroundColor White
Write-Host "    java -jar C:\AC9\Jhermes.jar" -ForegroundColor White
