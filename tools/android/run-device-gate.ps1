#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Adb,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Device,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CaseList,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$runner = 'com.example.offlinetranslator.test/androidx.test.runner.AndroidJUnitRunner'
$proof = New-Item -ItemType Directory -Path $EvidenceDir -Force
$logPath = Join-Path $proof.FullName 'device-gate-full.log'
$receiptPath = Join-Path $proof.FullName 'device-gate-receipt.json'
if ((Test-Path -LiteralPath $logPath) -or (Test-Path -LiteralPath $receiptPath)) {
    throw 'Evidence already exists. Select another EvidenceDir; previous results will not be overwritten.'
}
$null = New-Item -ItemType File -Path $logPath
$started = Get-Date
$receipt = [ordered]@{
    status = 'running'
    startedUtc = $started.ToUniversalTime().ToString('o')
    adb = $Adb
    device = $Device
    caseList = $CaseList
    runner = $runner
    stdoutFile = 'device-gate-full.log'
}
$receipt | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $receiptPath
$nativeExitCode = $null
$scriptExitCode = 1
try {
    & $Adb -s $Device shell am instrument -w -r -e class $CaseList $runner 2>&1 |
        Tee-Object -Variable output |
        Tee-Object -FilePath $logPath
    $nativeExitCode = $LASTEXITCODE
    $text = $output -join "`n"
    $completed = [regex]::Match($text, '(?m)^OK \(([1-9]\d*) tests?\)\s*$')
    $runnerFinished = $text -match '(?m)^INSTRUMENTATION_CODE: -1\s*$'
    $failed = $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|keyDispatchingTimedOut|INSTRUMENTATION_STATUS_CODE:\s*-[12]\b|INSTRUMENTATION_RESULT:\s*shortMsg='
    if ($nativeExitCode -eq 0 -and $completed.Success -and $runnerFinished -and -not $failed) {
        $receipt.status = 'passed'
        $receipt['tests'] = [int]$completed.Groups[1].Value
        $scriptExitCode = 0
    } else {
        $receipt.status = 'failed_or_incomplete'
        if ($nativeExitCode -ne 0) { $scriptExitCode = $nativeExitCode }
    }
} catch {
    $receipt.status = 'failed_or_incomplete'
    $receipt['error'] = $_.ToString()
    Write-Warning $_.ToString()
} finally {
    $receipt['nativeExitCode'] = $nativeExitCode
    $receipt['exitCode'] = $scriptExitCode
    $receipt['wallSeconds'] = ((Get-Date) - $started).TotalSeconds
    $receipt | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $receiptPath
    $receipt | ConvertTo-Json
}
exit $scriptExitCode
