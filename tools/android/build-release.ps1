#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Keystore,
    [Parameter(Mandatory = $true)][string]$PasswordFile,
    [string]$KeyAlias = 'release'
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This helper requires Windows and PowerShell 7.' }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$Keystore = (Resolve-Path -LiteralPath $Keystore).Path
$PasswordFile = (Resolve-Path -LiteralPath $PasswordFile).Path
$tools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
if (-not (Test-Path $zipalign) -or -not (Test-Path $apksigner)) { throw 'Install Android Build Tools 36.0.0.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$aligned = $null
$previousPassword = $env:OFFLINE_TRANSLATOR_RELEASE_PASSWORD
Push-Location $root
try {
    & .\gradlew.bat :engine-sherpa:prepareSherpaArchive :app:assembleRelease :app:lintRelease --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build or lint failed.' }
    $directory = Join-Path $root 'app/build/outputs/apk/release'
    $metadata = Get-Content -Raw (Join-Path $directory 'output-metadata.json') | ConvertFrom-Json
    if ($metadata.elements.Count -ne 1) { throw 'Expected one universal release APK.' }
    $artifact = $metadata.elements[0]
    $unsigned = Join-Path $directory $artifact.outputFile
    $aligned = Join-Path $directory 'release-aligned.apk'
    $signed = Join-Path $directory "offline-translator-$($artifact.versionName).apk"
    & $zipalign -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    $env:OFFLINE_TRANSLATOR_RELEASE_PASSWORD = [System.IO.File]::ReadAllText($PasswordFile).TrimEnd("`r", "`n")
    & $apksigner sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass env:OFFLINE_TRANSLATOR_RELEASE_PASSWORD --key-pass env:OFFLINE_TRANSLATOR_RELEASE_PASSWORD --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $apksigner verify --verbose --print-certs $signed
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & $zipalign -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $signed).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$signed.sha256", "$hash  $([System.IO.Path]::GetFileName($signed))`n", [System.Text.UTF8Encoding]::new($false))
    Write-Output "Signed release APK: $signed"
    Write-Output "SHA-256: $hash"
} finally {
    $env:OFFLINE_TRANSLATOR_RELEASE_PASSWORD = $previousPassword
    if ($aligned -and (Test-Path -LiteralPath $aligned)) { Remove-Item -LiteralPath $aligned }
    Pop-Location
}
