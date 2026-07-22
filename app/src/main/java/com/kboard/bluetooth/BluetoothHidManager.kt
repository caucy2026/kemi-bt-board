package com.kboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context, var listener: HidStateListener?) {

    interface HidStateListener {
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
        fun onAppRegistered(registered: Boolean)
        fun onLog(message: String)
    }

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val REPORT_ID_KEYBOARD = 1
        private const val REPORT_ID_MOUSE = 2

        const val MODE_PINYIN = 0
        const val MODE_MAC = 1
        const val MODE_WIN = 2

        // Standard Keyboard + Mouse HID Report Descriptor
        private val HID_DESCRIPTOR = byteArrayOf(
            // Keyboard Report Descriptor
            0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop Ctrls)
            0x09.toByte(), 0x06.toByte(),        // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
            0x85.toByte(), 0x01.toByte(),        //   Report ID (1)
            0x05.toByte(), 0x07.toByte(),        //   Usage Page (Kbrd/Keypad)
            0x19.toByte(), 0xE0.toByte(),        //   Usage Minimum (0xE0)
            0x29.toByte(), 0xE7.toByte(),        //   Usage Maximum (0xE7)
            0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),        //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(),        //   Report Size (1)
            0x95.toByte(), 0x08.toByte(),        //   Report Count (8)
            0x81.toByte(), 0x02.toByte(),        //   Input (Data,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition)
            0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
            0x75.toByte(), 0x08.toByte(),        //   Report Size (8)
            0x81.toByte(), 0x03.toByte(),        //   Input (Const,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition)
            0x95.toByte(), 0x05.toByte(),        //   Report Count (5)
            0x75.toByte(), 0x01.toByte(),        //   Report Size (1)
            0x05.toByte(), 0x08.toByte(),        //   Usage Page (LEDs)
            0x19.toByte(), 0x01.toByte(),        //   Usage Minimum (Num Lock)
            0x29.toByte(), 0x05.toByte(),        //   Usage Maximum (Kana)
            0x91.toByte(), 0x02.toByte(),        //   Output (Data,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition,NonVolatile)
            0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
            0x75.toByte(), 0x03.toByte(),        //   Report Size (3)
            0x91.toByte(), 0x03.toByte(),        //   Output (Const,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition,NonVolatile)
            0x95.toByte(), 0x06.toByte(),        //   Report Count (6)
            0x75.toByte(), 0x08.toByte(),        //   Report Size (8)
            0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(),        //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(),        //   Usage Page (Kbrd/Keypad)
            0x19.toByte(), 0x00.toByte(),        //   Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(),        //   Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(),        //   Input (Data,Array,Abs)
            0xC0.toByte(),                       // End Collection

            // Mouse Report Descriptor
            0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop Ctrls)
            0x09.toByte(), 0x02.toByte(),        // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
            0x85.toByte(), 0x02.toByte(),        //   Report ID (2)
            0x09.toByte(), 0x01.toByte(),        //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(),        //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),        //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(),        //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
            0x75.toByte(), 0x01.toByte(),        //     Report Size (1)
            0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition)
            0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
            0x75.toByte(), 0x05.toByte(),        //     Report Size (5)
            0x81.toByte(), 0x03.toByte(),        //     Input (Const,Var,Abs,NoWrap,Linear,PreferredState,NoNullPosition)
            0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop Ctrls)
            0x09.toByte(), 0x30.toByte(),        //     Usage (X)
            0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
            0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
            0x95.toByte(), 0x02.toByte(),        //     Report Count (2)
            0x81.toByte(), 0x06.toByte(),        //     Input (Data,Var,Rel,NoWrap,Linear,PreferredState,NoNullPosition)
            0x09.toByte(), 0x38.toByte(),        //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
            0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
            0x81.toByte(), 0x06.toByte(),        //     Input (Data,Var,Rel,NoWrap,Linear,PreferredState,NoNullPosition)
            0xC0.toByte(),                       //   End Collection
            0xC0.toByte()                        // End Collection
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    
    @Volatile
    var connectedDevice: BluetoothDevice? = null
        private set
        
    @Volatile
    private var isAppRegistered = false
    
    var currentInputMode = MODE_MAC

    private val executor = Executors.newSingleThreadExecutor()

    init {
        enforceSystemHidOnlyConfiguration()
        initProfileProxy()
    }

    fun enforceSystemHidOnlyConfiguration() {
        try {
            val cr = context.contentResolver
            // 1. Permanently disable audio profiles (HFP=1, A2DP=2, A2DP_SINK=11, AVRCP=12, HFP_CLIENT=16) in system settings
            android.provider.Settings.Global.putString(cr, "bluetooth_disabled_profiles", "1,2,11,12,16")
            Log.d(TAG, "Settings.Global: Set bluetooth_disabled_profiles to 1,2,11,12,16")
            
            // 2. Force system global Class of Device to Keyboard/Mouse Combo (0x0025C0 = 2474432)
            android.provider.Settings.Global.putInt(cr, "bluetooth_class_of_device", 0x0025C0)
            android.provider.Settings.Global.putInt(cr, "bluetooth_device_class", 0x0025C0)
            Log.d(TAG, "Settings.Global: Set bluetooth_class_of_device to 0x0025C0 (2474432)")
            listener?.onLog("System HID-Only profile lock & 0x0025C0 CoD applied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Settings.Global for HID-only configuration", e)
        }
        
        // 3. Attempt to invoke hidden BluetoothAdapter APIs to disable audio profiles
        val adapter = bluetoothAdapter
        if (adapter != null) {
            val audioProfiles = intArrayOf(1, 2, 11, 12, 16)
            for (profile in audioProfiles) {
                try {
                    val setProfileEnabledMethod = adapter.javaClass.getMethod("setProfileEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    setProfileEnabledMethod.invoke(adapter, profile, false)
                    Log.d(TAG, "BluetoothAdapter: setProfileEnabled($profile, false) succeeded")
                } catch (e: Exception) {
                    // Ignore if method not found
                }
            }
        }
        
        // 4. Update local Bluetooth device class to Peripheral Keyboard/Mouse
        setLocalBluetoothClassToKeyboardMouse()
    }

    private fun initProfileProxy() {
        if (bluetoothAdapter == null) {
            listener?.onLog("Bluetooth adapter is null")
            return
        }

        val success = bluetoothAdapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as? BluetoothHidDevice
                        listener?.onLog("BluetoothHidDevice proxy connected")
                        
                        // Check if already connected on startup to populate state
                        val connectedDevs = hidDevice?.connectedDevices
                        if (!connectedDevs.isNullOrEmpty()) {
                            connectedDevice = connectedDevs[0]
                            listener?.onLog("Already connected device found on startup: ${connectedDevice?.name ?: connectedDevice?.address}")
                            listener?.onConnectionStateChanged(connectedDevice, BluetoothProfile.STATE_CONNECTED)
                        }
                        
                        registerHidApp()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        isAppRegistered = false
                        listener?.onLog("BluetoothHidDevice proxy disconnected")
                        listener?.onAppRegistered(false)
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
        if (!success) {
            listener?.onLog("Failed to get BluetoothHidDevice profile proxy")
        }
    }

    private fun registerHidApp() {
        val hid = hidDevice ?: return
        if (isAppRegistered) return

        // Set local device class to Keyboard/Mouse Peripheral (0x0025C0)
        setLocalBluetoothClassToKeyboardMouse()

        // Clean up any orphaned registration from previous runs
        try {
            hid.unregisterApp()
            listener?.onLog("Unregistered any previous HID App registration to avoid conflicts")
        } catch (e: Exception) {
            // ignore
        }

        // Wait briefly for the Bluetooth stack to unregister
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            // ignore
        }

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "kboard",
            "Virtual HID Combo App",
            "Google Antigravity",
            0x80.toByte(), // Mouse subclass (enforces Just Works pairing)
            HID_DESCRIPTOR
        )
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                super.onAppStatusChanged(device, registered)
                isAppRegistered = registered
                listener?.onLog("App Status Changed: registered = $registered")
                listener?.onAppRegistered(registered)
                if (registered) {
                    autoReconnectToLastDevice()
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                super.onConnectionStateChanged(device, state)
                Log.d(TAG, "onConnectionStateChanged: device=$device state=$state")
                listener?.onLog("Connection State Changed: device = ${device?.address}, state = ${getStateString(state)}")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device
                    if (device != null) {
                        disableAudioProfilesForDevice(device)
                    }
                    device?.address?.let { address ->
                        val prefs = context.getSharedPreferences("kboard_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_connected_device", address).apply()
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevice = null
                }
                listener?.onConnectionStateChanged(device, state)
            }

            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                super.onGetReport(device, type, id, bufferSize)
                Log.d(TAG, "onGetReport: type=$type id=$id")
                // Reply with default empty values
                hidDevice?.replyReport(device, type, id, byteArrayOf(0))
            }

            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                super.onSetReport(device, type, id, data)
                Log.d(TAG, "onSetReport: type=$type id=$id data=${data?.contentToString()}")
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                super.onVirtualCableUnplug(device)
                listener?.onLog("Virtual Cable Unplugged")
                connectedDevice = null
                listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
            }
        }

        val success = hid.registerApp(sdpSettings, null, null, executor, callback)
        if (!success) {
            listener?.onLog("Failed to register HID App")
        }
    }

    fun autoReconnectToLastDevice() {
        val hid = hidDevice ?: return
        val adapter = bluetoothAdapter ?: return
        val prefs = context.getSharedPreferences("kboard_prefs", Context.MODE_PRIVATE)
        val lastAddress = prefs.getString("last_connected_device", null) ?: return
        
        Log.d(TAG, "Attempting auto-reconnect to last connected device: $lastAddress")
        try {
            val device = adapter.getRemoteDevice(lastAddress)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                disableAudioProfilesForDevice(device)
                listener?.onLog("Auto-connecting to paired host: ${device.name ?: device.address}...")
                executor.execute {
                    try {
                        Thread.sleep(500) // Brief delay to let the Bluetooth stack settle
                    } catch (e: Exception) {}
                    
                    if (connectedDevice == null && getConnectedDevicesDirectly().isEmpty()) {
                        val success = hid.connect(device)
                        Log.d(TAG, "hidDevice.connect to $lastAddress returned: $success")
                        listener?.onLog("Initiating connection to $lastAddress: $success")
                    }
                }
            } else {
                Log.d(TAG, "Last device is no longer bonded/paired.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-reconnect", e)
        }
    }

    fun onAclConnected(device: BluetoothDevice) {
        Log.d(TAG, "ACL Physical Link Connected: ${device.address}")
        disableAudioProfilesForDevice(device)
        
        val prefs = context.getSharedPreferences("kboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_connected_device", device.address).apply()
        
        executor.execute {
            try { Thread.sleep(400) } catch (e: Exception) {}
            val directDevs = getConnectedDevicesDirectly()
            if (directDevs.isNotEmpty()) {
                connectedDevice = directDevs[0]
                listener?.onConnectionStateChanged(connectedDevice, BluetoothProfile.STATE_CONNECTED)
            } else if (connectedDevice == null) {
                val hid = hidDevice
                if (hid != null) {
                    val success = hid.connect(device)
                    Log.d(TAG, "ACL connected, initiating HID handshake to ${device.address}: $success")
                }
            }
        }
    }

    fun onAclDisconnected(device: BluetoothDevice) {
        Log.d(TAG, "ACL Physical Link Disconnected: ${device.address}")
        if (connectedDevice?.address == device.address) {
            connectedDevice = null
            listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
        }
    }

    // Reconnect loop methods removed from BluetoothHidManager and moved to MainActivity (foreground lifecycle-bound)

    private fun setLocalBluetoothClassToKeyboardMouse() {
        val adapter = bluetoothAdapter ?: return
        try {
            // Class value for Keyboard/Mouse Combo (Peripheral)
            val peripheralClassVal = 0x0025C0
            
            // Create BluetoothClass instance via reflection
            val bluetoothClassClass = Class.forName("android.bluetooth.BluetoothClass")
            val constructor = bluetoothClassClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            val bluetoothClassInstance = constructor.newInstance(peripheralClassVal)
            
            // Invoke setBluetoothClass on BluetoothAdapter
            val setBluetoothClassMethod = adapter.javaClass.getMethod("setBluetoothClass", bluetoothClassClass)
            val success = setBluetoothClassMethod.invoke(adapter, bluetoothClassInstance) as Boolean
            Log.d(TAG, "setBluetoothClass to Keyboard/Mouse (0x0025C0) returned: $success")
            listener?.onLog("Set Bluetooth Class to Keyboard/Mouse: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Bluetooth Class via reflection", e)
            listener?.onLog("Failed to set Bluetooth Class: ${e.message}")
        }
    }

    private fun disableAudioProfilesForDevice(device: BluetoothDevice) {
        val adapter = bluetoothAdapter ?: return
        val profilesToDisable = intArrayOf(
            2,  // BluetoothProfile.A2DP (Audio Source / Music)
            11, // BluetoothProfile.A2DP_SINK (A2DP Audio Receiver)
            1,  // BluetoothProfile.HEADSET (HFP / Phone Audio)
            16  // BluetoothProfile.HEADSET_CLIENT (HFP Client Receiver)
        )
        for (profileId in profilesToDisable) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            // 1. Actively disconnect the audio connection to the Mac
                            try {
                                val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                disconnectMethod.invoke(proxy, device)
                                Log.d(TAG, "AudioProfile: Active disconnect called on profile $profile for ${device.address}")
                            } catch (e: Exception) {
                                // ignore
                            }
                            
                            // 2. Set priority/connection policy to Off/Forbidden (0) to permanently block auto-reconnection of audio
                            try {
                                val setConnectionPolicy = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                                setConnectionPolicy.invoke(proxy, device, 0) // CONNECTION_POLICY_FORBIDDEN = 0
                                Log.d(TAG, "AudioProfile: setConnectionPolicy(device, 0) succeeded for profile $profile")
                            } catch (ex: Exception) {
                                try {
                                    val setPriority = proxy.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                                    setPriority.invoke(proxy, device, 0) // PRIORITY_OFF = 0
                                    Log.d(TAG, "AudioProfile: setPriority(device, 0) succeeded for profile $profile")
                                } catch (e: Exception) {
                                    Log.e(TAG, "AudioProfile: Failed to disable profile $profile connection", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "AudioProfile: Error in service listener for profile $profile", e)
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }
                    
                    override fun onServiceDisconnected(profile: Int) {}
                }, profileId)
            } catch (e: Exception) {
                Log.e(TAG, "Error obtaining profile proxy for $profileId", e)
            }
        }
    }

    private fun getStateString(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN ($state)"
        }
    }

    fun getConnectedDevicesDirectly(): List<BluetoothDevice> {
        return hidDevice?.connectedDevices ?: emptyList()
    }

    fun disconnectFromHost() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        Log.d(TAG, "Actively disconnecting from host: ${device.address}")
        try {
            hid.disconnect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    fun clearAllPairsAndDisconnect() {
        val adapter = bluetoothAdapter ?: return
        val hid = hidDevice
        
        // 1. Actively disconnect
        connectedDevice?.let { device ->
            try {
                hid?.disconnect(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            }
        }
        connectedDevice = null
        
        // 2. Clear stored last device in SharedPreferences
        val prefs = context.getSharedPreferences("kboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("last_connected_device").apply()
        
        // 3. Unpair all bonded devices using reflection to call hidden api removeBond()
        try {
            val bondedDevices = adapter.bondedDevices
            if (bondedDevices != null) {
                for (device in bondedDevices) {
                    listener?.onLog("Unpairing device: ${device.name ?: device.address}...")
                    val removeBondMethod = device.javaClass.getMethod("removeBond")
                    removeBondMethod.invoke(device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unpairing devices", e)
        }
        
        // 4. Force scan mode to CONNECTABLE_DISCOVERABLE (23) again
        try {
            val setScanModeMethod = adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            setScanModeMethod.invoke(adapter, 23)
            listener?.onLog("Bluetooth scan mode set to discoverable (23)")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting scan mode", e)
        }
        
        // 5. Force status callback to transition UI to DISCONNECTED since removeBond blocks system framework callbacks
        listener?.onConnectionStateChanged(null, BluetoothProfile.STATE_DISCONNECTED)
        
        // 6. Restart Bluetooth stack to reload clean SDP without audio UUIDs (0x110C / 0x110E)
        resetBluetoothStackToApplyHidOnly()
        
        listener?.onLog("All pairings and connections cleared. Device is now discoverable.")
    }

    fun resetBluetoothStackToApplyHidOnly() {
        val adapter = bluetoothAdapter ?: return
        executor.execute {
            try {
                listener?.onLog("Restarting Bluetooth stack to reload clean HID-only SDP records...")
                adapter.disable()
                var retries = 0
                while (adapter.state != BluetoothAdapter.STATE_OFF && retries < 20) {
                    Thread.sleep(200)
                    retries++
                }
                Thread.sleep(500)
                adapter.enable()
                listener?.onLog("Bluetooth stack restarted. Pure HID SDP records active!")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting bluetooth adapter", e)
            }
        }
    }

    fun unregister() {
        disconnectFromHost()
        try { Thread.sleep(300) } catch (e: Exception) {}
        if (isAppRegistered) {
            hidDevice?.unregisterApp()
            isAppRegistered = false
        }
    }

    // Sends Mouse report
    // Report layout:
    // data[0] -> buttons (bit 0 = left click, bit 1 = right click, bit 2 = middle click)
    // data[1] -> x displacement (-127 to 127)
    // data[2] -> y displacement (-127 to 127)
    // data[3] -> wheel scroll (-127 to 127)
    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        val reportData = byteArrayOf(buttons, x, y, wheel)
        val success = hid.sendReport(device, REPORT_ID_MOUSE, reportData)
        if (!success) {
            Log.e(TAG, "Failed to send mouse report")
        }
    }

    // Sends Keyboard report
    // Report layout:
    // data[0] -> modifiers (bit 0 = LCtrl, bit 1 = LShift, bit 2 = LAlt, bit 3 = LGUI, bit 4 = RCtrl, bit 5 = RShift, bit 6 = RAlt, bit 7 = RGUI)
    // data[1] -> reserved (0)
    // data[2..7] -> 6 keycodes (array of up to 6 keycodes currently pressed)
    fun sendKeyboardReport(modifiers: Byte, keycodes: ByteArray) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        val reportData = ByteArray(8)
        reportData[0] = modifiers
        reportData[1] = 0 // Reserved
        for (i in 0 until minOf(keycodes.size, 6)) {
            reportData[2 + i] = keycodes[i]
        }
        val success = hid.sendReport(device, REPORT_ID_KEYBOARD, reportData)
        if (!success) {
            Log.e(TAG, "Failed to send keyboard report")
        }
    }

    // Type a single ASCII character by translating and sending pressed then released key reports
    fun sendAsciiCharacter(char: Char) {
        val mapping = KeycodeTranslator.getHidScanCode(char) ?: return
        val modifiers = mapping.first
        val keycode = mapping.second

        // Key down
        sendKeyboardReport(modifiers, byteArrayOf(keycode))
        
        // Wait briefly (keyboard repeat safety)
        try {
            Thread.sleep(10)
        } catch (e: InterruptedException) {
            // ignore
        }

        // Key up (empty keys)
        sendKeyboardReport(0, byteArrayOf(0))
    }

    // Sends a keystroke (modifier + keycode) followed by key release
    fun sendKeystroke(modifiers: Byte, keycode: Byte) {
        executor.execute {
            sendKeyboardReport(modifiers, byteArrayOf(keycode))
            try {
                Thread.sleep(15)
            } catch (e: InterruptedException) {
                // ignore
            }
            sendKeyboardReport(0, byteArrayOf(0))
        }
    }

    // Type a string of ASCII characters sequentially
    fun sendAsciiString(text: String) {
        executor.execute {
            for (char in text) {
                sendAsciiCharacter(char)
                try {
                    Thread.sleep(15) // small delay between keystrokes
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    // Sends a Unicode character on macOS using Option + 4 Hex digits
    private fun sendUnicodeCharacterMac(char: Char) {
        val hex = char.code.toString(16).padStart(4, '0').lowercase()
        val altMod: Byte = 0x04 // Left Alt (Option)
        
        // 1. Hold Option (Alt)
        sendKeyboardReport(altMod, byteArrayOf(0))
        try { Thread.sleep(10) } catch (e: Exception) {}
        
        // 2. Type 4 hex digits
        for (hexChar in hex) {
            val mapping = KeycodeTranslator.getHidScanCode(hexChar) ?: continue
            val scancode = mapping.second
            
            // Press digit
            sendKeyboardReport(altMod, byteArrayOf(scancode))
            try { Thread.sleep(5) } catch (e: Exception) {}
            // Release digit, keep Alt
            sendKeyboardReport(altMod, byteArrayOf(0))
            try { Thread.sleep(5) } catch (e: Exception) {}
        }
        
        // 3. Release Option
        sendKeyboardReport(0, byteArrayOf(0))
        try { Thread.sleep(10) } catch (e: Exception) {}
    }

    // Sends a Unicode character on Windows using Alt + Numpad + + 4 Hex digits
    private fun sendUnicodeCharacterWin(char: Char) {
        val hex = char.code.toString(16).padStart(4, '0').lowercase()
        val altMod: Byte = 0x04 // Left Alt
        val keypadPlus: Byte = 0x57 // Keypad +
        
        // 1. Hold Alt
        sendKeyboardReport(altMod, byteArrayOf(0))
        try { Thread.sleep(15) } catch (e: Exception) {}
        
        // 2. Press Keypad +
        sendKeyboardReport(altMod, byteArrayOf(keypadPlus))
        try { Thread.sleep(12) } catch (e: Exception) {}
        sendKeyboardReport(altMod, byteArrayOf(0))
        try { Thread.sleep(12) } catch (e: Exception) {}
        
        // 3. Type 4 hex digits (Windows requires numpad keys for digits 0-9)
        for (hexChar in hex) {
            val scancode: Byte = when (hexChar) {
                in '0'..'9' -> {
                    if (hexChar == '0') {
                        0x62.toByte() // Keypad 0
                    } else {
                        (0x59 + (hexChar - '1')).toByte() // Keypad 1-9
                    }
                }
                in 'a'..'f' -> {
                    (0x04 + (hexChar - 'a')).toByte() // Standard a-f
                }
                else -> continue
            }
            
            // Press digit
            sendKeyboardReport(altMod, byteArrayOf(scancode))
            try { Thread.sleep(10) } catch (e: Exception) {}
            // Release digit, keep Alt
            sendKeyboardReport(altMod, byteArrayOf(0))
            try { Thread.sleep(10) } catch (e: Exception) {}
        }
        
        // 4. Release Alt
        sendKeyboardReport(0, byteArrayOf(0))
        try { Thread.sleep(15) } catch (e: Exception) {}
    }

    // Sends a Unicode string by splitting ASCII and Unicode characters
    fun sendUnicodeString(text: String, mode: Int) {
        executor.execute {
            var inMacUnicodeBlock = false
            val altMod: Byte = 0x04 // Left Alt (Option)
            
            for (char in text) {
                if (char.code in 0..127) {
                    // If we were in a Unicode block, release Option first
                    if (inMacUnicodeBlock) {
                        sendKeyboardReport(0, byteArrayOf(0))
                        try { Thread.sleep(25) } catch (e: Exception) {} // Increased to 25ms to let OS register key release
                        inMacUnicodeBlock = false
                    }
                    
                    sendAsciiCharacter(char)
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                } else {
                    if (mode == MODE_MAC) {
                        // If not yet in Unicode block, hold Option
                        if (!inMacUnicodeBlock) {
                            sendKeyboardReport(altMod, byteArrayOf(0))
                            try { Thread.sleep(15) } catch (e: Exception) {} // Increased to 15ms
                            inMacUnicodeBlock = true
                        }
                        
                        val hex = char.code.toString(16).padStart(4, '0').lowercase()
                        for (hexChar in hex) {
                            val mapping = KeycodeTranslator.getHidScanCode(hexChar) ?: continue
                            val scancode = mapping.second
                            
                            // Press digit
                            sendKeyboardReport(altMod, byteArrayOf(scancode))
                            try { Thread.sleep(5) } catch (e: Exception) {}
                            // Release digit, keep Alt
                            sendKeyboardReport(altMod, byteArrayOf(0))
                            try { Thread.sleep(5) } catch (e: Exception) {}
                        }
                    } else if (mode == MODE_WIN) {
                        sendUnicodeCharacterWin(char)
                    }
                }
            }
            
            // If we finish and are still in a Unicode block, release Option
            if (inMacUnicodeBlock) {
                sendKeyboardReport(0, byteArrayOf(0))
                try { Thread.sleep(25) } catch (e: Exception) {} // Increased to 25ms
            }
        }
    }

    // Sends macOS bypass shortcut sequences (Escape followed by Cmd + W)
    fun sendBypassMacKeyboardSetup() {
        executor.execute {
            // 1. Send Escape (0x29)
            sendKeyboardReport(0, byteArrayOf(0x29))
            try { Thread.sleep(15) } catch (e: Exception) {}
            sendKeyboardReport(0, byteArrayOf(0))
            
            try { Thread.sleep(80) } catch (e: Exception) {}
            
            // 2. Send Left GUI (0x08) + W (0x1A)
            sendKeyboardReport(0x08, byteArrayOf(0x1A))
            try { Thread.sleep(15) } catch (e: Exception) {}
            sendKeyboardReport(0, byteArrayOf(0))
        }
    }
}
