[CmdletBinding()]
param()

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

Function Await($WinRtTask, $ResultType) {
    $asTaskObject = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTaskObject.Invoke($null, @($WinRtTask))
    $netTask.Wait()
    return $netTask.Result
}

[Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.BluetoothDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null

$selector = [Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelectorFromDeviceName("KEMI-KB")
$devices = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector)) ([Windows.Devices.Enumeration.DeviceInformationCollection])

Write-Host "Found $($devices.Count) matching devices:"

foreach ($dev in $devices) {
    Write-Host "--------------------------------------------------"
    Write-Host "Device Name: $($dev.Name)"
    Write-Host "Device ID:   $($dev.Id)"
    
    try {
        $btDev = Await ([Windows.Devices.Bluetooth.BluetoothDevice]::FromIdAsync($dev.Id)) ([Windows.Devices.Bluetooth.BluetoothDevice])
        if ($btDev) {
            $cod = $btDev.ClassOfDevice
            Write-Host "Class of Device (CoD) Raw: 0x$($cod.RawValue.ToString('X6')) ($($cod.RawValue))"
            Write-Host "  Major Class: $($cod.MajorClass)"
            Write-Host "  Minor Class: $($cod.MinorClass)"
            
            # Check services
            $rfcomm = Await ($btDev.GetRfcommServicesAsync()) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
            # Or Check GATT
        }
    } catch {
        Write-Host "Could not load detail: $_"
    }
}
