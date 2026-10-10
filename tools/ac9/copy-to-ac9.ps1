# Copy the freshly built jar + verification scripts into the AC9 work folder (runs on YOUR machine, not in the sandbox)
# Usage:  .\tools\ac9\copy-to-ac9.ps1
# After running this, start the sandbox per docs/ac9-manual-verification.md section 4.

$ErrorActionPreference = "Stop"

$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
if (-not (Test-Path (Join-Path $repo "Jhermes"))) {
    $repo = "C:\Users\admin\Desktop\个性工具\Hermes-agent-Java-"
}

$work = Join-Path $env:USERPROFILE "Desktop\JhermesAC9"
New-Item -ItemType Directory -Path $work -Force | Out-Null

# 1. locate the newest built jar
$jars = Get-ChildItem (Join-Path $repo "Jhermes\target") -Filter "Jhermes-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "original-*" } | Sort-Object LastWriteTime -Descending
if (-not $jars) {
    Write-Host "[X] No jar found. Build it first:" -ForegroundColor Red
    Write-Host "    cd $repo\Jhermes ; .\mvnw.cmd package -DskipTests" -ForegroundColor Yellow
    exit 1
}
$jar = $jars[0]
Copy-Item $jar.FullName (Join-Path $work "Jhermes.jar") -Force
Write-Host "[OK] jar  : $($jar.Name) ($([math]::Round($jar.Length/1MB,1)) MB, built $($jar.LastWriteTime))" -ForegroundColor Green

# 2. copy the verification assets
foreach ($f in @("prepare-sandbox.ps1", "ac9-check.ps1", "config-template.yaml", "ac9-commands.txt")) {
    $src = Join-Path $repo "tools\ac9\$f"
    if (Test-Path $src) {
        Copy-Item $src $work -Force
        Write-Host "[OK] asset: $f" -ForegroundColor Green
    } else {
        Write-Host "[!] missing asset: $f" -ForegroundColor Yellow
    }
}

# 3. remind about the JRE payload
$jre = Join-Path $work "jre21.zip"
if (Test-Path $jre) {
    Write-Host "[OK] jre  : jre21.zip ($([math]::Round((Get-Item $jre).Length/1MB,1)) MB)" -ForegroundColor Green
} else {
    Write-Host "[!] jre21.zip NOT present - download it now (tutorial section 3.2):" -ForegroundColor Yellow
    Write-Host "    Invoke-WebRequest -Uri ""https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"" -OutFile ""$jre""" -ForegroundColor White
}

Write-Host ""
Write-Host "Work folder ready: $work" -ForegroundColor Cyan
Get-ChildItem $work | Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB)}} | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "Now start the sandbox (tutorial section 4.1)." -ForegroundColor Cyan
