[CmdletBinding()]
param(
    [string]$Distribution = "Ubuntu",
    [string]$SourceRepository,
    [string]$InstalledTools,
    [string]$Cache,
    [switch]$VerifyOnly
)
$ErrorActionPreference = "Stop"

function Convert-ToWslPath([string]$Path) {
    if ($Path.StartsWith('/')) { return $Path }
    $absolute = (Resolve-Path -LiteralPath $Path).Path.Replace('\', '/')
    $translated = & wsl.exe -d $Distribution -- wslpath -a -u $absolute
    if ($LASTEXITCODE -ne 0) { throw "Cannot translate path through existing WSL $Distribution`: $absolute" }
    return $translated.Trim()
}

$scriptPath = Convert-ToWslPath (Join-Path $PSScriptRoot "prepare-mozilla-native.py")
$arguments = @($scriptPath)
if ($VerifyOnly) { $arguments += "--verify-only" }
if ($SourceRepository) { $arguments += @("--source-repository", (Convert-ToWslPath $SourceRepository)) }
if ($InstalledTools) { $arguments += @("--installed-tools", (Convert-ToWslPath $InstalledTools)) }
if ($Cache) { $arguments += @("--cache", (Convert-ToWslPath $Cache)) }
& wsl.exe -d $Distribution -- python3 @arguments
if ($LASTEXITCODE -ne 0) { throw "Mozilla native preparation failed with exit code $LASTEXITCODE" }
