package com.kboard.utils

import java.net.NetworkInterface
import java.util.*

object DeviceMacReader {

    // Returns the MAC address of wlan0 (in uppercase with colons, e.g. "1C:79:2D:02:F6:2D")
    // Requires sharedUserId="android.uid.system" on Android 12 to read actual hardware address.
    fun getMacAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (nif.name.equals("wlan0", ignoreCase = true)) {
                    val macBytes = nif.hardwareAddress ?: return "02:00:00:00:00:00"
                    val res = StringBuilder()
                    for (b in macBytes) {
                        res.append(String.format("%02X:", b))
                    }
                    if (res.isNotEmpty()) {
                        res.deleteCharAt(res.length - 1)
                    }
                    return res.toString()
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }
}
