package com.kboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kboard.asr.IflytekASRClient
import com.kboard.asr.VoiceInputController
import com.kboard.asr.XunfeiCredentialProvider
import com.kboard.bluetooth.BluetoothHidManager
import com.kboard.bluetooth.KeycodeTranslator
import com.kboard.ui.TouchpadView
import com.kboard.utils.DeviceMacReader
import java.util.concurrent.Executors
import android.os.IBinder
import com.kboard.bluetooth.BluetoothHidService

class MainActivity : AppCompatActivity(), BluetoothHidManager.HidStateListener, IflytekASRClient.ASRCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 2026
        private const val REQUEST_ENABLE_BT = 2027
    }

    private lateinit var statusText: TextView
    private lateinit var btNameText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var micButton: Button
    private lateinit var touchpadView: TouchpadView
    private lateinit var keyCtrl: Button
    private lateinit var keyEsc: Button
    private lateinit var keyToggleKeyboard: Button
    private lateinit var keyShift: Button
    private lateinit var keyAlt: Button
    private lateinit var keyWinCmd: Button
    private lateinit var keyC: Button
    private lateinit var keyV: Button
    private lateinit var keyBack: Button
    private lateinit var keyEnter: Button
    private lateinit var btnModePinyin: Button
    private lateinit var btnModeMac: Button
    private lateinit var btnModeWin: Button
    private lateinit var hintTextView: TextView
    private lateinit var macButtonsLayout: android.widget.LinearLayout
    private lateinit var btnBypassMac: Button
    private lateinit var btnHowToSetUnicode: Button
    private lateinit var qwertyKeyboardOverlay: android.widget.LinearLayout
    private var currentInputMode = BluetoothHidManager.MODE_MAC
    private var activeModifiers: Byte = 0
    private val pressedKeycodes = mutableSetOf<Byte>()
    private var latestSessionText = ""

    private var btService: BluetoothHidService? = null
    private var isBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as BluetoothHidService.LocalBinder
            btService = binder.getService()
            isBound = true
            
            btService?.initializeHidManager(this@MainActivity)
            btService?.hidManager?.let { hidManager ->
                touchpadView.setHidManager(hidManager)
                hidManager.currentInputMode = currentInputMode
                val device = hidManager.connectedDevice
                if (device != null) {
                    statusText.text = "状态: 已连接至主机 [${device.name ?: device.address}]"
                } else {
                    statusText.text = "状态: 等待主机连接"
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            btService = null
            isBound = false
        }
    }

    private var voiceController: VoiceInputController? = null
    private var asrClient: IflytekASRClient? = null
    private var credentialProvider = XunfeiCredentialProvider()
    
    // Cached credentials
    private var cachedCredentials: XunfeiCredentialProvider.Credentials? = null
    
    private var wifiMacAddress = "02:00:00:00:00:00"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        statusText = findViewById(R.id.statusText)
        btNameText = findViewById(R.id.btNameText)
        transcriptText = findViewById(R.id.transcriptText)
        micButton = findViewById(R.id.micButton)
        touchpadView = findViewById(R.id.touchpadView)

        keyCtrl = findViewById(R.id.keyCtrl)
        keyEsc = findViewById(R.id.keyEsc)
        keyToggleKeyboard = findViewById(R.id.keyToggleKeyboard)
        keyShift = findViewById(R.id.keyShift)
        qwertyKeyboardOverlay = findViewById(R.id.qwertyKeyboardOverlay)
        keyAlt = findViewById(R.id.keyAlt)
        keyWinCmd = findViewById(R.id.keyWinCmd)
        keyC = findViewById(R.id.keyC)
        keyV = findViewById(R.id.keyV)
        keyBack = findViewById(R.id.keyBack)
        keyEnter = findViewById(R.id.keyEnter)
        
        btnModePinyin = findViewById(R.id.btnModePinyin)
        btnModeMac = findViewById(R.id.btnModeMac)
        btnModeWin = findViewById(R.id.btnModeWin)
        hintTextView = findViewById(R.id.hintText)
        macButtonsLayout = findViewById(R.id.macButtonsLayout)
        btnBypassMac = findViewById(R.id.btnBypassMac)
        btnHowToSetUnicode = findViewById(R.id.btnHowToSetUnicode)

        val appTitle = findViewById<TextView>(R.id.appTitle)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            appTitle.text = "KEMI语音键盘 v${pInfo.versionName}"
        } catch (e: Exception) {
            appTitle.text = "KEMI语音键盘"
        }

        // Load input mode from SharedPreferences
        val prefs = getSharedPreferences("kboard_prefs", android.content.Context.MODE_PRIVATE)
        currentInputMode = prefs.getInt("input_mode", BluetoothHidManager.MODE_MAC)

        setupInputModeButtons()

        setupKeyButtons()

        voiceController = VoiceInputController(this)

        // Read WiFi MAC address (thanks to system signature, we get the real one)
        wifiMacAddress = DeviceMacReader.getMacAddress()
        val cleanMac = wifiMacAddress.replace(":", "").uppercase()
        val lastFour = if (cleanMac.length >= 4) cleanMac.substring(cleanMac.length - 4) else "0000"
        btNameText.text = "蓝牙设备名称是KEMI-KB-$lastFour"

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        // Bluetooth Connect and Advertise permissions are required on Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            initializeBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeBluetooth()
            } else {
                Toast.makeText(this, "需要必要权限以运行此应用", Toast.LENGTH_LONG).show()
                statusText.text = "状态: 权限未授予，无法运行"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                initializeBluetooth()
            } else {
                Toast.makeText(this, "蓝牙未开启，应用无法正常工作", Toast.LENGTH_LONG).show()
                statusText.text = "状态: 蓝牙未开启"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "当前设备不支持蓝牙", Toast.LENGTH_LONG).show()
            statusText.text = "状态: 不支持蓝牙"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            statusText.text = "状态: 蓝牙已关闭，请求开启"
            val enableBtIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        // Set Bluetooth Adapter Name
        val cleanMac = wifiMacAddress.replace(":", "").uppercase()
        val lastFour = if (cleanMac.length >= 4) cleanMac.substring(cleanMac.length - 4) else "0000"
        val targetBtName = "KEMI-KB-$lastFour"
        try {
            bluetoothAdapter.name = targetBtName
            Log.d(TAG, "Bluetooth device name set to: $targetBtName")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to set bluetooth name", e)
        }

        // Set Bluetooth Discoverable (SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23)
        try {
            val setScanModeMethod = bluetoothAdapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            setScanModeMethod.invoke(bluetoothAdapter, 23)
            Log.d(TAG, "Bluetooth scan mode set to CONNECTABLE_DISCOVERABLE (23) successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set scan mode via reflection", e)
        }

        applyJustWorksPairing()

        statusText.text = "状态: 正在初始化蓝牙 HID 服务..."
        if (!isBound) {
            val intent = android.content.Intent(this, BluetoothHidService::class.java)
            startService(intent)
            bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
        } else {
            btService?.initializeHidManager(this)
            btService?.hidManager?.let {
                touchpadView.setHidManager(it)
                statusText.text = "状态: 蓝牙就绪，等待配对连接"
            }
        }
        setupMicButton()
    }

    private fun applyJustWorksPairing() {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return

        // Set Class of Device (CoD) to Mouse (0x000580)
        try {
            val bluetoothClassClass = Class.forName("android.bluetooth.BluetoothClass")
            val constructor = bluetoothClassClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            val keyboardMouseCoD = constructor.newInstance(0x000580)
            
            val setBluetoothClassMethod = bluetoothAdapter.javaClass.getMethod("setBluetoothClass", bluetoothClassClass)
            val success = setBluetoothClassMethod.invoke(bluetoothAdapter, keyboardMouseCoD) as Boolean
            Log.d(TAG, "Bluetooth class of device set to Mouse (0x000580) successfully: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bluetooth class via reflection", e)
        }

        // Set IO Capability to NoInputNoOutput (3) to bypass pairing confirmation dialog (enforces Just Works pairing)
        try {
            val setIoCapabilityMethod = bluetoothAdapter.javaClass.getMethod("setIoCapability", Int::class.javaPrimitiveType)
            val success = setIoCapabilityMethod.invoke(bluetoothAdapter, 3) as Boolean
            Log.d(TAG, "Bluetooth IO Capability set to NoInputNoOutput (3) successfully: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set IO capability via reflection", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMicButton() {
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startASRProcess()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopASRProcess()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupInputModeButtons() {
        val clickListener = android.view.View.OnClickListener { view ->
            currentInputMode = when (view.id) {
                R.id.btnModePinyin -> BluetoothHidManager.MODE_PINYIN
                R.id.btnModeMac -> BluetoothHidManager.MODE_MAC
                R.id.btnModeWin -> BluetoothHidManager.MODE_WIN
                else -> BluetoothHidManager.MODE_PINYIN
            }
            btService?.hidManager?.currentInputMode = currentInputMode
            updateInputModeUI(currentInputMode)

            // Save input mode to SharedPreferences
            val prefs = getSharedPreferences("kboard_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("input_mode", currentInputMode).apply()
        }
        btnModePinyin.setOnClickListener(clickListener)
        btnModeMac.setOnClickListener(clickListener)
        btnModeWin.setOnClickListener(clickListener)

        btnBypassMac.setOnClickListener {
            Log.d(TAG, "Bypassing Mac keyboard setup assistant...")
            btService?.hidManager?.sendBypassMacKeyboardSetup()
        }

        btnHowToSetUnicode.setOnClickListener {
            showMacUnicodeGuideDialog()
        }

        // Initialize UI with saved mode
        updateInputModeUI(currentInputMode)
    }

    private fun updateInputModeUI(mode: Int) {
        val activeColor = android.graphics.Color.parseColor("#0284C7")
        val inactiveColor = android.graphics.Color.parseColor("#1E293B")

        btnModePinyin.setBackgroundColor(if (mode == BluetoothHidManager.MODE_PINYIN) activeColor else inactiveColor)
        btnModeMac.setBackgroundColor(if (mode == BluetoothHidManager.MODE_MAC) activeColor else inactiveColor)
        btnModeWin.setBackgroundColor(if (mode == BluetoothHidManager.MODE_WIN) activeColor else inactiveColor)

        // Set visibility of bypass Mac buttons layout
        macButtonsLayout.visibility = if (mode == BluetoothHidManager.MODE_MAC) android.view.View.VISIBLE else android.view.View.GONE

        when (mode) {
            BluetoothHidManager.MODE_PINYIN -> {
                hintTextView.text = "提示: 拼音模式已开启。请确保接收端已切换为中文拼音输入法。"
            }
            BluetoothHidManager.MODE_MAC -> {
                val builder = android.text.SpannableStringBuilder("提示: Mac直投模式已开启。请确保Mac已切换至【Unicode十六进制输入】输入法  ")
                val iconDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_mac_unicode_icon)
                iconDrawable?.let {
                    val size = (14 * resources.displayMetrics.density).toInt()
                    it.setBounds(0, 0, size, size)
                    val imageSpan = android.text.style.ImageSpan(it, android.text.style.ImageSpan.ALIGN_BOTTOM)
                    builder.setSpan(imageSpan, builder.length - 1, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                hintTextView.text = builder
            }
            BluetoothHidManager.MODE_WIN -> {
                hintTextView.text = "提示: Win直投模式已开启。请确保Windows中已添加注册表【EnableHexNumpad=1】并重启。\n(配置方法: HKEY_CURRENT_USER\\Control Panel\\Input Method 下新建字符串值 EnableHexNumpad = 1)"
            }
        }
    }

    private fun startASRProcess() {
        latestSessionText = ""
        transcriptText.text = "正在连接语音转写..."
        micButton.text = "正在聆听..."
        
        networkExecutor.execute {
            try {
                // Step 1: Get/apply credentials (cached if already fetched)
                val creds = cachedCredentials ?: credentialProvider.applyCredentials(wifiMacAddress)
                if (creds == null) {
                    runOnUiThread {
                        transcriptText.text = "错误: 无法获取讯飞 ASR 凭证"
                        micButton.text = "按住说话"
                    }
                    return@execute
                }
                cachedCredentials = creds

                // Step 2: Complete session authentication
                val authSuccess = credentialProvider.authenticate(wifiMacAddress, creds.token)
                if (!authSuccess) {
                    runOnUiThread {
                        transcriptText.text = "错误: 讯飞 ASR 鉴权失败"
                        micButton.text = "按住说话"
                    }
                    return@execute
                }

                // Step 3: Establish streaming WebSocket client
                asrClient = IflytekASRClient(creds, wifiMacAddress, this@MainActivity)
                asrClient?.start()

            } catch (e: Exception) {
                Log.e(TAG, "Error in ASR setup thread", e)
                runOnUiThread {
                    transcriptText.text = "发生异常: ${e.message}"
                    micButton.text = "按住说话"
                }
            }
        }
    }

    private fun sendKeyboardState() {
        val keycodesArray = pressedKeycodes.toByteArray()
        btService?.hidManager?.sendKeyboardReport(activeModifiers, keycodesArray)
        Log.d(TAG, "Sent keyboard report: modifiers=$activeModifiers, keys=${keycodesArray.contentToString()}")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupKeyButtons() {
        // CTRL (0x01)
        keyCtrl.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyCtrl.isPressed = true
                    activeModifiers = (activeModifiers.toInt() or 0x01).toByte()
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyCtrl.isPressed = false
                    activeModifiers = (activeModifiers.toInt() and 0x01.inv()).toByte()
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // ALT (0x04)
        keyAlt.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyAlt.isPressed = true
                    activeModifiers = (activeModifiers.toInt() or 0x04).toByte()
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyAlt.isPressed = false
                    activeModifiers = (activeModifiers.toInt() and 0x04.inv()).toByte()
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // WIN/CMD (0x08)
        keyWinCmd.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyWinCmd.isPressed = true
                    activeModifiers = (activeModifiers.toInt() or 0x08).toByte()
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyWinCmd.isPressed = false
                    activeModifiers = (activeModifiers.toInt() and 0x08.inv()).toByte()
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // ESC (0x29)
        keyEsc.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyEsc.isPressed = true
                    pressedKeycodes.add(0x29.toByte())
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyEsc.isPressed = false
                    pressedKeycodes.remove(0x29.toByte())
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // Toggle Keyboard Layout (...)
        keyToggleKeyboard.setOnClickListener {
            if (qwertyKeyboardOverlay.visibility == android.view.View.VISIBLE) {
                qwertyKeyboardOverlay.visibility = android.view.View.GONE
                touchpadView.visibility = android.view.View.VISIBLE
                keyToggleKeyboard.isSelected = false
            } else {
                qwertyKeyboardOverlay.visibility = android.view.View.VISIBLE
                touchpadView.visibility = android.view.View.GONE
                keyToggleKeyboard.isSelected = true
            }
        }

        // Bind all QWERTY keyboard overlay keys dynamically
        val qwertyMap = mapOf(
            R.id.btnTab to 0x2B.toByte(), // Tab key inside virtual keyboard
            R.id.btnDel to 0x4C.toByte(), // Delete key inside virtual keyboard
            
            R.id.btnQ to 0x14.toByte(),
            R.id.btnW to 0x1A.toByte(),
            R.id.btnE to 0x08.toByte(),
            R.id.btnR to 0x15.toByte(),
            R.id.btnT to 0x17.toByte(),
            R.id.btnY to 0x1C.toByte(),
            R.id.btnU to 0x18.toByte(),
            R.id.btnI to 0x0C.toByte(),
            R.id.btnO to 0x12.toByte(),
            R.id.btnP to 0x13.toByte(),
            
            R.id.btnA to 0x04.toByte(),
            R.id.btnS to 0x16.toByte(),
            R.id.btnD to 0x07.toByte(),
            R.id.btnF to 0x09.toByte(),
            R.id.btnG to 0x0A.toByte(),
            R.id.btnH to 0x0B.toByte(),
            R.id.btnJ to 0x0D.toByte(),
            R.id.btnK to 0x0E.toByte(),
            R.id.btnL to 0x0F.toByte(),
            
            R.id.btnZ to 0x1D.toByte(),
            R.id.btnX to 0x1B.toByte(),
            R.id.btnC to 0x06.toByte(),
            R.id.btnV to 0x19.toByte(),
            R.id.btnB to 0x05.toByte(),
            R.id.btnN to 0x11.toByte(),
            R.id.btnM to 0x10.toByte(),
            
            R.id.btnSpace to 0x2C.toByte()
        )

        qwertyMap.forEach { (viewId, scanCode) ->
            findViewById<Button>(viewId)?.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        view.isPressed = true
                        pressedKeycodes.add(scanCode)
                        sendKeyboardState()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        pressedKeycodes.remove(scanCode)
                        sendKeyboardState()
                        true
                    }
                    else -> false
                }
            }
        }

        // SHIFT (0x02)
        keyShift.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyShift.isPressed = true
                    activeModifiers = (activeModifiers.toInt() or 0x02).toByte()
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyShift.isPressed = false
                    activeModifiers = (activeModifiers.toInt() and 0x02.inv()).toByte()
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // btnAlt on virtual keyboard (0x04 modifier)
        findViewById<Button>(R.id.btnAlt)?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    view.isPressed = true
                    activeModifiers = (activeModifiers.toInt() or 0x04).toByte()
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    activeModifiers = (activeModifiers.toInt() and 0x04.inv()).toByte()
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // C (0x06)
        keyC.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyC.isPressed = true
                    pressedKeycodes.add(0x06.toByte())
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyC.isPressed = false
                    pressedKeycodes.remove(0x06.toByte())
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // V (0x19)
        keyV.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyV.isPressed = true
                    pressedKeycodes.add(0x19.toByte())
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyV.isPressed = false
                    pressedKeycodes.remove(0x19.toByte())
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // BACK (0x2A)
        keyBack.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyBack.isPressed = true
                    pressedKeycodes.add(0x2A.toByte())
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyBack.isPressed = false
                    pressedKeycodes.remove(0x2A.toByte())
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }

        // ENTER (0x28)
        keyEnter.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    keyEnter.isPressed = true
                    pressedKeycodes.add(0x28.toByte())
                    sendKeyboardState()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    keyEnter.isPressed = false
                    pressedKeycodes.remove(0x28.toByte())
                    sendKeyboardState()
                    true
                }
                else -> false
            }
        }
    }

    private fun stopASRProcess() {
        micButton.text = "按住说话"
        // Stop recording and send '--end--' token to ASR
        voiceController?.stopRecording(asrClient)
    }

    // --- Bluetooth HID state listener callbacks ---

    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        runOnUiThread {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    statusText.text = "状态: 已连接至主机 [${device?.name ?: device?.address}]"
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    statusText.text = "状态: 正在连接..."
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    statusText.text = "状态: 等待主机连接"
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    statusText.text = "状态: 正在断开..."
                }
            }
        }
    }

    override fun onAppRegistered(registered: Boolean) {
        runOnUiThread {
            if (registered) {
                val connectedDev = btService?.hidManager?.connectedDevice
                if (connectedDev != null) {
                    statusText.text = "状态: 已连接至主机 [${connectedDev.name ?: connectedDev.address}]"
                } else {
                    statusText.text = "状态: 等待主机连接"
                }
                // Re-apply Just Works configuration after registration completes (with a short delay to override system overrides)
                mainHandler.postDelayed({
                    applyJustWorksPairing()
                }, 500)
            } else {
                statusText.text = "状态: 注册蓝牙 HID 失败"
            }
        }
    }

    override fun onLog(message: String) {
        Log.d(TAG, "HID Log: $message")
    }

    // --- Xunfei ASR Client callbacks ---

    override fun onStarted() {
        runOnUiThread {
            transcriptText.text = "麦克风已开启，开始说话..."
            // ASR socket is opened, start recording now!
            asrClient?.let {
                voiceController?.startRecording(it)
            }
        }
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            transcriptText.text = text
            if (text.isNotEmpty()) {
                latestSessionText = text
            }
        }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            transcriptText.text = text
            if (text.isNotEmpty()) {
                latestSessionText = text
            }
        }
    }

    override fun onError(code: Int, message: String) {
        runOnUiThread {
            transcriptText.text = "语音识别错误 (代码 $code): $message"
        }
    }

    override fun onClosed() {
        runOnUiThread {
            asrClient = null
            val textToSend = latestSessionText
            if (textToSend.isNotEmpty()) {
                if (currentInputMode == BluetoothHidManager.MODE_PINYIN) {
                    val translated = KeycodeTranslator.translateText(textToSend)
                    Log.d(TAG, "Sending final ASR text (Pinyin): '$textToSend', Translated: '$translated'")
                    btService?.hidManager?.sendAsciiString(translated)
                } else {
                    Log.d(TAG, "Sending final ASR text (Unicode mode=$currentInputMode): '$textToSend'")
                    btService?.hidManager?.sendUnicodeString(textToSend, currentInputMode)
                }
                // Send Shift + Enter (Shift=0x02, Enter=0x28) sequentially to insert a newline (换行) without triggering message send
                btService?.hidManager?.sendKeystroke(0x02.toByte(), 0x28.toByte())
            }
            latestSessionText = ""
        }
    }

    private fun showMacUnicodeGuideDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_mac_guide)
        
        // Set translucent/transparent background
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val closeBtn = dialog.findViewById<Button>(R.id.btnGuideClose)
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.window?.setLayout(
            (320 * resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Actively disconnect from host so Mac/PC knows the keyboard has gone offline
        btService?.hidManager?.disconnectFromHost()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        // Stop the foreground service so it doesn't linger; auto-reconnect will fire on next launch
        stopService(android.content.Intent(this, BluetoothHidService::class.java))
        voiceController?.release()
        asrClient?.release()
        networkExecutor.shutdown()
    }
}
