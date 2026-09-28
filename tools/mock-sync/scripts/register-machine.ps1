# Register a desktop Registration Client machine in a running MockSync.
#
# usage: .\scripts\register-machine.ps1 -ClientDir <client dir> [-LibDir <client jar dir>] [-AdminUrl http://127.0.0.1:8080]
#   ClientDir  the client's working directory (where .mosipkeys\ and db\ are created on first launch)
#   LibDir     the client's jar directory; default <ClientDir>\lib
#
# With the client's jars available, MachineKeyTool.java asks keymanager for the machine keys (TPM or local, the
# same choice the client makes). Without them, .mosipkeys\readme.txt is used, which exists only for local keys.
param(
	[Parameter(Mandatory = $true)][string]$ClientDir,
	[string]$LibDir,
	[string]$AdminUrl = 'http://127.0.0.1:8080'
)
$ErrorActionPreference = 'Stop'
if (-not $LibDir) { $LibDir = Join-Path $ClientDir 'lib' }
$tool = Join-Path $PSScriptRoot 'MachineKeyTool.java'
$readme = Join-Path $ClientDir '.mosipkeys\readme.txt'

if (Get-ChildItem -Path $LibDir -Filter *.jar -ErrorAction SilentlyContinue | Select-Object -First 1) {
	$out = [System.IO.Path]::GetTempFileName()
	Push-Location $ClientDir
	try {
		& java -cp "$LibDir\*" $tool $out | Out-Null
		if ($LASTEXITCODE -ne 0) { throw 'MachineKeyTool failed' }
	} finally { Pop-Location }
	$body = Get-Content -Raw $out
	Remove-Item $out
	Write-Output "Machine keys: $body"
	Invoke-RestMethod -Method Post -Uri "$AdminUrl/admin/machines" -ContentType 'application/json' -Body $body
} elseif (Test-Path $readme) {
	Invoke-RestMethod -Method Post -Uri "$AdminUrl/admin/machines/readme" -ContentType 'text/plain' -Body (Get-Content -Raw $readme)
} else {
	throw "No client jars in $LibDir and no $readme; launch the client once first."
}
