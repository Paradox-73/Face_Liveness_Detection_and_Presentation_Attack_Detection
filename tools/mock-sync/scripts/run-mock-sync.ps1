# Start MockSync: HTTPS on 8443 (desktop client), HTTP on 8080 (Android debug build).
# Builds target\mock-sync.jar first when it is missing, or always with -Rebuild.
# Extra arguments go to Spring Boot, e.g.  .\scripts\run-mock-sync.ps1 --mocksync.center-id=10002
param(
	[switch]$Rebuild,
	[Parameter(ValueFromRemainingArguments = $true)][string[]]$SpringArgs
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if ($Rebuild -or -not (Test-Path 'target\mock-sync.jar')) {
	& mvn -q -B package -DskipTests
	if ($LASTEXITCODE -ne 0) { throw 'MockSync build failed' }
}
$javaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS -split ' ' } else { @('-Xmx512m') }
& java @javaOpts -jar 'target\mock-sync.jar' @SpringArgs
