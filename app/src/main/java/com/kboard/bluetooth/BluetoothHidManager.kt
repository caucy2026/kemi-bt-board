package com.kboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context, var listener: HidStateListener?) {

    interface HidStateListener {
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
        fun onAppRegistered(registered: Boolean)
        fun onLog(message: String)
        fun onBtRestartState(isRestarting: Boolean) {}
        fun onNumLockStateChanged(isOn: Boolean) {}  // NumLock LED 状态变化
    }

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val REPORT_ID_KEYBOARD = 1
        private const val REPORT_ID_MOUSE = 2

        const val MODE_PINYIN = 0
        const val MODE_MAC = 1
        const val MODE_WIN = 2

        // A2DP profile bitmask (per a2dp.md): Sink=4, Source=2048, Both=2052
        private const val A2DP_BOTH_DISABLED = 2052
        private const val A2DP_SOURCE_DISABLED = 2048  // exit: disable Source only, re-enable Sink
        private const val A2DP_DYNAMIC_PROP = "persist.sys_im.blutooth.is_a2dp_dynamic"

        // Standard Keyboard + Mouse HID Report Descriptor
        // Keypad keys (including Keypad+ 0x57) are part of Usage Page 0x07
        // and are already covered by the keyboard array's 0x00..0x65 range.
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
    @Volatile
    var isManualDisconnect = false
    @Volatile
    private var isBtResetting = false  // true during clear/reset to suppress transient failure UI

    // NumLock LED 状态
    @Volatile
    var isNumLockOn = false
        private set

    // NumLock 主动查询：0=idle, 1=等待LED报告, 2=等待还原确认
    @Volatile
    private var numLockQueryPhase = 0
    private val queryHandler = Handler(Looper.getMainLooper())
    private var queryTimeoutRunnable: Runnable? = null

    private val executor = Executors.newSingleThreadExecutor()

    // 监听蓝牙开关状态广播，替代 polling sleep
    private var btStateReceiver: BroadcastReceiver? = null
    @Volatile
    private var btStateLatch: CountDownLatch? = null

    init {
        // 注册蓝牙状态广播
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        btStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) ?: -1
                val prev = intent?.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1) ?: -1
                Log.d(TAG, "BT State: $prev → $state")
                btStateLatch?.countDown()
            }
        }
        context.registerReceiver(btStateReceiver, filter)

        // Step 1: MUST set dynamic switch BEFORE writing disabled_profiles (per a2dp.md)
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java)
                .invoke(null, A2DP_DYNAMIC_PROP, "true")
            Log.d(TAG, "SystemProperties.set $A2DP_DYNAMIC_PROP = true")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set $A2DP_DYNAMIC_PROP: ${e.message}")
        }

        // Step 2: Write disabled_profiles bitmask + CoD + disable audio via reflection
        enforceSystemHidOnlyConfiguration()

        // Step 3: Init HID profile proxy (NO BT restart on enter)
        initProfileProxy()
    }

    /** 等待蓝牙到达指定状态（事件驱动，不轮询） */
    private fun waitForBtState(targetState: Int, timeoutSec: Long = 10): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (adapter.state == targetState) return true  // 已经到达
        btStateLatch = CountDownLatch(1)
        return try {
            btStateLatch?.await(timeoutSec, TimeUnit.SECONDS) ?: false
        } catch (e: InterruptedException) {
            false
        } finally {
            btStateLatch = null
        }
    }

    fun destroy() {
        btStateReceiver?.let { context.unregisterReceiver(it) }
        btStateReceiver = null
    }

    fun enforceSystemHidOnlyConfiguration() {
        try {
            val cr = context.contentResolver
            // 1. Disable A2DP via bitmask 2052 (per a2dp.md: Sink=4 + Source=2048)
            android.provider.Settings.Global.putInt(cr, "bluetooth_disabled_profiles", A2DP_BOTH_DISABLED)
            val verify = android.provider.Settings.Global.getInt(cr, "bluetooth_disabled_profiles", -1)
            Log.d(TAG, "putInt bluetooth_disabled_profiles=$A2DP_BOTH_DISABLED, read back=$verify")
            if (verify != A2DP_BOTH_DISABLED) {
                Log.e(TAG, "FAILED to verify bluetooth_disabled_profiles! wrote=$A2DP_BOTH_DISABLED, got=$verify")
            }
            
            // 2. Force system global Class of Device to Keyboard Peripheral (0x000540)
            android.provider.Settings.Global.putInt(cr, "bluetooth_class_of_device", 0x000540)
            android.provider.Settings.Global.putInt(cr, "bluetooth_device_class", 0x000540)
            Log.d(TAG, "putInt bluetooth_class_of_device = 0x000540 (Keyboard)")
        } catch (e: Exception) {
            Log.e(TAG, "enforceSystemHidOnlyConfiguration failed: ${e.message}", e)
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

    fun restoreA2dpAndRestartBt() {
        try {
            val cr = context.contentResolver
            android.provider.Settings.Global.putInt(cr, "bluetooth_disabled_profiles", A2DP_SOURCE_DISABLED)
            Log.d(TAG, "restoreA2dp: putInt bluetooth_disabled_profiles=$A2DP_SOURCE_DISABLED (disable Source, keep Sink)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreA2dp failed: ${e.message}", e)
        }
    }

    private fun performBtOffOnCycle() {
        val adapter = bluetoothAdapter ?: run { initProfileProxy(); return }
        executor.execute {
            try {
                listener?.onBtRestartState(true)
                if (isAppRegistered) { try { hidDevice?.unregisterApp() } catch (_: Exception) {}; isAppRegistered = false }

                // BT OFF → 等 STATE_OFF 广播
                Log.d(TAG, "BT OFF..."); adapter.disable()
                val offOk = waitForBtState(BluetoothAdapter.STATE_OFF, 10)
                Log.d(TAG, "BT OFF done (ok=$offOk, state=${adapter.state})")

                // BT ON → 等 STATE_ON 广播
                Log.d(TAG, "BT ON..."); adapter.enable()
                val onOk = waitForBtState(BluetoothAdapter.STATE_ON, 10)
                Log.d(TAG, "BT ON done (ok=$onOk, state=${adapter.state})")

                enforceSystemHidOnlyConfiguration()
                initProfileProxy()
                listener?.onBtRestartState(false)
            } catch (e: Exception) {
                Log.e(TAG, "BT OFF/ON cycle error: ${e.message}", e)
                listener?.onBtRestartState(false)
                initProfileProxy()
            }
        }
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
                        // Clear reset flag & hide spinner when HID proxy reconnects after clear/reset
                        if (isBtResetting) {
                            isBtResetting = false
                            listener?.onBtRestartState(false)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        isAppRegistered = false
                        listener?.onLog("BluetoothHidDevice proxy disconnected")
                        // Suppress failure UI during intentional BT reset (clear/reset flow)
                        if (!isBtResetting) {
                            listener?.onAppRegistered(false)
                        }
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
        if (!success) {
            listener?.onLog("Failed to get BluetoothHidDevice profile proxy")
            listener?.onAppRegistered(false)
        }
    }

    fun reinitProfileProxy() {
        softRestartBluetoothStackAndReinit()
    }

    fun softRestartBluetoothStackAndReinit() {
        val adapter = bluetoothAdapter ?: return
        executor.execute {
            try {
                listener?.onBtRestartState(true)
                listener?.onLog("Soft-restarting Bluetooth adapter...")
                if (isAppRegistered) {
                    try { hidDevice?.unregisterApp() } catch (e: Exception) {}
                    isAppRegistered = false
                }

                // BT OFF → 等广播
                adapter.disable()
                waitForBtState(BluetoothAdapter.STATE_OFF, 10)
                Log.d(TAG, "Soft restart: BT OFF confirmed, enabling...")

                // BT ON → 等广播
                adapter.enable()
                waitForBtState(BluetoothAdapter.STATE_ON, 10)
                Log.d(TAG, "Soft restart: BT ON confirmed, reinitializing...")

                enforceSystemHidOnlyConfiguration()
                initProfileProxy()
                listener?.onBtRestartState(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error in softRestartBluetoothStackAndReinit", e)
                listener?.onBtRestartState(false)
                initProfileProxy()
            }
        }
    }

    private fun registerHidApp() {
        val hid = hidDevice ?: return
        if (isAppRegistered) return

        // Set local device class to Keyboard/Mouse Peripheral (0x0025C0)
        setLocalBluetoothClassToKeyboardMouse()

        // 整个注册流程移到后台，不阻塞主线程（避免 Bluetooth 服务初始化卡死）
        executor.execute {
            // Clean up any orphaned registration from previous runs
            try {
                hid.unregisterApp()
                listener?.onLog("Unregistered any previous HID App registration to avoid conflicts")
            } catch (e: Exception) {
                // ignore
            }

            // Wait briefly for the Bluetooth stack to unregister
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                // ignore
            }

            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "kboard",
                "Virtual HID Keyboard",
                "Google Antigravity",
                0x40.toByte(),
                HID_DESCRIPTOR
            )
            val callback = createHidCallback()
            
            var success = hid.registerApp(sdpSettings, null, null, executor, callback)
            if (!success) {
                listener?.onLog("Failed to register HID App, retrying in 1s...")
                try { Thread.sleep(1000) } catch (_: Exception) {}
                success = hid.registerApp(sdpSettings, null, null, executor, callback)
                if (!success) {
                    listener?.onLog("Failed to register HID App (2nd attempt)")
                }
            }
        }
    }
    
    /** 提取 HID callback 创建，避免 registerHidApp 过长 */
    private fun createHidCallback(): BluetoothHidDevice.Callback {
        return object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                super.onAppStatusChanged(device, registered)
                isAppRegistered = registered
                listener?.onLog("App Status Changed: registered = $registered")
                listener?.onAppRegistered(registered)
                if (registered && !isManualDisconnect) {
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
                    setScanMode(21)
                    Log.d(TAG, "ScanMode set to CONNECTABLE (21)")
                    // 主动查询 NumLock：发一次 NumLock → 读 LED → 再发一次还原
                    scheduleNumLockQuery()
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevice = null
                    // Disconnected: restore discoverable mode so other hosts can find us
                    setScanMode(23)  // SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    Log.d(TAG, "ScanMode set to CONNECTABLE_DISCOVERABLE (23) — device is free for new connections")
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
                val hex = data?.joinToString(" ") { String.format("%02X", it) } ?: "null"
                Log.d(TAG, "onSetReport: type=$type (${if(type==BluetoothHidDevice.REPORT_TYPE_OUTPUT) "OUTPUT" else if(type==BluetoothHidDevice.REPORT_TYPE_INPUT) "INPUT" else "FEATURE"}), id=$id, data=[$hex]")
                // 解析 OUTPUT 报告（type=2）：LED 状态字节
                if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                    id.toInt() == REPORT_ID_KEYBOARD &&
                    data != null && data.size == 1
                ) {
                    val reportedNumLock = (data[0].toInt() and 1) != 0
                    Log.d(TAG, "NumLock LED from host: $reportedNumLock (data=0x${String.format("%02X", data[0])}) phase=$numLockQueryPhase")
                    
                    if (numLockQueryPhase == 1) {
                        // 查询阶段1：收到了第一次toggle后的LED状态
                        // 主机报告的 = 被我们toggle后的新状态 → 原始状态 = !reportedNumLock
                        val originalState = !reportedNumLock
                        isNumLockOn = originalState
                        listener?.onNumLockStateChanged(isNumLockOn)
                        Log.d(TAG, "NumLock query: reported=$reportedNumLock → original=$originalState, restoring...")
                        
                        // 发第二次 NumLock 还原
                        numLockQueryPhase = 2
                        queryTimeoutRunnable?.let { queryHandler.removeCallbacks(it) }
                        executor.execute {
                            sendKeyboardReport(0, byteArrayOf(0x53))
                            try { Thread.sleep(80) } catch (_: Exception) {}
                            sendKeyboardReport(0, byteArrayOf())
                            Log.d(TAG, "NumLock query: restore toggle sent")
                        }
                        // 等还原的 LED 确认
                        queryTimeoutRunnable = Runnable {
                            Log.w(TAG, "NumLock query: restore timeout")
                            numLockQueryPhase = 0
                        }
                        queryHandler.postDelayed(queryTimeoutRunnable!!, 2000)
                    } else if (numLockQueryPhase == 2) {
                        // 查询阶段2：收到了还原toggle后的LED确认
                        // 理论上 reportedNumLock 应该 == originalState（已还原）
                        Log.d(TAG, "NumLock query: restore confirmed, reported=$reportedNumLock, local=$isNumLockOn")
                        numLockQueryPhase = 0
                        queryTimeoutRunnable?.let { queryHandler.removeCallbacks(it) }
                    } else {
                        // 非查询状态下的正常 LED 更新
                        if (reportedNumLock != isNumLockOn) {
                            isNumLockOn = reportedNumLock
                            listener?.onNumLockStateChanged(isNumLockOn)
                        }
                    }
                }
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                super.onVirtualCableUnplug(device)
                listener?.onLog("Virtual Cable Unplugged")
                connectedDevice = null
                listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
            }
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
        
        executor.execute {
            try { Thread.sleep(400) } catch (e: Exception) {}
            val directDevs = getConnectedDevicesDirectly()
            if (directDevs.isNotEmpty()) {
                connectedDevice = directDevs[0]
                listener?.onConnectionStateChanged(connectedDevice, BluetoothProfile.STATE_CONNECTED)
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

    // Set Bluetooth scan mode via reflection (21=CONNECTABLE, 23=CONNECTABLE_DISCOVERABLE)
    private fun setScanMode(mode: Int) {
        val adapter = bluetoothAdapter ?: return
        try {
            val m = adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            m.invoke(adapter, mode)
        } catch (e: Exception) {
            Log.e(TAG, "setScanMode($mode) failed: ${e.message}")
        }
    }

    private fun setLocalBluetoothClassToKeyboardMouse() {
        val adapter = bluetoothAdapter ?: return
        try {
            // Class value for Keyboard Peripheral (0x000540)
            val peripheralClassVal = 0x000540
            
            // Create BluetoothClass instance via reflection
            val bluetoothClassClass = Class.forName("android.bluetooth.BluetoothClass")
            val constructor = bluetoothClassClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            val bluetoothClassInstance = constructor.newInstance(peripheralClassVal)
            
            // Invoke setBluetoothClass on BluetoothAdapter
            val setBluetoothClassMethod = adapter.javaClass.getMethod("setBluetoothClass", bluetoothClassClass)
            val success = setBluetoothClassMethod.invoke(adapter, bluetoothClassInstance) as Boolean
            Log.d(TAG, "setBluetoothClass to Keyboard (0x000540) returned: $success")
            listener?.onLog("Set Bluetooth Class to Keyboard (0x000540): $success")
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
        isManualDisconnect = true
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        Log.d(TAG, "Actively disconnecting from host: ${device.address}")
        try {
            hid.disconnect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    /** 手动发送 NumLock 键，纯本地状态跟踪（onSetReport 不工作，无法从主机读取 LED） */
    fun sendNumLockToggle() {
        // 如果正在查询中，忽略手动操作
        if (numLockQueryPhase != 0) {
            Log.d(TAG, "NumLock toggle ignored: query in progress (phase=$numLockQueryPhase)")
            return
        }
        isNumLockOn = !isNumLockOn
        listener?.onNumLockStateChanged(isNumLockOn)
        Log.d(TAG, "NumLock toggle: local=$isNumLockOn")
        executor.execute {
            sendKeyboardReport(0, byteArrayOf(0x53))
            try { Thread.sleep(80) } catch (_: Exception) {}
            sendKeyboardReport(0, byteArrayOf())
        }
    }

    /** 连接后主动查询 NumLock：发NumLock → 等LED → 发NumLock还原 */
    private fun scheduleNumLockQuery() {
        if (numLockQueryPhase != 0) return  // 已在查询中
        queryHandler.postDelayed({
            if (connectedDevice == null) return@postDelayed  // 已断开
            numLockQueryPhase = 1
            Log.d(TAG, "NumLock query: phase 1 — sending toggle...")
            // 发送 NumLock（第一次）
            executor.execute {
                sendKeyboardReport(0, byteArrayOf(0x53))
                try { Thread.sleep(80) } catch (_: Exception) {}
                sendKeyboardReport(0, byteArrayOf())
                Log.d(TAG, "NumLock query: toggle sent, waiting for LED report...")
            }
            // 超时保护：3秒内没收到 LED 报告，发一次还原并放弃
            queryTimeoutRunnable = Runnable {
                if (numLockQueryPhase == 1 || numLockQueryPhase == 2) {
                    Log.w(TAG, "NumLock query: timeout, restoring blindly...")
                    executor.execute {
                        sendKeyboardReport(0, byteArrayOf(0x53))
                        try { Thread.sleep(80) } catch (_: Exception) {}
                        sendKeyboardReport(0, byteArrayOf())
                    }
                    numLockQueryPhase = 0
                }
            }
            queryHandler.postDelayed(queryTimeoutRunnable!!, 3000)
        }, 800)  // 等连接稳定 800ms 后再查询
    }

    fun clearAllPairsAndDisconnect() {
        val adapter = bluetoothAdapter ?: return
        val hid = hidDevice
        isManualDisconnect = true
        isBtResetting = true  // suppress all transient failure UI during this flow
        
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
                isBtResetting = true
                listener?.onBtRestartState(true)
                listener?.onLog("Restarting Bluetooth stack to reload clean HID-only SDP records...")

                // BT OFF → 等广播
                adapter.disable()
                waitForBtState(BluetoothAdapter.STATE_OFF, 10)

                // BT ON → 等广播
                adapter.enable()
                waitForBtState(BluetoothAdapter.STATE_ON, 10)

                enforceSystemHidOnlyConfiguration()
                initProfileProxy()
                listener?.onLog("Bluetooth stack restarted. HID re-registered.")
                // isBtResetting=false + onBtRestartState(false) handled in onServiceConnected
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting bluetooth adapter", e)
                isBtResetting = false
                listener?.onBtRestartState(false)
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
        val hid = hidDevice ?: run {
            Log.e(TAG, "KB RPT SKIP: hidDevice=null mod=0x${modifiers.toString(16)} keys=${keycodes.map { String.format("0x%02X", it) }}")
            return
        }
        val device = connectedDevice ?: run {
            Log.e(TAG, "KB RPT SKIP: connectedDevice=null mod=0x${modifiers.toString(16)} keys=${keycodes.map { String.format("0x%02X", it) }}")
            return
        }
        val reportData = ByteArray(8)
        reportData[0] = modifiers
        reportData[1] = 0 // Reserved
        for (i in 0 until minOf(keycodes.size, 6)) {
            reportData[2 + i] = keycodes[i]
        }
        val success = hid.sendReport(device, REPORT_ID_KEYBOARD, reportData)
        if (!success) {
            Log.e(TAG, "KB RPT FAIL: mod=0x${modifiers.toString(16)} keys=${keycodes.map { String.format("0x%02X", it) }} report=[${reportData.joinToString { String.format("%02X", it) }}]")
        } else {
            Log.v(TAG, "KB RPT OK:  mod=0x${modifiers.toString(16)} keys=${keycodes.map { String.format("0x%02X", it) }}")
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
    fun sendKeystroke(modifiers: Byte, keycode: Byte, holdTimeMs: Long = 15L) {
        executor.execute {
            sendKeyboardReport(modifiers, byteArrayOf(keycode))
            try {
                Thread.sleep(holdTimeMs)
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

    // ===================================================================
    // Windows Unicode 输入：Alt + Keypad+ + Hex（EnableHexNumpad=1 标准方式）
    //
    // 前提：Windows 注册表 HKCU\Control Panel\Input Method\EnableHexNumpad=1 (REG_SZ)
    //
    // 序列：NumLock ON → 按住 Alt → 按 Keypad+ → 松开 Keypad+ → 输入 4 位 hex
    //        → 松开 Alt → NumLock 还原
    //
    // hex 0-9、a-f 均使用主键盘键码；Windows 接收器负责拦截并转换 Unicode。
    // ===================================================================
    private var winUniFailCount = 0
    private var winUniTotalCount = 0
    private val winHexModifierDelayMs = 3L
    private val winHexKeyDelayMs = 5L
    private val winHexCommitDelayMs = 5L

    private fun sendUnicodeCharacterWin(char: Char) {
        val hex = char.code.toString(16).padStart(4, '0')  // e.g. "4f60"
        winUniTotalCount++
        Log.d(TAG, "WIN-HEX: char=U+${hex.uppercase()}('$char') hex=$hex")

        val altMod: Byte = 0x04   // Left Alt

        // === 1. 按住 Alt + 按 Keypad+ 进入 hex 模式 ===
        sendKeyboardReport(altMod, byteArrayOf())           // 按住 Alt
        try { Thread.sleep(winHexModifierDelayMs) } catch (_: Exception) {}
        sendKeyboardReport(altMod, byteArrayOf(0x57))       // Keypad +
        try { Thread.sleep(winHexKeyDelayMs) } catch (_: Exception) {}

        // === 2. 使用主键盘输入 4 位 hex ===
        // 相邻不同键直接切换报告，上一键会随报告自动释放；只有连续相同
        // hex 位才插入空报告，避免 Windows 将其视为一次长按。
        var previousSc: Byte? = null
        for (ch in hex) {
            val sc: Byte = when (ch) {
                '0' -> 0x27
                in '1'..'9' -> (0x1E + (ch - '1')).toByte()
                in 'a'..'f' -> (0x04 + (ch - 'a')).toByte()
                else -> continue
            }
            if (sc == previousSc) {
                sendKeyboardReport(altMod, byteArrayOf())
                try { Thread.sleep(winHexKeyDelayMs) } catch (_: Exception) {}
            }
            sendKeyboardReport(altMod, byteArrayOf(sc))
            try { Thread.sleep(winHexKeyDelayMs) } catch (_: Exception) {}
            previousSc = sc
        }

        // === 3. 同时释放最后一个 hex 键和 Alt，提交字符 ===
        sendKeyboardReport(0, byteArrayOf())
        try { Thread.sleep(winHexCommitDelayMs) } catch (_: Exception) {}

        Log.d(TAG, "WIN-HEX DONE: U+${hex.uppercase()} hex=$hex total=$winUniTotalCount fail=$winUniFailCount")
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
            // Safety: always release all modifiers after Unicode string (prevents stuck Alt/Ctrl on Windows)
            sendKeyboardReport(0, byteArrayOf())
            try { Thread.sleep(10) } catch (e: Exception) {}
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
