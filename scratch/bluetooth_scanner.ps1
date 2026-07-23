# BluetoothDeviceScanner.ps1
# Windows Bluetooth 经典设备 + 服务扫描器
# 使用 Windows.Devices.Bluetooth API 扫描附近的蓝牙设备及其 SDP 服务

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

Function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

Function AwaitAction($WinRtAction) {
    $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]
    $netTask = $asTask.Invoke($null, @($WinRtAction))
    $netTask.Wait(-1) | Out-Null
}

# Load WinRT assemblies
[Windows.Devices.Bluetooth.BluetoothDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Windows 蓝牙设备扫描器" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ========== 方法1: 使用 BluetoothDevice.FindAllAsync() ==========
Write-Host "[1] 扫描经典蓝牙设备 (BR/EDR)..." -ForegroundColor Yellow

# 先通过 DeviceInformation 找到所有蓝牙设备
$deviceSelector = [Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelectorFromPairingState($false)
$allDevices = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($deviceSelector)) ([Windows.Devices.Enumeration.DeviceInformationCollection])

# 也获取已配对的
$pairedSelector = [Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelectorFromPairingState($true)
$pairedDevices = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($pairedSelector)) ([Windows.Devices.Enumeration.DeviceInformationCollection])

Write-Host "  未配对设备: $($allDevices.Count) 个"
Write-Host "  已配对设备: $($pairedDevices.Count) 个"
Write-Host ""

# 合并去重
$allDeviceInfos = @{}
foreach ($d in $allDevices) { $allDeviceInfos[$d.Id] = $d }
foreach ($d in $pairedDevices) { $allDeviceInfos[$d.Id] = $d }

$deviceCount = 0
foreach ($deviceInfo in $allDeviceInfos.Values) {
    $deviceCount++
    $name = $deviceInfo.Name
    if ([string]::IsNullOrEmpty($name)) { $name = "(未知设备)" }
    
    Write-Host "----------------------------------------" -ForegroundColor Gray
    Write-Host "[设备 $deviceCount] $name" -ForegroundColor Green
    Write-Host "  ID: $($deviceInfo.Id)" -ForegroundColor DarkGray
    
    # 尝试获取 BluetoothDevice 对象
    try {
        $btDevice = Await ([Windows.Devices.Bluetooth.BluetoothDevice]::FromIdAsync($deviceInfo.Id)) ([Windows.Devices.Bluetooth.BluetoothDevice])
    } catch {
        Write-Host "  [无法获取 BluetoothDevice 详情]" -ForegroundColor Red
        continue
    }
    
    if ($null -eq $btDevice) {
        Write-Host "  [BluetoothDevice 为 null - 可能是 LE 设备]" -ForegroundColor DarkYellow
        continue
    }
    
    # 基本信息
    Write-Host "  BluetoothAddress: $($btDevice.BluetoothAddress.ToString('X12'))" -ForegroundColor Gray
    Write-Host "  ClassOfDevice: 0x$($btDevice.ClassOfDevice.RawValue.ToString('X6'))" -ForegroundColor Gray
    
    # 解析 Class of Device
    $cod = $btDevice.ClassOfDevice.RawValue
    $majorClass = ($cod -shr 8) -band 0x1F
    $minorClass = ($cod -shr 2) -band 0x3F
    $serviceClass = ($cod -shr 13) -band 0x7FF
    
    $majorNames = @{
        0 = "Miscellaneous"; 1 = "Computer"; 2 = "Phone"; 3 = "LAN/Network"
        4 = "Audio/Video"; 5 = "Peripheral"; 6 = "Imaging"; 7 = "Wearable"; 8 = "Toy"; 9 = "Health"
    }
    $minorPeripheral = @{
        0x10 = "Keyboard"; 0x20 = "Pointing"; 0x30 = "Combo KB/Mouse"
        0x40 = "Keyboard"; 0x80 = "Mouse"
    }
    $minorAudio = @{
        1 = "Headset"; 2 = "HandsFree"; 3 = "Mic"; 4 = "Speaker"; 5 = "Headphones"
        6 = "Portable Audio"; 7 = "Car Audio"; 8 = "SetTopBox"; 9 = "Hifi Audio"
        10 = "VCR"; 11 = "Video Camera"; 12 = "Camcorder"; 13 = "Video Monitor"
        14 = "Video Display Loudspk"; 15 = "Video Conferencing"; 16 = "Gaming/Toy"
    }
    
    $majorName = if ($majorNames.ContainsKey($majorClass)) { $majorNames[$majorClass] } else { "Unknown($majorClass)" }
    Write-Host "  MajorClass: $majorName (0x$($majorClass.ToString('X2')))" -ForegroundColor Gray
    
    # Service class bits
    $svcBits = @()
    if (($serviceClass -band 0x001) -ne 0) { $svcBits += "LimitedDiscoverable" }
    if (($serviceClass -band 0x020) -ne 0) { $svcBits += "Positioning" }
    if (($serviceClass -band 0x040) -ne 0) { $svcBits += "Networking" }
    if (($serviceClass -band 0x080) -ne 0) { $svcBits += "Rendering" }
    if (($serviceClass -band 0x100) -ne 0) { $svcBits += "Capturing" }
    if (($serviceClass -band 0x200) -ne 0) { $svcBits += "ObjectTransfer" }
    if (($serviceClass -band 0x400) -ne 0) { $svcBits += "Audio" }
    if (($serviceClass -band 0x800) -ne 0) { $svcBits += "Telephony" }
    if (($serviceClass -band 0x1000) -ne 0) { $svcBits += "Information" }
    if ($svcBits.Count -gt 0) {
        Write-Host "  ServiceClass: $($svcBits -join ', ')" -ForegroundColor Yellow
    } else {
        Write-Host "  ServiceClass: (none)" -ForegroundColor Gray
    }
    
    # Rfcomm Services (SDP records for classic Bluetooth)
    Write-Host "  Rfcomm SDP Services:" -ForegroundColor Cyan
    try {
        $rfcommServices = Await ($btDevice.GetRfcommServicesAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceServicesResult])
        if ($rfcommServices.Error -ne [Windows.Devices.Bluetooth.BluetoothError]::Success) {
            Write-Host "    Error: $($rfcommServices.Error)" -ForegroundColor Red
        } elseif ($rfcommServices.Services.Count -eq 0) {
            Write-Host "    (无 Rfcomm 服务)" -ForegroundColor DarkGray
        } else {
            foreach ($svc in $rfcommServices.Services) {
                $uuid = $svc.ServiceId.Uuid
                Write-Host "    - $($svc.ConnectionServiceName) | UUID: $uuid" -ForegroundColor White
                
                # Try to get SDP attributes
                try {
                    $sdpResult = Await ($svc.GetSdpRawAttributesAsync()) ([Windows.Foundation.Collections.IMapView[UInt32,Windows.Storage.Streams.IBuffer]])
                    if ($sdpResult.Count -gt 0) {
                        Write-Host "      SDP Attributes: $($sdpResult.Count) entries" -ForegroundColor DarkGray
                        foreach ($kvp in $sdpResult) {
                            $reader = [Windows.Storage.Streams.DataReader]::FromBuffer($kvp.Value)
                            $bytes = New-Object byte[] $kvp.Value.Length
                            $reader.ReadBytes($bytes)
                            Write-Host "        Attr 0x$($kvp.Key.ToString('X4')): $([BitConverter]::ToString($bytes).Replace('-',' '))" -ForegroundColor DarkGray
                        }
                    }
                } catch {
                    Write-Host "      (无法读取 SDP 属性: $_)" -ForegroundColor DarkGray
                }
            }
        }
    } catch {
        Write-Host "    Rfcomm scan failed: $_" -ForegroundColor Red
    }
    
    # DeviceAccessInformation
    $accessInfo = [Windows.Devices.Enumeration.DeviceAccessInformation]::CreateFromId($deviceInfo.Id)
    Write-Host "  AccessStatus: $($accessInfo.CurrentStatus)" -ForegroundColor Gray
    
    Write-Host ""
}

# ========== 方法2: 扫描所有蓝牙配对设备通过 Windows 注册表 ==========
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "[2] Windows 蓝牙注册表信息" -ForegroundColor Yellow

$btRegPath = "HKLM:\SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Devices"
if (Test-Path $btRegPath) {
    $btDevices = Get-ChildItem $btRegPath -ErrorAction SilentlyContinue
    foreach ($dev in $btDevices) {
        $props = Get-ItemProperty $dev.PSPath -ErrorAction SilentlyContinue
        $devName = $props.Name
        $devCod = $props.ClassOfDevice
        if (-not [string]::IsNullOrEmpty($devName)) {
            Write-Host "  $devName" -ForegroundColor Green
            Write-Host "    Address: $($dev.PSChildName)" -ForegroundColor Gray
            if ($devCod) { Write-Host "    CoD: 0x$($devCod.ToString('X6'))" -ForegroundColor Gray }
        }
    }
} else {
    Write-Host "  注册表路径不存在" -ForegroundColor DarkYellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  扫描完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
