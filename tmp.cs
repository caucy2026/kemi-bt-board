using System;
using Windows.Devices.Enumeration;
using Windows.Devices.Bluetooth;
class P { static void Main() { Scan().Wait(); }
static async System.Threading.Tasks.Task Scan() {
var d=await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelector());
Console.WriteLine("Found "+d.Count+" BT devices:\n");
foreach(var x in d){Console.WriteLine("  "+x.Name);}
}}}
