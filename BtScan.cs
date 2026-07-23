using System;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

class BtScan {
    static async Task Main() {
        var devices = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelector());
        Console.WriteLine($"Found {devices.Count} Bluetooth devices:\n");
        foreach (var d in devices) {
            var name = d.Name ?? "(unnamed)";
            var bt = await BluetoothDevice.FromIdAsync(d.Id);
            var cod = bt?.ClassOfDevice;
            var codHex = cod != null ? $"0x{cod.RawValue:X}" : "N/A";
            var conn = bt?.ConnectionStatus.ToString() ?? "N/A";
            var icon = name.Contains("KEMI") ? "[KEMI-KB] " : "         ";
            Console.WriteLine($"{icon}{name}");
            Console.WriteLine($"  CoD: {codHex}  Connected: {conn}");
            if (cod != null) {
                Console.WriteLine($"  MajorClass: {cod.MajorClass}  ServiceCap: 0x{cod.ServiceCapabilities:X}");
            }
            Console.WriteLine();
        }
    }
}
