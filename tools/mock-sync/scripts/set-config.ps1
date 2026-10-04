# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# Change config values served by a running MockSync. Clients receive them on their next config sync
# (desktop: Sync Data from the menu, or the scheduled sync job).
#
# usage: .\scripts\set-config.ps1 -Set @{ 'mosip.registration.liveness.passive.threshold' = '85' }
#        .\scripts\set-config.ps1 -Unset 'mosip.registration.liveness.passive.threshold'
#        .\scripts\set-config.ps1                  (lists overrides and effective liveness keys)
param(
	[hashtable]$Set,
	[string[]]$Unset,
	[string]$AdminUrl = 'http://127.0.0.1:8080'
)
$ErrorActionPreference = 'Stop'
if ($Set) {
	Invoke-RestMethod -Method Put -Uri "$AdminUrl/admin/config" -ContentType 'application/json' -Body ($Set | ConvertTo-Json)
} elseif ($Unset) {
	foreach ($key in $Unset) { Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$AdminUrl/admin/config/$key" | Out-Null; "$key removed" }
} else {
	Invoke-RestMethod -Uri "$AdminUrl/admin/config" | ConvertTo-Json -Depth 4
}
