# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
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
