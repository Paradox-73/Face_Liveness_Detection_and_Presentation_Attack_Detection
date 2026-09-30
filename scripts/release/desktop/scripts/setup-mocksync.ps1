<#
.SYNOPSIS
Connects this desktop Registration Client package to a running MockSync (the
local stand-in for the MOSIP server). Run it once after MockSync has started,
and again whenever MockSync's work\ folder is deleted.

.DESCRIPTION
Two steps, both repeatable:
 1. Trust: downloads MockSync's HTTPS certificate from its admin API and imports
    it into this package's Java runtime (jre\lib\security\cacerts, alias
    'mocksync'). The client calls the server only over HTTPS.
 2. Machine registration: reads this computer's machine keys with the client's
    own key library (tools\machine-key-tool.jar; TPM keys when the computer has
    a TPM 2.0 chip, else local keys in .mosipkeys\) and registers them with
    MockSync. MockSync encrypts the first-login response to these keys, so this
    must happen before the first login.

.PARAMETER AdminUrl
MockSync admin address (plain HTTP, loopback only). Default: http://127.0.0.1:8080.

.PARAMETER SkipTrust
Only register the machine.

.PARAMETER SkipRegister
Only import the certificate.
#>
[CmdletBinding()]
param(
	[string]$AdminUrl = 'http://127.0.0.1:8080',
	[switch]$SkipTrust,
	[switch]$SkipRegister
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $root 'jre\bin\java.exe'
$keytool = Join-Path $root 'jre\bin\keytool.exe'
$cacerts = Join-Path $root 'jre\lib\security\cacerts'

try {
	[void](Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "$AdminUrl/admin/machines")
} catch {
	throw "MockSync does not answer at $AdminUrl. Start it first (start-mocksync.bat in the MockSync package)."
}

if (-not $SkipTrust) {
	$certDir = Join-Path $root 'certs'
	New-Item -ItemType Directory -Force -Path $certDir | Out-Null
	$cert = Join-Path $certDir 'mocksync-tls.pem'
	Invoke-WebRequest -UseBasicParsing -Uri "$AdminUrl/admin/tls-certificate" -OutFile $cert
	# keytool prints warnings on stderr, which PowerShell 5.1 treats as errors under 'Stop'.
	$ErrorActionPreference = 'Continue'
	& $keytool -delete -alias mocksync -keystore $cacerts -storepass changeit 2>&1 | Out-Null
	$out = & $keytool -importcert -noprompt -alias mocksync -file $cert -keystore $cacerts -storepass changeit 2>&1
	$code = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
	if ($code -ne 0) { throw "keytool could not import ${cert}: $out" }
	Write-Host "Trusted MockSync HTTPS certificate ($cert) in $cacerts"
}

if (-not $SkipRegister) {
	$tmp = [System.IO.Path]::GetTempFileName()
	Push-Location $root
	try {
		$ErrorActionPreference = 'Continue'
		& $java -cp 'lib\*;tools\machine-key-tool.jar' MachineKeyTool $tmp 2>&1 | Out-Null
		$code = $LASTEXITCODE
		$ErrorActionPreference = 'Stop'
		if ($code -ne 0) { throw 'Reading the machine keys failed (tools\machine-key-tool.jar).' }
	} finally {
		Pop-Location
	}
	$body = Get-Content -Raw $tmp
	Remove-Item $tmp
	$machine = Invoke-RestMethod -Method Post -Uri "$AdminUrl/admin/machines" -ContentType 'application/json' -Body $body
	Write-Host ("Registered machine '{0}' (machine id {1}, centre {2}, key type {3})" -f `
		$machine.name, $machine.machineId, $machine.centerId, $machine.clientType)
}
