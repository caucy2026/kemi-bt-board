Get-PnpDevice -Class Bluetooth | Where-Object { $_.FriendlyName -like "*KEMI*" -or $_.FriendlyName -like "*KBOARD*" } | Select-Object FriendlyName, InstanceId, Status, Class

Get-WmiObject -Class Win32_PnPEntity | Where-Object { $_.Name -like "*KEMI*" -or $_.Name -like "*KBOARD*" } | Select-Object Name, DeviceID, PNPClass, Service
