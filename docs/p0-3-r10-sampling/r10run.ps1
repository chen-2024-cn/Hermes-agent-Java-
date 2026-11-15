# R10 sampling runner: pipes each question into the CLI, captures full stdout/stderr.
# Pure ASCII on purpose (PS 5.1 parses non-BOM UTF-8 scripts as GBK) -- questions live in
# an external UTF-8 data file and are read with -Encoding UTF8.
#
# Usage:
#   ps1 -Jar <path> -WorkDir <path> -OutDir <path> -Label <legacy|current> -Questions <path>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$Label,
    [Parameter(Mandatory = $true)][string]$Questions
)

# stdin pipes to native processes must be UTF-8 (JVM side reads System.in as UTF-8)
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$ErrorActionPreference = 'Continue'

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$cases = Get-Content -LiteralPath $Questions -Encoding UTF8 | Where-Object { $_.Trim() -ne '' }
$summary = Join-Path $OutDir "_summary.txt"
Add-Content -LiteralPath $summary -Value ("=== {0} @ {1} ===" -f $Label, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

$index = 0
foreach ($q in $cases) {
    $index++
    $caseId = "{0}-{1:d2}" -f $Label, $index
    $outFile = Join-Path $OutDir ($caseId + ".txt")
    Add-Content -LiteralPath $summary -Value ("--- {0} ---" -f $caseId)
    Add-Content -LiteralPath $summary -Value ("Q: {0}" -f $q)

    Write-Host ("[{0}] running case {1} ..." -f $Label, $index)
    Push-Location $WorkDir
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # One question, then EOF closes the REPL (same path as the earlier smoke tests)
    $q | java "-Dstdin.encoding=UTF-8" -jar $Jar chat *> $outFile
    $exit = $LASTEXITCODE
    $sw.Stop()
    Pop-Location

    $text = Get-Content -LiteralPath $outFile -Encoding UTF8 -Raw
    $tokens = ($text -split "`n" | Where-Object { $_ -match 'tokens' } | Select-Object -First 1)
    Add-Content -LiteralPath $summary -Value ("exit={0} secs={1:n1} bytes={2}" -f $exit, $sw.Elapsed.TotalSeconds, $text.Length)
    Add-Content -LiteralPath $summary -Value ("tokens: {0}" -f $tokens)
}
Write-Host ("[{0}] done, summary at {1}" -f $Label, $summary)
