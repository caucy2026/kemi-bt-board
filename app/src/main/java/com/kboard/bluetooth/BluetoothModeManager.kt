package com.kboard.bluetooth

import android.util.Log
import java.io.File

/**
 * Dynamically switches the Aicsemi Bluetooth chip between Keyboard mode and Audio mode
 * by modifying /vendor/etc/bluetooth/aicbt.conf via the overlay filesystem.
 * 
 * The Aicsemi HAL reads this config at startup. After writing the config,
 * we restart the BT stack to apply changes immediately without rebooting.
 * 
 * Requires system UID (sharedUserId="android.uid.system") to write to overlay upperdir.
 */
object BluetoothModeManager {
    private const val TAG = "BtModeManager"
    
    // Config paths
    private const val CONFIG_PATH = "/vendor/etc/bluetooth/aicbt.conf"
    private val OVERLAY_UPPER = "/mnt/scratch/overlay/vendor/upper/etc/bluetooth"
    private val BACKUP_PATH = "/data/misc/bluedroid/aicbt.conf.backup"    
    // App cache dir for temp files (set during init)
    private var appCacheDir: File = File("/data/data/com.kboard/cache")
    
    /**
     * Must be called before using the mode manager.
     * Provides a writable directory for temp config files.
     */
    fun init(cacheDir: File) {
        appCacheDir = cacheDir
        appCacheDir.mkdirs()
        Log.d(TAG, "Init with cache: ${appCacheDir.absolutePath}")
    }    
    // CoD values for each mode
    object KeyboardMode {
        const val SERVICE_CLASS = "0x00"   // No audio/telephony/OBEX bits
        const val MAJOR_CLASS   = "0x05"   // Peripheral
        const val MINOR_CLASS   = "0x40"   // Keyboard (6-bit field: 0x40>>2=0x10=keyboard)
    }
    
    object AudioMode {
        // Original factory defaults (from current aicbt.conf)
        const val SERVICE_CLASS = "0x1A"
        const val MAJOR_CLASS   = "0x01"   // Computer
        const val MINOR_CLASS   = "0x1C"
    }
    
    enum class Mode { KEYBOARD, AUDIO }
    
    @Volatile
    var currentMode: Mode = Mode.KEYBOARD
        private set
    
    // Has the original config been backed up?
    private var backupDone = false
    
    /**
     * Call once at app startup. Backs up original config and applies keyboard mode.
     */
    fun initializeForKeyboard() {
        ensureBackup()
        switchTo(Mode.KEYBOARD)
    }
    
    /**
     * Restore audio mode and original config. Call when app exits.
     */
    fun restoreForExit() {
        switchTo(Mode.AUDIO)
    }
    
    /**
     * Switches between KEYBOARD and AUDIO mode.
     * Modifies aicbt.conf and restarts the BT stack to apply immediately.
     */
    @Synchronized
    fun switchTo(mode: Mode): Boolean {
        // Always check the ACTUAL config state, not our cached mode
        val currentConfigMode = readCurrentModeFromConfig()
        
        if (currentConfigMode == mode) {
            Log.d(TAG, "Config already in $mode mode (CoD verified), skipping write")
            currentMode = mode
            return true
        }
        
        Log.d(TAG, "Switching BT mode: current=$currentConfigMode -> target=$mode")
        
        val configLines = when (mode) {
            Mode.KEYBOARD -> buildKeyboardConfig()
            Mode.AUDIO -> buildAudioConfig()
        }
        
        if (!writeConfig(configLines)) {
            Log.e(TAG, "Failed to write config for $mode mode")
            return false
        }
        
        // Verify write was successful
        val verifyMode = readCurrentModeFromConfig()
        if (verifyMode != mode) {
            Log.e(TAG, "Config write verification failed! Still showing $verifyMode")
            return false
        }
        
        // Restart BT stack so HAL re-reads the config
        restartBluetoothStack()
        
        currentMode = mode
        Log.d(TAG, "Successfully switched to $mode mode")
        return true
    }
    
    /**
     * Reads the current CoD from the actual config file to determine current mode.
     */
    private fun readCurrentModeFromConfig(): Mode? {
        try {
            val content = File(CONFIG_PATH).readText()
            val hasKeyboardService = content.contains("DevClassServiceClass=0x00")
            val hasKeyboardMajor = content.contains("DevClassMajorClass=0x05")
            if (hasKeyboardService && hasKeyboardMajor) return Mode.KEYBOARD
            
            val hasAudioService = content.contains("DevClassServiceClass=0x1A")
            if (hasAudioService) return Mode.AUDIO
            
            return null  // Unknown state
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Backs up the original aicbt.conf if not already done.
     * The backup is used to restore audio mode.
     */
    private fun ensureBackup() {
        if (backupDone) return
        
        try {
            val backupFile = File(BACKUP_PATH)
            if (!backupFile.exists()) {
                val originalConfig = File(CONFIG_PATH)
                if (originalConfig.exists()) {
                    backupFile.parentFile?.mkdirs()
                    originalConfig.copyTo(backupFile, overwrite = true)
                    Log.d(TAG, "Original aicbt.conf backed up to $BACKUP_PATH")
                }
            } else {
                Log.d(TAG, "Backup already exists at $BACKUP_PATH")
            }
            backupDone = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup original config: ${e.message}")
            // Not fatal - we have hardcoded AudioMode defaults
        }
    }
    
    /**
     * Reads the backup (or uses defaults) and generates audio mode config lines.
     */
    private fun buildAudioConfig(): String {
        try {
            val backupFile = File(BACKUP_PATH)
            if (backupFile.exists()) {
                return backupFile.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read backup, using hardcoded audio defaults: ${e.message}")
        }
        
        // Fallback: use current config with audio values
        return buildConfigContent(
            AudioMode.SERVICE_CLASS,
            AudioMode.MAJOR_CLASS,
            AudioMode.MINOR_CLASS
        )
    }
    
    private fun buildKeyboardConfig(): String {
        return buildConfigContent(
            KeyboardMode.SERVICE_CLASS,
            KeyboardMode.MAJOR_CLASS,
            KeyboardMode.MINOR_CLASS
        )
    }
    
    /**
     * Reads the current config file and replaces only the DevClass lines.
     * This preserves all other settings (firmware paths, log levels, etc.)
     */
    private fun buildConfigContent(serviceClass: String, majorClass: String, minorClass: String): String {
        val currentConfig = try {
            File(CONFIG_PATH).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read current config: ${e.message}")
            // Return minimal config
            return """
DevClassServiceClass=$serviceClass
DevClassMajorClass=$majorClass
DevClassMinorClass=$minorClass
""".trimIndent()
        }
        
        return currentConfig
            .replace(Regex("DevClassServiceClass=.*"), "DevClassServiceClass=$serviceClass")
            .replace(Regex("DevClassMajorClass=.*"), "DevClassMajorClass=$majorClass")
            .replace(Regex("DevClassMinorClass=.*"), "DevClassMinorClass=$minorClass")
    }
    
    /**
     * Writes config to the overlay upperdir so it takes effect.
     * Tries multiple strategies because /vendor is overlayfs (read-only).
     * Strategy 1: Write to overlay upperdir (for overlayfs systems)
     * Strategy 2: Direct write after remount rw
     * Strategy 3: Write accessible path and symlink
     */
    private fun writeConfig(configContent: String): Boolean {
        // Strategy 1: Overlay upperdir
        try {
            val upperDir = File(OVERLAY_UPPER)
            if (upperDir.exists() || upperDir.mkdirs()) {
                val targetFile = File(upperDir, "aicbt.conf")
                targetFile.writeText(configContent)
                Log.d(TAG, "Config written to overlay upper: ${targetFile.absolutePath}")
                
                // Verify via merged view
                try {
                    Thread.sleep(500) // Allow overlay to merge
                    val merged = File(CONFIG_PATH).readText()
                    if (merged.contains("DevClassServiceClass=0x00")) {
                        Log.d(TAG, "Overlay merge verified: keyboard config visible via /vendor")
                        return true
                    }
                    Log.w(TAG, "Overlay merge not visible yet, trying next strategy...")
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay verify failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "Cannot create overlay dir: $OVERLAY_UPPER")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay write failed: ${e.message}")
        }
        
        // Strategy 2: Direct write with remount
        try {
            val remount = Runtime.getRuntime().exec(arrayOf("mount", "-o", "remount,rw", "/vendor"))
            remount.waitFor()
            if (remount.exitValue() == 0) {
                File(CONFIG_PATH).writeText(configContent)
                Log.d(TAG, "Config written via remount rw")
                Runtime.getRuntime().exec(arrayOf("mount", "-o", "remount,ro", "/vendor")).waitFor()
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remount write failed: ${e.message}")
        }
        
        // Strategy 3: Try to write via our system UID directly
        // (Some devices allow system UID to write to vendor even if ro)
        try {
            val target = File(CONFIG_PATH)
            if (target.canWrite()) {
                target.writeText(configContent)
                Log.d(TAG, "Config written directly (system UID)")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct write failed: ${e.message}")
        }
        
        // Strategy 4: Write to app cache, then pipe to vendor via shell
        try {
            val tmpFile = File(appCacheDir, "aicbt_modified.conf")
            tmpFile.writeText(configContent)
            Log.d(TAG, "Temp config at: ${tmpFile.absolutePath}")
            
            val catProc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "cat ${tmpFile.absolutePath} > $CONFIG_PATH"))
            catProc.waitFor()
            if (catProc.exitValue() == 0) {
                Log.d(TAG, "Config written via cat redirect!")
                tmpFile.delete()
                return true
            }
            tmpFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Shell write failed: ${e.message}")
        }
        
        // Strategy 5: ROOT - use su to write (for rooted devices)
        try {
            val tmpFile = File(appCacheDir, "aicbt_modified.conf")
            tmpFile.writeText(configContent)
            
            val suProc = Runtime.getRuntime().exec(arrayOf("su", "-c", 
                "cat ${tmpFile.absolutePath} > $CONFIG_PATH"))
            suProc.waitFor()
            if (suProc.exitValue() == 0) {
                Log.d(TAG, "Config written via su (root)!")
                tmpFile.delete()
                return true
            }
            tmpFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "su write failed (device not rooted): ${e.message}")
        }
        
        Log.e(TAG, "ALL write strategies failed! /vendor is read-only overlayfs.")
        Log.e(TAG, "To fix: modify $CONFIG_PATH in the firmware image,")
        Log.e(TAG, "  or root the device and grant su access.")
        return false
    }
    
    /**
     * Restarts the Bluetooth stack to apply config changes without rebooting.
     * Steps: stop HAL → kill BT app → start HAL (BT app auto-restarts)
     */
    private fun restartBluetoothStack() {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val setProp = sp.getMethod("set", String::class.java, String::class.java)
            
            // Step 1: Stop HAL
            Log.d(TAG, "Stopping vendor.bluetooth-1-0...")
            setProp.invoke(null, "ctl.stop", "vendor.bluetooth-1-0")
            Thread.sleep(2500)
            
            // Step 2: Kill BT app so it re-reads profile config on restart
            try {
                val killProc = Runtime.getRuntime().exec(arrayOf("am", "force-stop", "com.android.bluetooth"))
                killProc.waitFor()
                Log.d(TAG, "Force-stopped com.android.bluetooth (exit=${killProc.exitValue()})")
            } catch (e: Exception) {
                Log.e(TAG, "Cannot kill BT app: ${e.message}")
            }
            Thread.sleep(3000)
            
            // Step 3: Start HAL (BT app will auto-restart)
            Log.d(TAG, "Starting vendor.bluetooth-1-0...")
            setProp.invoke(null, "ctl.start", "vendor.bluetooth-1-0")
            Thread.sleep(3000)
            
            Log.d(TAG, "BT stack restarted with new config")
        } catch (e: Exception) {
            Log.e(TAG, "BT stack restart failed: ${e.message}")
            
            // Shell fallback
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "ctl.stop", "vendor.bluetooth-1-0"))
                Thread.sleep(2500)
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", "com.android.bluetooth"))
                Thread.sleep(3000)
                Runtime.getRuntime().exec(arrayOf("setprop", "ctl.start", "vendor.bluetooth-1-0"))
                Thread.sleep(3000)
            } catch (e2: Exception) {
                Log.e(TAG, "Shell fallback also failed: ${e2.message}")
            }
        }
    }
}
