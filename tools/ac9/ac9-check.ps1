# AC9 layer-1 verification (runs INSIDE Windows Sandbox)
# Usage:  cd C:\AC9 ; .\ac9-check.ps1
#
# Layer 1 calls "Jhermes security check --file": it runs the FULL CommandPolicy
# pipeline (normalize -> segment -> classify -> decide) for each command and prints
# level / matched rules / reason / decision / audit id.
# It EXECUTES NOTHING and costs ZERO tokens, so it is safe to run anywhere.
#
# Commands are read from a file (not passed as CLI args) on purpose: several cases
# contain double quotes and pipes (e.g. grep -r "rm -rf /" .), which PowerShell 5.1
# mangles when handing them to a native executable.

$ErrorActionPreference = "Stop"
$AC9 = "C:\AC9"
$jar = Join-Path $AC9 "Jhermes.jar"
$cmdFile = Join-Path $AC9 "ac9-commands.txt"
$report = Join-Path $AC9 "AC9-REPORT.txt"
$javaPathFile = Join-Path $AC9 "JAVA_PATH.txt"

function Die($msg) { Write-Host "[X] $msg" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $jar)) { Die "Jhermes.jar missing in $AC9" }
if (-not (Test-Path $cmdFile)) { Die "ac9-commands.txt missing in $AC9 (tutorial section 3.1)" }

# resolve java: prefer the path recorded by prepare-sandbox.ps1, fall back to PATH
$javaCmd = "java"
if (Test-Path $javaPathFile) {
    $recorded = (Get-Content $javaPathFile -Raw).Trim()
    if ($recorded -and (Test-Path $recorded)) { $javaCmd = $recorded }
} else {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Die "java not found. Run .\prepare-sandbox.ps1 first."
    }
}

Write-Host ""
Write-Host "=== AC9 layer 1: command policy verification ===" -ForegroundColor Cyan
Write-Host "java    : $javaCmd"
Write-Host "jar     : $jar"
Write-Host "commands: $cmdFile"
Write-Host "report  : $report"
Write-Host ""

# force UTF-8 for the JVM's stdout so Chinese reasons are not mangled on cp936 consoles
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

$lines = & $javaCmd -jar $jar security check --file $cmdFile 2>&1 | Out-String
$exit = $LASTEXITCODE

# write report with BOM so that Notepad on Windows shows Chinese correctly
$utf8bom = New-Object System.Text.UTF8Encoding $true
[System.IO.File]::WriteAllText($report, $lines, $utf8bom)

Write-Host $lines

Write-Host ""
if ($exit -eq 0) {
    Write-Host "[OK] ALL CASES MATCHED EXPECTATIONS" -ForegroundColor Green
} else {
    Write-Host "[X] SOME CASES DID NOT MATCH (exit code $exit) - paste the report back!" -ForegroundColor Red
}
Write-Host "Report written to: $report" -ForegroundColor Cyan
Write-Host "(Copy it back with: Get-Content `"$env:USERPROFILE\Desktop\JhermesAC9\AC9-REPORT.txt`")" -ForegroundColor DarkGray

exit $exit
