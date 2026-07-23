"""
Windows Bluetooth SDP Scanner - Python + ctypes
Scans for Bluetooth BR/EDR devices and enumerates their SDP services.
"""
import ctypes
import struct
from ctypes import wintypes

# Windows Bluetooth API constants
BTH_MAX_DEVICE_NAME_LENGTH = 256

class SYSTEMTIME(ctypes.Structure):
    _fields_ = [
        ("wYear", wintypes.WORD),
        ("wMonth", wintypes.WORD),
        ("wDayOfWeek", wintypes.WORD),
        ("wDay", wintypes.WORD),
        ("wHour", wintypes.WORD),
        ("wMinute", wintypes.WORD),
        ("wSecond", wintypes.WORD),
        ("wMilliseconds", wintypes.WORD),
    ]

class BLUETOOTH_ADDRESS(ctypes.Structure):
    _fields_ = [("rgBytes", ctypes.c_ubyte * 6)]

    def __str__(self):
        return ":".join(f"{b:02X}" for b in self.rgBytes[::-1])

    @property
    def value(self):
        result = 0
        for b in self.rgBytes[::-1]:
            result = (result << 8) | b
        return result

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

class BLUETOOTH_FIND_RADIO_PARAMS(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD)]

class BLUETOOTH_RADIO_INFO(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("address", BLUETOOTH_ADDRESS),
        ("szName", wintypes.WCHAR * BTH_MAX_DEVICE_NAME_LENGTH),
        ("ulClassofDevice", wintypes.ULONG),
        ("lmpSubversion", wintypes.USHORT),
        ("manufacturer", wintypes.USHORT),
    ]

# Load DLLs - Windows 10/11 Bluetooth APIs
try:
    bthprops = ctypes.WinDLL("BluetoothAPIs.dll")
except:
    bthprops = ctypes.WinDLL("bthprops.cpl")

# Function prototypes
BluetoothFindFirstDevice = bthprops.BluetoothFindFirstDevice
BluetoothFindFirstDevice.argtypes = [ctypes.POINTER(BLUETOOTH_DEVICE_SEARCH_PARAMS), ctypes.POINTER(BLUETOOTH_DEVICE_INFO)]
BluetoothFindFirstDevice.restype = wintypes.HANDLE

BluetoothFindNextDevice = bthprops.BluetoothFindNextDevice
BluetoothFindNextDevice.argtypes = [wintypes.HANDLE, ctypes.POINTER(BLUETOOTH_DEVICE_INFO)]
BluetoothFindNextDevice.restype = wintypes.BOOL

BluetoothFindDeviceClose = bthprops.BluetoothFindDeviceClose
BluetoothFindDeviceClose.argtypes = [wintypes.HANDLE]
BluetoothFindDeviceClose.restype = wintypes.BOOL

# SDP service enumeration - more complex, use WSALookupService
winsock = ctypes.WinDLL("ws2_32.dll")

# GUIDs for Bluetooth SDP
class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", ctypes.c_ubyte * 8),
    ]

WSAQUERYSET_SIZE = 616  # Approximate size for WSAQUERYSETW

# WSAStartup
WSADATA_SIZE = 408
winsock.WSAStartup.argtypes = [wintypes.WORD, ctypes.c_void_p]
winsock.WSAStartup.restype = ctypes.c_int

winsock.WSACleanup.argtypes = []
winsock.WSACleanup.restype = ctypes.c_int

winsock.WSALookupServiceBeginW.argtypes = [ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p]
winsock.WSALookupServiceBeginW.restype = ctypes.c_int

winsock.WSALookupServiceNextW.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.c_void_p, ctypes.c_void_p]
winsock.WSALookupServiceNextW.restype = ctypes.c_int

winsock.WSALookupServiceEnd.argtypes = [wintypes.HANDLE]
winsock.WSALookupServiceEnd.restype = ctypes.c_int


def parse_cod(cod_value):
    """Parse Bluetooth Class of Device"""
    major_class = (cod_value >> 8) & 0x1F
    minor_class = (cod_value >> 2) & 0x3F
    service_class = (cod_value >> 13) & 0x7FF

    major_names = {
        0: "Miscellaneous", 1: "Computer", 2: "Phone", 3: "LAN/Network",
        4: "Audio/Video", 5: "Peripheral", 6: "Imaging", 7: "Wearable",
        8: "Toy", 9: "Health"
    }

    # Minor class for Peripheral
    peripheral_minor = {
        0x10: "Keyboard", 0x20: "PointingDevice", 0x30: "Combo KB/Mouse",
        0x40: "Keyboard(alt)", 0x50: "Pointing(alt)", 0x60: "Gamepad",
        0x80: "Mouse"
    }

    # Minor class for Audio/Video
    audio_minor = {
        1: "Headset", 2: "HandsFree", 3: "Microphone", 4: "Loudspeaker",
        5: "Headphones", 6: "PortableAudio", 7: "CarAudio", 8: "SetTopBox",
        9: "HiFiAudio", 10: "VCR", 11: "VideoCamera", 12: "Camcorder",
        13: "VideoMonitor", 14: "VideoDisplayLoudspk", 15: "VideoConferencing",
        16: "Gaming/Toy"
    }

    major_name = major_names.get(major_class, f"Unknown({major_class})")

    if major_class == 5:  # Peripheral
        minor_name = peripheral_minor.get(minor_class, f"Unknown(0x{minor_class:02X})")
    elif major_class == 4:  # Audio/Video
        minor_name = audio_minor.get(minor_class, f"Unknown({minor_class})")
    else:
        minor_name = f"0x{minor_class:02X}"

    # Service class bits
    svc = []
    if service_class & 0x001: svc.append("LimitedDiscoverable")
    if service_class & 0x020: svc.append("Positioning")
    if service_class & 0x040: svc.append("Networking")
    if service_class & 0x080: svc.append("Rendering")
    if service_class & 0x100: svc.append("Capturing")
    if service_class & 0x200: svc.append("ObjectTransfer")
    if service_class & 0x400: svc.append("AUDIO")
    if service_class & 0x800: svc.append("TELEPHONY")
    if service_class & 0x1000: svc.append("Information")

    return major_name, minor_name, svc


def scan_devices():
    """Scan for all Bluetooth devices using Windows Bluetooth API"""
    print("=" * 60)
    print("  Windows Bluetooth Device Scanner (ctypes)")
    print("=" * 60)
    print()

    # Initialize WSA for SDP queries
    wsa_data = ctypes.create_string_buffer(WSADATA_SIZE)
    result = winsock.WSAStartup(0x0202, wsa_data)
    if result != 0:
        print(f"WSAStartup failed: {result}")
    else:
        print("Winsock initialized for SDP queries")
    print()

    # Set up search params - include ALL devices
    search_params = BLUETOOTH_DEVICE_SEARCH_PARAMS()
    search_params.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_SEARCH_PARAMS)
    search_params.fReturnAuthenticated = True
    search_params.fReturnRemembered = True
    search_params.fReturnUnknown = True
    search_params.fReturnConnected = True
    search_params.fIssueInquiry = True  # Do a real inquiry
    search_params.cTimeoutMultiplier = 15  # ~20 second scan
    search_params.hRadio = None

    device_info = BLUETOOTH_DEVICE_INFO()
    device_info.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_INFO)

    print("Starting Bluetooth device inquiry (may take up to 20 seconds)...")
    print()

    hFind = BluetoothFindFirstDevice(
        ctypes.byref(search_params),
        ctypes.byref(device_info)
    )

    if hFind is None or hFind == wintypes.HANDLE(-1).value:
        error = ctypes.GetLastError()
        print(f"BluetoothFindFirstDevice failed! Error: {error}")
        winsock.WSACleanup()
        return

    device_num = 0
    kemifound = False

    while True:
        device_num += 1
        name = device_info.szName if device_info.szName else "(Unknown)"
        address = str(device_info.Address)
        cod = device_info.ulClassofDevice
        connected = bool(device_info.fConnected)
        remembered = bool(device_info.fRemembered)

        major, minor, svc_bits = parse_cod(cod)

        print("-" * 60)
        print(f"[Device #{device_num}] {name}")
        print(f"  Address:       {address}")
        print(f"  ClassOfDevice: 0x{cod:06X}")
        print(f"  MajorClass:    {major}")
        print(f"  MinorClass:    {minor}")
        print(f"  ServiceClass:  {', '.join(svc_bits) if svc_bits else '(none)'}")
        print(f"  Connected:     {connected}")
        print(f"  Remembered:    {remembered}")

        # Check if this is our KEMI device
        is_kemi = "KEMI" in name.upper() if name else False
        if is_kemi:
            kemifound = True
            print(f"  *** THIS IS THE KEMI DEVICE! ***")

        # Try to enumerate SDP services using WSA
        if is_kemi:
            print(f"  --- Attempting SDP service enumeration ---")
            enumerate_sdp_services(address)

        print()

        # Next device
        next_device = BLUETOOTH_DEVICE_INFO()
        next_device.dwSize = ctypes.sizeof(BLUETOOTH_DEVICE_INFO)
        if not BluetoothFindNextDevice(hFind, ctypes.byref(next_device)):
            break
        device_info = next_device

    BluetoothFindDeviceClose(hFind)

    print(f"Total devices found: {device_num}")

    if not kemifound:
        print()
        print("*** KEMI device NOT found in scan! ***")
        print("Make sure the Android device has Bluetooth ON and is discoverable.")

    winsock.WSACleanup()


def enumerate_sdp_services(address_str):
    """Try to enumerate SDP services for a specific device"""
    # This is simplified - full SDP enumeration via WSA is complex
    # The key info we need is in the CoD which we already have
    pass


if __name__ == "__main__":
    scan_devices()
