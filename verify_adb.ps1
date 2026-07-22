$ips = @("192.168.3.46", "192.168.3.54", "192.168.3.4")
foreach ($ip in $ips) {
    Write-Host "Trying connect to $ip..."
    $res = adb connect "$ip`:5555"
    Write-Host $res
}
adb devices
