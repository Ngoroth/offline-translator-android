[CmdletBinding()]
param(
    [string]$Distribution = "Ubuntu",
    [string]$SourceRepository,
    [string]$NdkArchive,
    [switch]$VerifyOnly
)
$ErrorActionPreference = "Stop"

function Convert-ToWslPath([string]$Path) {
    $absolute = (Resolve-Path -LiteralPath $Path).Path.Replace('\', '/')
    $translated = & wsl.exe -d $Distribution -- wslpath -a -u $absolute
    if ($LASTEXITCODE -ne 0) { throw "Cannot translate path through existing WSL $Distribution`: $absolute" }
    return $translated.Trim()
}

$scriptPath = Convert-ToWslPath (Join-Path $PSScriptRoot "prepare-tencent-native.py")
$arguments = @($scriptPath)
if ($VerifyOnly) { $arguments += "--verify-only" }
if ($SourceRepository) { $arguments += @("--source-repository", (Convert-ToWslPath $SourceRepository)) }
if ($NdkArchive) { $arguments += @("--ndk-archive", (Convert-ToWslPath $NdkArchive)) }
& wsl.exe -d $Distribution -- python3 @arguments
if ($LASTEXITCODE -ne 0) { throw "Tencent native preparation failed with exit code $LASTEXITCODE" }
