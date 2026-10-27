<#
.SYNOPSIS
  Read-only diagnostic capture for the WavDrop P5-F Full-vs-Bounded device validation.

.DESCRIPTION
  * Needs no root. Runs only read-only adb commands (devices, getprop, pm path, dumpsys meminfo, logcat -d / logcat).
  * Never clears logcat, installs, uninstalls, force-stops or clears app data.
  * Reports a missing adb, no device, an unauthorized device or several devices clearly and exits non-zero without acting.
  * Writes to a timestamped folder OUTSIDE the repository (default: %USERPROFILE%\wavdrop-validation-logs).
  * Keeps only WavDrop tags (silences everything else), so unrelated app logs and other apps' crash reports are not collected.

.EXAMPLE
  # One-shot: dump what the device buffer holds right now, plus device info and memory
  .\scripts\Capture-WavdropValidation.ps1 -Label full-A1 -Mode Snapshot

.EXAMPLE
  # Live: stream until Ctrl+C (run it BEFORE the test action; the buffer is not cleared)
  .\scripts\Capture-WavdropValidation.ps1 -Label bounded-B3 -Mode Live

.EXAMPLE
  # Memory sample only
  .\scripts\Capture-WavdropValidation.ps1 -Label bounded-peak -Mode Meminfo
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Label,
    [ValidateSet('Snapshot', 'Live', 'Meminfo')][string]$Mode = 'Snapshot',
    [string]$Serial,
    [string]$OutRoot = (Join-Path $env:USERPROFILE 'wavdrop-validation-logs'),
    [string]$Package = 'com.launchpoint.wavdrop.debug'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
# Containment is decided on the normalized path, so a directory that does not exist yet is judged exactly like an existing one.
$outFull = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutRoot)).TrimEnd('\', '/')
$repoFull = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
if ($outFull.Equals($repoFull, [StringComparison]::OrdinalIgnoreCase) -or
    $outFull.StartsWith($repoFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $outFull.StartsWith($repoFull + [IO.Path]::AltDirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    [Console]::Error.WriteLine("OutRoot must be outside the Git repository ($repoFull).")
    exit 2
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) { $adb = $sdkAdb } else { Write-Error 'adb not found on PATH or in the default Android SDK location.'; exit 2 }
} else { $adb = $adb.Source }

# ---- device state (reported, never assumed) --------------------------------------------------------------------------
$lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
$devices = @($lines | ForEach-Object { $p = $_ -split '\s+'; [pscustomobject]@{ Serial = $p[0]; State = $p[1] } })
if ($Serial) { $devices = @($devices | Where-Object Serial -eq $Serial) }
if ($devices.Count -eq 0) { Write-Error 'No adb device attached (check cable, USB debugging, or start the emulator).'; exit 3 }
if ($devices.Count -gt 1) { Write-Error ("Several devices attached; pass -Serial. Seen: " + ($devices.Serial -join ', ')); exit 3 }
$dev = $devices[0]
switch ($dev.State) {
    'device'       { }
    'unauthorized' { Write-Error "Device $($dev.Serial) is UNAUTHORIZED: accept the RSA prompt on the phone, then re-run."; exit 4 }
    'offline'      { Write-Error "Device $($dev.Serial) is OFFLINE: reconnect the cable / toggle USB debugging."; exit 4 }
    default        { Write-Error "Device $($dev.Serial) is in state '$($dev.State)'."; exit 4 }
}
$s = @('-s', $dev.Serial)

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$safeLabel = ($Label -replace '[^A-Za-z0-9._-]', '_')
$dir = Join-Path $OutRoot "$stamp-$safeLabel"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function Get-Prop($name) { (& $adb @s shell getprop $name).Trim() }

# ---- device + build identity ------------------------------------------------------------------------------------------
$info = [ordered]@{
    capturedAt   = (Get-Date).ToString('o')
    label        = $Label
    mode         = $Mode
    deviceModel  = Get-Prop 'ro.product.model'
    manufacturer = Get-Prop 'ro.product.manufacturer'
    androidRelease = Get-Prop 'ro.build.version.release'
    apiLevel     = Get-Prop 'ro.build.version.sdk'
    package      = $Package
    installed    = [bool](& $adb @s shell pm path $Package)
}
$pkgDump = & $adb @s shell dumpsys package $Package
$info.versionName = (($pkgDump | Select-String 'versionName=' | Select-Object -First 1) -replace '.*versionName=', '').Trim()
$info.lastUpdateTime = (($pkgDump | Select-String 'lastUpdateTime=' | Select-Object -First 1) -replace '.*lastUpdateTime=', '').Trim()
$info | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir 'device-info.json')
if (-not $info.installed) { Write-Warning "$Package is not installed on this device." }

# WavDrop tags only; '*:S' silences everything else.
$tags = @('WavdropValidation:D', 'WavdropStartTrace:D', 'WavdropQueuePerf:D', 'WavdropSessionPerf:D', 'WavdropSessionProgress:D',
          'WavdropCrossfade:D', 'WavdropNextSlot:D', 'WavdropResume:D', 'Wavdrop-Startup:D', 'WavStats-PC:W', '*:S')
# Deliberately no AndroidRuntime tag: it is shared by every app on the device and would collect unrelated apps' crash reports.
# WavDrop-only crash diagnostics are a separate, explicitly requested procedure (see docs/qa/P5-F-DEVICE-VALIDATION.md).

function Save-Meminfo($suffix) {
    $out = Join-Path $dir "meminfo-$suffix.txt"
    & $adb @s shell dumpsys meminfo $Package | Set-Content -Encoding UTF8 $out
    Write-Host "meminfo -> $out"
}

switch ($Mode) {
    'Meminfo'  { Save-Meminfo (Get-Date -Format 'HHmmss') }
    'Snapshot' {
        $out = Join-Path $dir 'logcat.txt'
        & $adb @s logcat -d -v threadtime @tags | Set-Content -Encoding UTF8 $out   # -d: dump and exit; buffer NOT cleared
        Save-Meminfo 'end'
        Write-Host "logcat -> $out"
    }
    'Live' {
        $out = Join-Path $dir 'logcat.txt'
        Write-Host "Streaming to $out (Ctrl+C to stop; buffer is not cleared)..."
        try { & $adb @s logcat -v threadtime @tags | Tee-Object -FilePath $out }
        finally { Save-Meminfo 'end' }
    }
}
Write-Host "Done. Folder: $dir"
