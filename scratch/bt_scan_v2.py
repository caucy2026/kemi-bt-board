"""
Bluetooth Scanner v2 - Fixed for Windows 10/11
Uses Windows Bluetooth API via ctypes to enumerate devices and SDP services.
"""
import ctypes
import sys
from ctypes import wintypes

# ========== Constants ==========
BTH_MAX_DEVICE_NAME_LENGTH = 256

class SYSTEMTIME(ctypes.Structure):
    _fields_ = [
        ("wYear", wintypes.WORD), ("wMonth", wintypes.WORD),
        ("wDayOfWeek", wintypes.WORD), ("wDay", wintypes.WORD),
        ("wHour", wintypes.WORD), ("wMinute", wintypes.WORD),
        ("wSecond", wintypes.WORD), ("wMilliseconds", wintypes.WORD),
    ]

class BLUETOOTH_ADDRESS(ctypes.Structure):
    _fields_ = [("rgBytes", ctypes.c_ubyte * 6)]
    def fmt(self):
        return ":".join(f"{b:02X}" for b in self.rgBytes[::-1])

class BLUETOOTH_DEVICE_INFO(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("Address", BLUETOOTH_ADDRESS),
        ("ulClassofDevice", wintypes.ULONG),
        ("fConnected", wintypes.BOOL),
        ("fRemembered", wintypes.BOOL),
        ("stLastSeen", SYSTEMTIME),
        ("stLastUsed", SYSTEMTIME),
        ("szName", wintypes.WCHAR * BTH_MAX_DEVICE_NAME_LENGTH),
    ]

class BLUETOOTH_DEVICE_SEARCH_PARAMS(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("fReturnAuthenticated", wintypes.BOOL),
        ("fReturnRemembered", wintypes.BOOL),
        ("fReturnUnknown", wintypes.BOOL),
        ("fReturnConnected", wintypes.BOOL),
        ("fIssueInquiry", wintypes.BOOL),
        ("cTimeoutMultiplier", ctypes.c_ubyte),
        ("hRadio", wintypes.HANDLE),
    ]

# ========== Load Bluetooth API DLL ==========
# Windows 8+: BluetoothAPIs.dll
# Fallback: bthprops.cpl (legacy)
btapi = None
for dll_name in ["BluetoothAPIs.dll", "bthprops.cpl"]:
    try:
        btapi = ctypes.WinDLL(dll_name)
        print(f"[OK] Loaded {dll_name}")
        break
    except Exception as e:
        print(f"[FAIL] Cannot load {dll_name}: {e}")

if btapi is None:
    print("FATAL: No Bluetooth API DLL found!")
    sys.exit(1)

# ========== Function prototypes ==========
# BluetoothFindFirstDevice
btapi.BluetoothFindFirstDevice.argtypes = [
    ctypes.POINTER(BLUETOOTH_DEVICE_SEARCH_PARAMS),
    ctypes.POINTER(BLUETOOTH_DEVICE_INFO)
]
btapi.BluetoothFindFirstDevice.restype = wintypes.HANDLE

btapi.BluetoothFindNextDevice.argtypes = [
    wintypes.HANDLE, ctypes.POINTER(BLUETOOTH_DEVICE_INFO)
]
btapi.BluetoothFindNextDevice.restype = wintypes.BOOL

btapi.BluetoothFindDeviceClose.argtypes = [wintypes.HANDLE]
btapi.BluetoothFindDeviceClose.restype = wintypes.BOOL

# ========== CoD Parser ==========
def parse_cod(cod_value):
    major = (cod_value >> 8) & 0x1F
    minor = (cod_value >> 2) & 0x3F
    service = (cod_value >> 13) & 0x7FF

    major_names = {
        0:"Misc",1:"Computer",2:"Phone",3:"LAN",4:"Audio/Video",
        5:"Peripheral",6:"Imaging",7:"Wearable",8:"Toy",9:"Health"
    }

    if major == 5 and minor == 0x40:
        dev_type = "⌨️ Keyboard"
    elif major == 5 and minor == 0x80:
        dev_type = "🖱️ Mouse"
    elif major == 5 and (minor & 0x10):
        dev_type = "⌨️ Keyboard variant"
    elif major == 4:
        dev_type = f"🔊 Audio ({minor})"
    elif major == 2:
        dev_type = "📱 Phone"
    elif major == 1:
        dev_type = "💻 Computer"
    else:
        dev_type = f"{major_names.get(major, '?')}/{minor}"

    # Service class bits
    svc = []
    if service & 0x400: svc.append("🎵AUDIO")
    if service & 0x800: svc.append("📞TELEPHONY")
    if service & 0x200: svc.append("📁OBEX")
    if service & 0x100: svc.append("📷Capture")
    if service & 0x080: svc.append("🎨Render")
    if service & 0x040: svc.append("🌐Net")
    if service & 0x020: svc.append("📍Position")
    if service & 0x001: svc.append("🔍Discoverable")

    return dev_type, svc, major_names.get(major, f"0x{major:02X}")

# ========== Main Scan ==========
print()
print("=" * 65)
print("  Windows Bluetooth Device Scanner v2")
print("=" * 65)
print()

# Set up search params
search_params = BLUETOOTH_DEVICE_SEARCH_PARAMS()
search_params.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_SEARCH_PARAMS)
search_params.fReturnAuthenticated = True
search_params.fReturnRemembered = True
search_params.fReturnUnknown = True
search_params.fReturnConnected = True
search_params.fIssueInquiry = True   # Do real inquiry!
search_params.cTimeoutMultiplier = 20 # ~25 second scan
search_params.hRadio = None

device_info = BLUETOOTH_DEVICE_INFO()
device_info.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_INFO)

print(f"SearchParams size: {search_params.dwSize}")
print(f"DeviceInfo size: {device_info.dwSize}")
print()
print("Starting Bluetooth inquiry (up to 25 seconds)...")
print("Make sure the Android KEMI device has Bluetooth ON and is discoverable!")
print()

hFind = btapi.BluetoothFindFirstDevice(
    ctypes.byref(search_params),
    ctypes.byref(device_info)
)

if hFind is None or hFind == 0:
    err = ctypes.GetLastError()
    print(f"BluetoothFindFirstDevice FAILED! Error code: {err} (0x{err:X})")

    # Try without inquiry (just cached devices)
    print()
    print("Retrying without active inquiry (cached devices only)...")
    search_params.fIssueInquiry = False
    hFind = btapi.BluetoothFindFirstDevice(
        ctypes.byref(search_params),
        ctypes.byref(device_info)
    )
    if hFind is None or hFind == 0:
        err = ctypes.GetLastError()
        print(f"Still FAILED! Error: {err}")
        sys.exit(1)

device_num = 0
kemi_devices = []

while True:
    device_num += 1
    name = device_info.szName if device_info.szName else "(Unknown)"
    addr = device_info.Address.fmt()
    cod = device_info.ulClassofDevice
    connected = bool(device_info.fConnected)
    remembered = bool(device_info.fRemembered)

    dev_type, svc_bits, major_name = parse_cod(cod)

    print("-" * 65)
    print(f"[#{device_num}] {name}")
    print(f"  Address:      {addr}")
    print(f"  CoD:          0x{cod:06X}")
    print(f"  Type:         {dev_type}")
    print(f"  MajorClass:   {major_name}")
    svc_str = ", ".join(svc_bits) if svc_bits else "(none)"
    print(f"  ServiceClass: {svc_str}")
    print(f"  Connected:    {connected} | Remembered: {remembered}")

    is_kemi = "KEMI" in name.upper() if name else False
    if is_kemi:
        kemi_devices.append((name, addr, cod, svc_str, dev_type))
        print(f"  ⭐⭐⭐ THIS IS THE KEMI DEVICE! ⭐⭐⭐")

    print()

    # Next device
    next_dev = BLUETOOTH_DEVICE_INFO()
    next_dev.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_INFO)
    if not btapi.BluetoothFindNextDevice(hFind, ctypes.byref(next_dev)):
        break
    device_info = next_dev

btapi.BluetoothFindDeviceClose(hFind)

print("=" * 65)
print(f"  Total devices found: {device_num}")
print("=" * 65)

if kemi_devices:
    print()
    print("=" * 65)
    print("  KEMI DEVICE ANALYSIS")
    print("=" * 65)
    for name, addr, cod, svc, dtype in kemi_devices:
        print(f"  Name:         {name}")
        print(f"  Address:      {addr}")
        print(f"  CoD:          0x{cod:06X}")
        print(f"  Windows type: {dtype}")
        print(f"  ServiceClass: {svc}")
        print()
        print("  🔍 DIAGNOSIS:")
        if "AUDIO" in svc:
            print("     ⚠️  ServiceClass contains AUDIO bit!")
            print("     → Mac/Windows will classify as audio device")
            print("     → FIX: Clear bit 0x400 (Audio) from CoD ServiceClass")
        else:
            print("     ✅ No AUDIO service class bit - CoD is correct")

        major = (cod >> 8) & 0x1F
        minor = (cod >> 2) & 0x3F
        if major == 5 and minor == 0x40:
            print("     ✅ Major=Peripheral Minor=Keyboard - CoD is keyboard")
        else:
            print(f"     ⚠️  Major=0x{major:02X} Minor=0x{minor:02X} - not pure keyboard!")
else:
    print()
    print("⚠️  KEMI device NOT FOUND!")
    print("   Make sure:")
    print("   1. Android device Bluetooth is ON")
    print("   2. KEMI app is running")
    print("   3. Device is discoverable (visible to other devices)")

print()
input("Press Enter to exit...")
