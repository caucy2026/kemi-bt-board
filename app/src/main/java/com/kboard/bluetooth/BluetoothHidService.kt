package com.kboard.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kboard.R
import android.bluetooth.BluetoothDevice
import android.util.Log

class BluetoothHidService : Service() {

    private val binder = LocalBinder()
    var hidManager: BluetoothHidManager? = null
        private set

    companion object {
        private const val CHANNEL_ID = "kboard_bt_channel"
        private const val NOTIFICATION_ID = 2026
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHidService = this@BluetoothHidService
    }

    private val pairingReceiver = object : android.content.BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    Log.d("BluetoothHidService", "ACL Connected broadcast received for device ${device?.address}")
                    device?.let { hidManager?.onAclConnected(it) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    Log.d("BluetoothHidService", "ACL Disconnected broadcast received for device ${device?.address}")
                    device?.let { hidManager?.onAclDisconnected(it) }
                }
                "android.bluetooth.device.action.PAIRING_REQUEST" -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val pairingVariant = intent.getIntExtra("android.bluetooth.device.extra.PAIRING_VARIANT", -1)
                    val pairingKey = intent.getIntExtra("android.bluetooth.device.extra.PAIRING_KEY", -1)
                    Log.d("BluetoothHidService", "Auto-pairing receiver triggered for ${device?.address}, variant=$pairingVariant, key=$pairingKey")
                    
                    // Show a Toast to guide the user on the kboard device screen
                    try {
                        android.widget.Toast.makeText(
                            context,
                            "已自动确认配对请求！请直接在手机上点击【配对】或【确认】即可连接",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        // ignore
                    }

                    try {
                        when (pairingVariant) {
                            0 -> { // PAIRING_VARIANT_PIN
                                val pin = if (pairingKey != -1) {
                                    pairingKey.toString().toByteArray()
                                } else {
                                    "0000".toByteArray()
                                }
                                device?.setPin(pin)
                                Log.d("BluetoothHidService", "Auto-pairing: setPin called for PIN variant")
                            }
                            1 -> { // PAIRING_VARIANT_PASSKEY
                                if (pairingKey != -1) {
                                    val pinBytes = String.format("%06d", pairingKey).toByteArray()
                                    device?.setPin(pinBytes)
                                    Log.d("BluetoothHidService", "Auto-pairing: setPin with passkey $pairingKey")
                                } else {
                                    device?.setPin("000000".toByteArray())
                                }
                            }
                            2, 3 -> { // PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT
                                val setPairingConfirmationMethod = device?.javaClass?.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
                                setPairingConfirmationMethod?.invoke(device, true)
                                Log.d("BluetoothHidService", "Auto-pairing: confirmed pairing/consent")
                            }
                            4, 5 -> { // PAIRING_VARIANT_DISPLAY_PASSKEY, PAIRING_VARIANT_DISPLAY_PIN
                                val setPairingConfirmationMethod = device?.javaClass?.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
                                setPairingConfirmationMethod?.invoke(device, true)
                                Log.d("BluetoothHidService", "Auto-pairing: confirmed display consent")
                            }
                        }
                        
                        // Abort the system dialog broadcast so it does not show up
                        abortBroadcast()
                        Log.d("BluetoothHidService", "Auto-paired device successfully!")
                    } catch (e: Exception) {
                        Log.e("BluetoothHidService", "Auto-pairing failed", e)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        
        // Register pairing and ACL receiver with highest priority
        val filter = android.content.IntentFilter().apply {
            addAction("android.bluetooth.device.action.PAIRING_REQUEST")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            priority = 2147483647 // Max integer priority to intercept before system UI
        }
        registerReceiver(pairingReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun initializeHidManager(listener: BluetoothHidManager.HidStateListener) {
        if (hidManager == null) {
            hidManager = BluetoothHidManager(applicationContext, listener)
        } else {
            hidManager?.listener = listener
        }
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("kboard 蓝牙控制器")
            .setContentText("保持后台蓝牙 HID 连接与触控服务中...")
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "蓝牙 HID 服务"
            val descriptionText = "保持后台蓝牙连接的通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (e: Exception) {
            // ignore
        }
        hidManager?.unregister()
        hidManager = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("BluetoothHidService", "onTaskRemoved: actively disconnecting from host")
        // Restore A2DP profiles before disconnect
        hidManager?.restoreA2dpAndRestartBt()
        // Send HID disconnect so Mac knows keyboard is offline
        hidManager?.disconnectFromHost()
        // Stop self so notification goes away
        stopSelf()
    }
}
