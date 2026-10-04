# Copyright (c) 2026 Team Badmosip
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
<#
.SYNOPSIS
Starts or stops the desktop Registration Client from a run layout made by
scripts/desktop-build.sh.

.DESCRIPTION
Runs jre\bin\javaw.exe with the same main class and class path as the official
run.bat (io.mosip.registration.controller.Initialization, lib/*), with the run
layout folder as the working directory. The client keeps its state in that
folder: db\ (Derby database), .mosipkeys\ (machine keys and the encrypted
database password) and logs\ (registration.log).

.PARAMETER RunDir
Run layout folder. Default: $env:MOSIP_RUN_ROOT\desktop, or <repo>\..\run\desktop.

.PARAMETER TrustCert
Certificate file (PEM or DER) to import into the run layout's JRE trust store
before launch, for example the MockSync localhost certificate. The client calls
every server over HTTPS with the default JDK trust store.

.PARAMETER TrustAlias
Alias for -TrustCert in the trust store. Default: mocksync.

.PARAMETER Reset
Deletes db\, .mosipkeys\ and logs\ before launch so the client starts as a fresh
install. On a machine that uses local (non-TPM) keys this creates new machine
keys, which must then be registered again with the server.

.PARAMETER Console
Runs java.exe in the current window and waits for it to exit, instead of
javaw.exe in the background.

.PARAMETER MaxHeap
Java maximum heap. Default: 2048m (the official run.bat value).

.PARAMETER Stop
Closes the client started by this script (PID in client.pid) and exits. Before
the login screen has appeared the client ignores window close requests, so a
stop during start-up ends in a force-stop after 20 s.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -TrustCert E:\certs\mocksync.crt

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -Stop
#>
[CmdletBinding()]
param(
	[string]$RunDir,
	[string]$TrustCert,
	[string]$TrustAlias = 'mocksync',
	[switch]$Reset,
	[switch]$Console,
	[string]$MaxHeap = '2048m',
	[switch]$Stop
)

$ErrorActionPreference = 'Stop'
$MainClass = 'io.mosip.registration.controller.Initialization'

if (-not $RunDir) {
	if ($env:MOSIP_RUN_ROOT) {
		$RunDir = Join-Path $env:MOSIP_RUN_ROOT 'desktop'
	} else {
		$RunDir = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'run\desktop'
	}
}
$RunDir = [System.IO.Path]::GetFullPath($RunDir)
$PidFile = Join-Path $RunDir 'client.pid'

function Get-ClientProcess {
	if (-not (Test-Path $PidFile)) { return $null }
	$savedPid = [int](Get-Content $PidFile -TotalCount 1)
	$proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
	if ($proc -and ($proc.ProcessName -in @('java', 'javaw'))) { return $proc }
	return $null
}

if ($Stop) {
	$proc = Get-ClientProcess
	if (-not $proc) {
		Write-Host "No running client found for $RunDir"
		Remove-Item $PidFile -ErrorAction SilentlyContinue
		exit 0
	}
	# The client has two top-level windows (pre-loader and main stage). When both
	# are closed, JavaFX runs ClientApplication.stop(), which closes the machine key
	# store and exits. Force-stop only if that does not happen in time.
	Add-Type -Namespace RegClient -Name Win32 -MemberDefinition @'
public delegate bool EnumProc(System.IntPtr hWnd, System.IntPtr lParam);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, System.IntPtr lParam);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr hWnd, out uint pid);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool IsWindowVisible(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool PostMessage(System.IntPtr hWnd, uint msg, System.IntPtr w, System.IntPtr l);
'@
	$targetPid = [uint32]$proc.Id
	$callback = [RegClient.Win32+EnumProc] {
		param($hWnd, $lParam)
		$ownerPid = [uint32]0
		[void][RegClient.Win32]::GetWindowThreadProcessId($hWnd, [ref]$ownerPid)
		if ($ownerPid -eq $targetPid -and [RegClient.Win32]::IsWindowVisible($hWnd)) {
			[void][RegClient.Win32]::PostMessage($hWnd, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)  # WM_CLOSE
		}
		return $true
	}
	[void][RegClient.Win32]::EnumWindows($callback, [IntPtr]::Zero)
	if (-not $proc.WaitForExit(20000)) {
		Write-Host "Client did not exit after closing its windows; stopping PID $($proc.Id)"
		Stop-Process -Id $proc.Id -Force
		[void]$proc.WaitForExit(10000)
	}
	Remove-Item $PidFile -ErrorAction SilentlyContinue
	Write-Host "Client stopped (PID $($proc.Id))"
	exit 0
}

$javaw = Join-Path $RunDir 'jre\bin\javaw.exe'
$java = Join-Path $RunDir 'jre\bin\java.exe'
if (-not (Test-Path $javaw) -or -not (Test-Path (Join-Path $RunDir 'lib\logback.xml'))) {
	throw "No run layout in $RunDir. Run scripts/desktop-build.sh first."
}

$running = Get-ClientProcess
if ($running) {
	throw "Client already running from $RunDir (PID $($running.Id)). Use -Stop first."
}

if ($TrustCert) {
	$keytool = Join-Path $RunDir 'jre\bin\keytool.exe'
	$cacerts = Join-Path $RunDir 'jre\lib\security\cacerts'
	$certPath = (Resolve-Path $TrustCert).Path
	# keytool writes warnings to stderr; Windows PowerShell 5.1 turns those into
	# errors under 'Stop', so run it under 'Continue' and check the exit code.
	$ErrorActionPreference = 'Continue'
	# Replace any earlier copy under the same alias; a missing alias is not an error.
	& $keytool -delete -alias $TrustAlias -keystore $cacerts -storepass changeit 2>&1 | Out-Null
	$importOutput = & $keytool -importcert -noprompt -alias $TrustAlias -file $certPath -keystore $cacerts -storepass changeit 2>&1
	$importExit = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
	if ($importExit -ne 0) { throw "keytool could not import ${certPath}: $importOutput" }
	Write-Host "Imported $certPath into $cacerts as '$TrustAlias'"
}

if ($Reset) {
	foreach ($dir in @('db', '.mosipkeys', 'logs')) {
		$path = Join-Path $RunDir $dir
		if (Test-Path $path) {
			Remove-Item -Recurse -Force $path
			Write-Host "Deleted $path"
		}
	}
}

$javaArgs = @("-Xmx$MaxHeap", '-Dfile.encoding=UTF-8', '-cp', 'lib/*', $MainClass)
$stdout = Join-Path $RunDir 'startup.log'
$stderr = Join-Path $RunDir 'startup-err.log'

if ($Console) {
	Push-Location $RunDir
	try {
		& $java @javaArgs
		exit $LASTEXITCODE
	} finally {
		Pop-Location
	}
}

$proc = Start-Process -FilePath $javaw -ArgumentList $javaArgs -WorkingDirectory $RunDir `
	-RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Set-Content -Path $PidFile -Value $proc.Id -Encoding ascii
Write-Host "Registration Client started (PID $($proc.Id))"
Write-Host "  run layout : $RunDir"
Write-Host "  app log    : $(Join-Path $RunDir 'logs\registration.log')"
Write-Host "  stdout     : $stdout"
Write-Host "  stop with  : powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Stop"
