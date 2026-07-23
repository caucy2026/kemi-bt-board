# A2DP Disable Verification Script
# Validates bluetooth_disabled_profiles against a2dp.md spec
param(
    [string]$Device = "192.168.3.46",
    [int]$WaitSec = 30
)

$ErrorActionPreference = "Continue"
$PkgName = "com.kboard"

function AShell { param([string]$C); adb -s $Device shell $C 2>&1 }
function ALogcat { param([string]$F); adb -s $Device logcat -d -s $F 2>&1 }

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  KEMI A2DP Verification Test" -ForegroundColor Cyan
Write-Host "  Target: $Device" -ForegroundColor Cyan
Write-Host "  Spec: a2dp.md (bitmask 2052 = Sink 4 + Source 2048)" -ForegroundColor Cyan
Write-Host "============================================================`n" -ForegroundColor Cyan

# Phase 1: Snapshot initial state
Write-Host "[Phase 1] Initial state snapshot..." -ForegroundColor Yellow

adb connect $Device 2>&1 | Out-Null

Write-Host "--- Before: bluetooth_disabled_profiles ---" -ForegroundColor Green
$profBefore = AShell "settings get global bluetooth_disabled_profiles"
Write-Host "  value = '$profBefore'"

Write-Host "--- Before: is_a2dp_dynamic prop ---" -ForegroundColor Green
$propBefore = AShell "getprop persist.sys_im.blutooth.is_a2dp_dynamic"
Write-Host "  value = '$propBefore'"

# Phase 2: Launch app
Write-Host "`n[Phase 2] Launching app..." -ForegroundColor Yellow

AShell "am force-stop $PkgName" | Out-Null
Start-Sleep 2
AShell "logcat -c" | Out-Null

AShell "am start -n $PkgName/.MainActivity"
Write-Host "  App launched, waiting ${WaitSec}s for init + BT restart..." -ForegroundColor Magenta
for ($i = 0; $i -lt $WaitSec; $i++) {
    Write-Host -NoNewline "."
    Start-Sleep 1
}
Write-Host ""

# Phase 3: Verify
Write-Host "`n[Phase 3] Verification..." -ForegroundColor Yellow

# Check 1: bluetooth_disabled_profiles MUST be "2052"
Write-Host "`n--- [Check 1] bluetooth_disabled_profiles == '2052' ---" -ForegroundColor Green
$profAfter = AShell "settings get global bluetooth_disabled_profiles"
Write-Host "  value = '$profAfter'"
$check1 = ($profAfter -and $profAfter.Trim() -eq "2052")
if ($check1) {
    Write-Host "  PASS: bitmask 2052 = A2DP Sink(4) + Source(2048) both disabled" -ForegroundColor Green
} else {
    Write-Host "  FAIL: expected '2052', got '$profAfter'" -ForegroundColor Red
}

# Check 2: is_a2dp_dynamic MUST be "true"
Write-Host "`n--- [Check 2] is_a2dp_dynamic == 'true' ---" -ForegroundColor Green
$propAfter = AShell "getprop persist.sys_im.blutooth.is_a2dp_dynamic"
Write-Host "  value = '$propAfter'"
$check2 = ($propAfter -and $propAfter.Trim() -eq "true")
if ($check2) {
    Write-Host "  PASS: dynamic profile switching enabled" -ForegroundColor Green
} else {
    Write-Host "  FAIL: expected 'true', got '$propAfter'" -ForegroundColor Red
    Write-Host "  Note: setprop requires root. App runs as system uid (sharedUserId)." -ForegroundColor Yellow
}

# Check 3: App logs - trace the disable flow
Write-Host "`n--- [Check 3] App log trace ---" -ForegroundColor Green
$logBt = ALogcat "BluetoothHidManager"
$logMain = ALogcat "MainActivity"
$keyLogs = @($logBt) + @($logMain) | Where-Object {
    $_ -match "A2DP|disableA2dp|restoreA2dp|setprop|bluetooth_disabled|Force-stop|BT OFF|BT ON|saved orig|2052"
}

if ($keyLogs) {
    Write-Host "  Key log entries:" -ForegroundColor Gray
    $keyLogs | ForEach-Object { Write-Host "    $_" -ForegroundColor Gray }
    
    $hasSetprop = ($keyLogs | Select-String "setprop").Count -gt 0
    $hasTarget = ($keyLogs | Select-String "2052").Count -gt 0
    $hasSaveOrig = ($keyLogs | Select-String "save orig").Count -gt 0
    $hasBtCycle = ($keyLogs | Select-String "BT OFF|BT ON").Count -gt 0
    
    Write-Host "`n  Trace checkpoints:" -ForegroundColor Gray
    Write-Host "    setprop executed:  $(if($hasSetprop){'OK'}else{'MISSING'})"
    Write-Host "    saved orig value:  $(if($hasSaveOrig){'OK'}else{'MISSING'})"
    Write-Host "    wrote target 2052: $(if($hasTarget){'OK'}else{'MISSING'})"
    Write-Host "    BT OFF->ON cycle:  $(if($hasBtCycle){'OK'}else{'MISSING'})"
} else {
    Write-Host "  WARNING: No A2DP log entries captured" -ForegroundColor Yellow
    Write-Host "  Try: adb logcat -s BluetoothHidManager:*" -ForegroundColor Gray
}

# Check 4: Raw settings type verification
Write-Host "`n--- [Check 4] Settings value type ---" -ForegroundColor Green
$rawGet = AShell "settings get global bluetooth_disabled_profiles"
Write-Host "  settings get returns: '$rawGet'"
if (-not $rawGet -or $rawGet.Trim() -in @("null", "")) {
    Write-Host "  WARNING: null/empty - possible putLong/putString type mismatch" -ForegroundColor Yellow
} elseif ($rawGet.Trim() -eq "2052") {
    Write-Host "  OK: putString works correctly, value is readable" -ForegroundColor Green
}

# Summary
Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  Summary" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$pass = 0; $total = 2
if ($check1) { $pass++ }
if ($check2) { $pass++ }

Write-Host "  1. bluetooth_disabled_profiles = 2052 : $(if($check1){'PASS'}else{'FAIL'})"
Write-Host "  2. is_a2dp_dynamic = true           : $(if($check2){'PASS'}else{'FAIL'})"
Write-Host "  Result: $pass / $total passed" -ForegroundColor $(if($pass -eq $total){'Green'}else{'Red'})

if ($pass -eq $total) {
    Write-Host "`n  SUCCESS: A2DP Sink+Source disabled (bitmask=2052)." -ForegroundColor Green
    Write-Host "  Custom Bluetooth.apk will read this on restart and disable profiles." -ForegroundColor Green
} else {
    Write-Host "`n  Some checks failed. Possible causes:" -ForegroundColor Yellow
    Write-Host "  1. Custom Bluetooth.apk not deployed? (a2dp.md step 2)" -ForegroundColor Yellow
    Write-Host "  2. setprop requires root? Check: adb root && getprop" -ForegroundColor Yellow
    Write-Host "  3. Check full logs: adb logcat -s BluetoothHidManager:*" -ForegroundColor Yellow
}

# Save output
$outFile = Join-Path $PSScriptRoot "verify_a2dp_result.txt"
@"
KEMI A2DP Verify - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Device: $Device
profiles_before=$profBefore
profiles_after=$profAfter
prop_before=$propBefore
prop_after=$propAfter
check_profiles_2052=$check1
check_prop_true=$check2
"@ | Out-File $outFile -Encoding ascii
Write-Host "`n  Full result saved to: $outFile" -ForegroundColor Gray
Write-Host "============================================================`n" -ForegroundColor Cyan
