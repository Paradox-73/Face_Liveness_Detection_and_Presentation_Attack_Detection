# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Make a Java runtime trust MockSync's HTTPS certificate (work\keys\tls-cert.pem, created on MockSync's first start).
#
# usage: .\scripts\trust-mock-sync.ps1 -TrustStore <file.p12>   trust store holding only this certificate; run the
#                                                              client with -Djavax.net.ssl.trustStore=<file.p12>
#                                                              -Djavax.net.ssl.trustStorePassword=changeit
#        .\scripts\trust-mock-sync.ps1 -Jre <java home>          import it into <java home>\lib\security\cacerts
param(
	[string]$TrustStore,
	[string]$Jre,
	[string]$WorkDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'work')
)
$ErrorActionPreference = 'Stop'
$cert = Join-Path $WorkDir 'keys\tls-cert.pem'
if (-not (Test-Path $cert)) { throw "$cert not found; start MockSync once first." }

function Find-Keytool([string]$javaHome) {
	foreach ($candidate in @((Join-Path $javaHome 'bin\keytool.exe'), (Get-Command keytool -ErrorAction SilentlyContinue).Source,
			(Join-Path "$env:JAVA_HOME" 'bin\keytool.exe'))) {
		if ($candidate -and (Test-Path $candidate)) { return $candidate }
	}
	throw 'keytool not found (the client JRE, PATH and JAVA_HOME were checked)'
}

if ($TrustStore) {
	& (Find-Keytool "$env:JAVA_HOME") -importcert -noprompt -alias mocksync -file $cert -keystore $TrustStore -storetype PKCS12 -storepass changeit
} elseif ($Jre) {
	$keytool = Find-Keytool $Jre
	$cacerts = Join-Path $Jre 'lib\security\cacerts'
	& $keytool -delete -noprompt -alias mocksync -keystore $cacerts -storepass changeit 2>$null | Out-Null
	& $keytool -importcert -noprompt -alias mocksync -file $cert -keystore $cacerts -storepass changeit
} else {
	throw 'usage: trust-mock-sync.ps1 -TrustStore <file.p12> | -Jre <java home>'
}
