using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Devices.Bluetooth.Background;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace BluetoothSdpScanner
{
    class Program
    {
        static async Task Main(string[] args)
        {
            Console.WriteLine("========================================");
            Console.WriteLine("  Windows Bluetooth SDP Service Scanner");
            Console.WriteLine("========================================");
            Console.WriteLine();

            // ===== 1. Scan all Bluetooth devices =====
            Console.WriteLine("[1] Scanning for Bluetooth devices...");
            
            // Paired devices
            var pairedSelector = BluetoothDevice.GetDeviceSelectorFromPairingState(true);
            var pairedDevices = await DeviceInformation.FindAllAsync(pairedSelector);
            
            // Unpaired devices
            var unpairedSelector = BluetoothDevice.GetDeviceSelectorFromPairingState(false);
            var unpairedDevices = await DeviceInformation.FindAllAsync(unpairedSelector);
            
            Console.WriteLine($"  Paired: {pairedDevices.Count}, Unpaired: {unpairedDevices.Count}");
            
            // Merge
            var allIds = new HashSet<string>();
            var allDevices = new List<DeviceInformation>();
            foreach (var d in pairedDevices) { if (allIds.Add(d.Id)) allDevices.Add(d); }
            foreach (var d in unpairedDevices) { if (allIds.Add(d.Id)) allDevices.Add(d); }
            Console.WriteLine($"  Total unique devices: {allDevices.Count}");
            Console.WriteLine();

            // ===== 2. Analyze each device =====
            int deviceNum = 0;
            foreach (var deviceInfo in allDevices)
            {
                deviceNum++;
                string name = string.IsNullOrEmpty(deviceInfo.Name) ? "(Unknown)" : deviceInfo.Name;
                
                Console.WriteLine("----------------------------------------");
                Console.WriteLine($"[Device #{deviceNum}] {name}");
                Console.WriteLine($"  ID: {deviceInfo.Id}");

                BluetoothDevice btDevice;
                try
                {
                    btDevice = await BluetoothDevice.FromIdAsync(deviceInfo.Id);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"  [Cannot get BluetoothDevice: {ex.Message}]");
                    continue;
                }

                if (btDevice == null)
                {
                    Console.WriteLine("  [BluetoothDevice is null - may be LE-only]");
                    continue;
                }

                // Basic info
                Console.WriteLine($"  Address: {btDevice.BluetoothAddress:X12}");
                
                var cod = btDevice.ClassOfDevice.RawValue;
                uint majorClass = (cod >> 8) & 0x1F;
                uint minorClass = (cod >> 2) & 0x3F;
                uint serviceClass = (cod >> 13) & 0x7FF;
                
                Console.WriteLine($"  ClassOfDevice: 0x{cod:X6}");
                
                string majorName = majorClass switch
                {
                    0 => "Miscellaneous", 1 => "Computer", 2 => "Phone",
                    3 => "LAN/Network", 4 => "Audio/Video", 5 => "Peripheral",
                    6 => "Imaging", 7 => "Wearable", 8 => "Toy", 9 => "Health",
                    _ => $"Unknown({majorClass})"
                };
                Console.WriteLine($"  MajorClass: {majorName} (0x{majorClass:X2})");
                Console.WriteLine($"  MinorClass: 0x{minorClass:X2}");
                
                // Service class bits
                var svcBits = new List<string>();
                if ((serviceClass & 0x001) != 0) svcBits.Add("LimitedDiscoverable");
                if ((serviceClass & 0x020) != 0) svcBits.Add("Positioning");
                if ((serviceClass & 0x040) != 0) svcBits.Add("Networking");
                if ((serviceClass & 0x080) != 0) svcBits.Add("Rendering");
                if ((serviceClass & 0x100) != 0) svcBits.Add("Capturing");
                if ((serviceClass & 0x200) != 0) svcBits.Add("ObjectTransfer");
                if ((serviceClass & 0x400) != 0) svcBits.Add("AUDIO");       // <-- THIS is the problem bit!
                if ((serviceClass & 0x800) != 0) svcBits.Add("TELEPHONY");   // <-- This too!
                if ((serviceClass & 0x1000) != 0) svcBits.Add("Information");
                Console.WriteLine($"  ServiceClass bits: {(svcBits.Count > 0 ? string.Join(", ", svcBits) : "(none)")}");
                
                // Host device info
                Console.WriteLine($"  WasSecureConnected: {btDevice.WasSecureConnectionUsedForPairing}");

                // ===== 3. Rfcomm Services (SDP records for BR/EDR) =====
                Console.WriteLine($"  --- Rfcomm SDP Services ---");
                try
                {
                    var rfcommResult = await btDevice.GetRfcommServicesAsync(BluetoothCacheMode.Uncached);
                    
                    if (rfcommResult.Error != BluetoothError.Success)
                    {
                        Console.WriteLine($"    Error: {rfcommResult.Error}");
                    }
                    else if (rfcommResult.Services.Count == 0)
                    {
                        Console.WriteLine($"    (No Rfcomm services found)");
                    }
                    else
                    {
                        Console.WriteLine($"    Found {rfcommResult.Services.Count} service(s):");
                        foreach (var svc in rfcommResult.Services)
                        {
                            Console.WriteLine($"      Service: {svc.ConnectionServiceName}");
                            Console.WriteLine($"        UUID: {svc.ServiceId.Uuid}");
                            Console.WriteLine($"        MaxProtectionLevel: {svc.MaxProtectionLevel}");
                            
                            // Try to get detailed SDP attributes
                            try
                            {
                                var sdpAttrs = await svc.GetSdpRawAttributesAsync();
                                if (sdpAttrs.Count > 0)
                                {
                                    Console.WriteLine($"        SDP Attributes ({sdpAttrs.Count}):");
                                    foreach (var kvp in sdpAttrs)
                                    {
                                        var reader = DataReader.FromBuffer(kvp.Value);
                                        byte[] bytes = new byte[kvp.Value.Length];
                                        reader.ReadBytes(bytes);
                                        Console.WriteLine($"          Attr 0x{kvp.Key:X4}: {BitConverter.ToString(bytes).Replace('-', ' ')}");
                                    }
                                }
                            }
                            catch (Exception ex)
                            {
                                Console.WriteLine($"        [SDP attr read failed: {ex.Message}]");
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"    Rfcomm scan failed: {ex.Message}");
                }

                Console.WriteLine();
            }

            Console.WriteLine("========================================");
            Console.WriteLine("  Scan Complete");
            Console.WriteLine("========================================");
            Console.WriteLine();
            Console.WriteLine("KEY: If ServiceClass contains 'AUDIO' or 'TELEPHONY',");
            Console.WriteLine("     the device will be classified as audio/headset by macOS/Windows.");
        }
    }
}
