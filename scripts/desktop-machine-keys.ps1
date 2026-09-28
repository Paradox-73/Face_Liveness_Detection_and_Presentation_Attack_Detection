<#
.SYNOPSIS
Prints the desktop Registration Client's machine name, public keys and key index.

.DESCRIPTION
A server must know these values before the client's first login (machine
registration). The client writes them to .mosipkeys\readme.txt only when it uses
local software keys; on a machine with a TPM 2.0 it uses TPM keys and writes no
readme. This script runs scripts\DesktopMachineKeys.java against the run layout's
lib\ folder and prints the same keys the client uses.

Needs a JDK 11 (source-file mode needs the compiler, which the bundled JRE lacks):
$env:JAVA11_HOME when it contains javac.exe, otherwise the Zulu JDK that
scripts/desktop-build.sh downloads into <run root>\cache.

.PARAMETER RunDir
Run layout folder. Default: $env:MOSIP_RUN_ROOT\desktop, or <repo>\..\run\desktop.

.PARAMETER OutFile
Also writes the output to this file.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\desktop-machine-keys.ps1 -OutFile E:\Projects\MOSIP\run\desktop\machine-keys.txt
#>
[CmdletBinding()]
param(
	[string]$RunDir,
	[string]$OutFile
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runRoot = if ($env:MOSIP_RUN_ROOT) { $env:MOSIP_RUN_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) 'run' }
if (-not $RunDir) { $RunDir = Join-Path $runRoot 'desktop' }
$RunDir = [System.IO.Path]::GetFullPath($RunDir)
if (-not (Test-Path (Join-Path $RunDir 'lib'))) {
	throw "No run layout in $RunDir. Run scripts/desktop-build.sh first."
}

$jdk = $null
foreach ($candidate in @($env:JAVA11_HOME, (Join-Path $runRoot 'cache\zulu11.41.23-ca-fx-jdk11.0.8-win_x64'))) {
	if ($candidate -and (Test-Path (Join-Path $candidate 'bin\javac.exe'))) { $jdk = $candidate; break }
}
if (-not $jdk) { throw 'No JDK 11 with javac found. Set JAVA11_HOME or run scripts/desktop-build.sh once.' }

$source = Join-Path $PSScriptRoot 'DesktopMachineKeys.java'
$logConfig = Join-Path $RunDir 'lib\logback.xml'
Push-Location $RunDir
try {
	# Library log lines go to logs\registration.log (lib\logback.xml), not to this output.
	$ErrorActionPreference = 'Continue'
	$output = & (Join-Path $jdk 'bin\java.exe') "-Dlogback.configurationFile=$logConfig" -cp 'lib/*' $source 2>&1
	$exitCode = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
} finally {
	Pop-Location
}
if ($exitCode -ne 0) {
	$all = ($output | ForEach-Object { "$_" }) -join [Environment]::NewLine
	throw "DesktopMachineKeys failed (exit $exitCode):$([Environment]::NewLine)$all"
}
# Keep stdout only; stderr carries SLF4J version notices from the client's jars.
$text = ($output | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } |
	ForEach-Object { "$_" }) -join [Environment]::NewLine
Write-Output $text
if ($OutFile) {
	# UTF-8 without a byte-order mark, so other tools can parse the file as plain text.
	[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($OutFile), $text + [Environment]::NewLine,
		(New-Object System.Text.UTF8Encoding($false)))
	Write-Host "Saved to $OutFile"
}
