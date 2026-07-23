# KEMI 语音键盘 — 蓝牙控制流程完整文档

> 版本: v1.1.0 | 包名: `com.kboard` | 系统签名: `android.uid.system`
> 最后更新: 2026-07-23

---

## 目录

1. [架构概览](#1-架构概览)
2. [启动流程](#2-启动流程)
3. [A2DP 禁用/恢复流程](#3-a2dp-禁用恢复流程)
4. [蓝牙重启流程](#4-蓝牙重启流程)
5. [HID Profile 注册流程](#5-hid-profile-注册流程)
6. [HID 数据发送流程](#6-hid-数据发送流程)
7. [自动配对流程](#7-自动配对流程)
8. [重连与保活流程](#8-重连与保活流程)
9. [清除连接流程](#9-清除连接流程)
10. [退出与恢复流程](#10-退出与恢复流程)
11. [Settings / SharedPreferences 写入清单](#11-settings--sharedpreferences-写入清单)
12. [当前已知问题](#12-当前已知问题)

---

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      MainActivity.kt                        │
│  UI + ASR + 按键绑定 + 生命周期                              │
│  implements HidStateListener, ASRCallback                   │
└──────────────┬──────────────────────────────────────────────┘
               │ bindService / serviceConnection
┌──────────────▼──────────────────────────────────────────────┐
│                  BluetoothHidService.kt                     │
│  Foreground Service (STICKY)                                │
│  - 前台通知 "保持后台蓝牙 HID 连接与触控服务中..."             │
│  - PAIRING_REQUEST / ACL 广播接收器                           │
│  - initializeHidManager() → 创建 BluetoothHidManager         │
└──────────────┬──────────────────────────────────────────────┘
               │ holds reference
┌──────────────▼──────────────────────────────────────────────┐
│                  BluetoothHidManager.kt                     │
│  核心: HID 注册、A2DP 管理、键盘鼠标报告发送、重连             │
│  init{} 触发: setprop + disableA2dpAndRestartBt()           │
└─────────────────────────────────────────────────────────────┘
```

**相关辅助类:**

| 类 | 职责 |
|----|------|
| `KeycodeTranslator` | ASCII↔HID ScanCode, 中文→拼音转写 |
| `VoiceInputController` | AudioRecord 16kHz PCM 采集 |
| `IflytekASRClient` | 讯飞 WebSocket 流式语音识别 |
| `TouchpadView` | 触摸板手势 → 鼠标报告 |
| `DeviceMacReader` | 读取 wlan0 MAC 地址 |
| `XunfeiCredentialProvider` | 讯飞凭证申请/鉴权 HTTP 客户端 |

---

## 2. 启动流程

### 2.1 时序图

```
用户点击图标 / adb am start
  │
  ▼
MainActivity.onCreate()
  │
  ├─ setContentView(activity_main.xml)
  ├─ 读取 SharedPreferences: input_mode
  ├─ VoiceInputController(this) 初始化
  ├─ DeviceMacReader.getMacAddress() → wifiMacAddress
  ├─ 设置蓝牙名称标签: "KEMI-KB-XXXX"
  │
  ▼
checkAndRequestPermissions()
  ├─ RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE
  ├─ [API 31+] BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
  ├─ 缺失权限 → requestPermissions()
  └─ 全部已授权 → initializeBluetooth()
```

### 2.2 initializeBluetooth() 详细步骤

```
initializeBluetooth()
│
├─ 第1步: 获取 BluetoothAdapter
│   └─ 不存在 → Toast "当前设备不支持蓝牙" → return
│
├─ 第2步: 确保蓝牙已开启
│   └─ 未开启 → startActivityForResult(ACTION_REQUEST_ENABLE) → return
│               (onActivityResult 中重试 initializeBluetooth)
│
├─ 第3步: 设置蓝牙适配器名称
│   └─ bluetoothAdapter.name = "KEMI-KB-{wifiMac后4位大写}"
│
├─ 第4步: 设置扫描模式为 CONNECTABLE_DISCOVERABLE
│   └─ 反射: bluetoothAdapter.setScanMode(23)
│
├─ 第5步: applyJustWorksPairing()
│   ├─ 反射: bluetoothAdapter.setBluetoothClass(0x000540) → Keyboard Peripheral CoD
│   └─ 反射: bluetoothAdapter.setIoCapability(3) → NoInputNoOutput (免确认配对)
│
├─ 第6步: 启动并绑定 BluetoothHidService
│   ├─ startService(intent) → 创建前台通知
│   ├─ bindService(intent, serviceConnection, BIND_AUTO_CREATE)
│   └─ onServiceConnected → initializeHidManager(this)
│       └─ 创建 BluetoothHidManager(applicationContext, listener)
│           └─ init{} 执行 (见 §3 A2DP 流程)
│
├─ 第7步: setupMicButton() / setupInputModeButtons() / setupGlobalKeyboard()
│
└─ 第8步: updateInputModeUI(savedMode)
```

### 2.3 BluetoothHidService 启动步骤

```
BluetoothHidService.onCreate()
│
├─ createNotificationChannel()
│   └─ CHANNEL_ID = "kboard_bt_channel", IMPORTANCE_LOW
│
├─ startForegroundService()
│   └─ Notification: "kboard 蓝牙控制器" / "保持后台蓝牙 HID 连接与触控服务中..."
│   └─ [Q+] FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
│
└─ registerReceiver(pairingReceiver)
    ├─ action: PAIRING_REQUEST (priority=MAX)
    ├─ action: ACL_CONNECTED
    └─ action: ACL_DISCONNECTED
```

### 2.4 MainActivity.onResume()

```
onResume()
├─ isResumedState = true
├─ isManualDisconnect = false
└─ startReconnectLoop() → 当前直接 return (已禁用自动轮询)
```

---

## 3. A2DP 禁用/恢复流程

### 3.1 背景

根据 `a2dp.md`，定制 `Bluetooth.apk` 通过 `bluetooth_disabled_profiles` bitmask 控制 Profile 开关:

| Profile | Bitmask | 说明 |
|---------|:-------:|------|
| A2DP Sink | `4` | 蓝牙音频接收 (手机作为音箱) |
| A2DP Source | `2048` | 蓝牙音频发送 (手机作为音源) |
| **Both disabled** | **`2052`** | `4 + 2048` ← 目标值 |
| All enabled | `0` | 恢复值 |

前置条件:
1. `setprop persist.sys_im.blutooth.is_a2dp_dynamic true` (动态切换开关)
2. 定制 `Bluetooth.apk` 已部署到 `/system/app/Bluetooth/`

### 3.2 进入 (disableA2dpAndRestartBt)

```
BluetoothHidManager.init{}
│
├─ Step 0: Runtime.exec("setprop persist.sys_im.blutooth.is_a2dp_dynamic true")
│
├─ Step 1: 检查 dirty shutdown 标记
│   └─ wasDirty == true → putString("bluetooth_disabled_profiles", "0") 恢复
│
└─ Step 2: disableA2dpAndRestartBt()
    │
    ├─ 2a: 读取当前 bluetooth_disabled_profiles (getString)
    │   └─ 如果是 "2052" → 已禁用, skip, return
    │
    ├─ 2b: 检查 a2dpDisabled 标记 → 正在操作中, skip, return
    │
    ├─ 2c: 保存原始值 originalProfilesValue (null → "0")
    │
    ├─ 2d: putString("bluetooth_disabled_profiles", "2052")
    │
    ├─ 2e: 回读验证 getString → 预期 "2052"
    │   └─ 不匹配 → 记录错误, return (蓝牙不会重启!)
    │
    ├─ 2f: 标记 a2dpDisabled = true
    │   ├─ SharedPreferences: a2dp_original = originalProfilesValue
    │   └─ SharedPreferences: a2dp_dirty_shutdown = true
    │
    └─ 2g: performBtRestart() (见 §4)
```

### 3.3 退出 (restoreA2dpAndRestartBt)

```
MainActivity.onBackPressed() / onDestroy()
│
└─ restoreA2dpAndRestartBt()
    │
    ├─ 1: 获取 restoreVal = originalProfilesValue ?: "0"
    │
    ├─ 2: putString("bluetooth_disabled_profiles", restoreVal)
    │
    ├─ 3: 回读验证
    │
    ├─ 4: 标记 a2dpDisabled = false, isRestoring = true
    │   └─ SharedPreferences: a2dp_dirty_shutdown = false
    │
    └─ 5: performBtRestart() → 完成后 stopService + finishAffinity()
```

### 3.4 Dirty Shutdown 保护

```
场景: App 崩溃 / 被系统杀死
  → a2dp_dirty_shutdown = true 残留在 SharedPreferences

下次启动 init{}:
  wasDirty == true
  → putString("bluetooth_disabled_profiles", "0") 恢复所有 Profile
  → 清除 dirty 标记
  → 再走正常 disableA2dpAndRestartBt() 流程
```

---

## 4. 蓝牙重启流程

### 4.1 performBtRestart()

```
performBtRestart()
│  (在 Executors.newSingleThreadExecutor() 中执行)
│
├─ 1: 注销 HID App (如果已注册)
│   └─ hidDevice?.unregisterApp(), isAppRegistered = false
│
├─ 2: Bluetooth OFF
│   ├─ adapter.disable()
│   └─ 轮询等待 STATE_OFF (最多 40次 × 200ms = 8s)
│
├─ 3: 等待 800ms
│
├─ 4: 强制停止蓝牙进程
│   └─ Runtime.exec("am force-stop com.android.bluetooth").waitFor()
│   └─ 目的: 使蓝牙进程重启时重新读取 Settings.Global 配置
│   └─ 等待 2000ms
│
├─ 5: Bluetooth ON
│   ├─ adapter.enable()
│   └─ 轮询等待 STATE_ON (最多 40次 × 200ms = 8s)
│
├─ 6: 等待 2500ms (让蓝牙栈完全初始化)
│
├─ 7: 分支判断
│   ├─ 如果是 restore 模式 (isRestoring=true):
│   │   ├─ onBtRestartState(false)
│   │   ├─ onRestoreComplete() → Activity.finishAffinity()
│   │   └─ stopService(BluetoothHidService)
│   │
│   └─ 如果是 disable 模式:
│       └─ initProfileProxy() (见 §5)
```

### 4.2 softRestartBluetoothStackAndReinit()

```
用于: "清除连接" 按钮 / 手动重试
│
├─ 注销 HID App
├─ adapter.disable() → 等待 30×200ms
├─ 等待 800ms
├─ adapter.enable() → 等待 30×200ms
├─ 等待 1500ms
└─ initProfileProxy()
```

> ⚠️ 与 performBtRestart() 的区别: soft 版本**不做 force-stop com.android.bluetooth**，不写 A2DP 配置。

---

## 5. HID Profile 注册流程

### 5.1 initProfileProxy()

```
initProfileProxy()
│
└─ bluetoothAdapter.getProfileProxy(HID_DEVICE, ServiceListener)
    │
    ├─ onServiceConnected:
    │   ├─ hidDevice = proxy as BluetoothHidDevice
    │   ├─ 检查已连接设备 → 更新 connectedDevice
    │   └─ registerHidApp()
    │
    └─ onServiceDisconnected:
        ├─ hidDevice = null
        └─ isAppRegistered = false
```

### 5.2 registerHidApp()

```
registerHidApp()
│
├─ 1: setLocalBluetoothClassToKeyboardMouse()
│   └─ 反射: adapter.setBluetoothClass(0x000540)
│
├─ 2: 注销旧 HID App
│   └─ hidDevice.unregisterApp()
│   └─ Thread.sleep(200)
│
├─ 3: 构建 SDP 配置
│   └─ BluetoothHidDeviceAppSdpSettings(
│         name = "kboard",
│         description = "Virtual HID Keyboard",
│         provider = "Google Antigravity",
│         subclass = 0x40,
│         descriptors = HID_DESCRIPTOR  ← 键盘+鼠标组合 Report Descriptor
│       )
│
├─ 4: 注册 HID App 回调:
│   │
│   ├─ onAppStatusChanged:
│   │   ├─ 标记 isAppRegistered
│   │   └─ 如果注册成功 + !isManualDisconnect → autoReconnectToLastDevice()
│   │
│   ├─ onConnectionStateChanged:
│   │   ├─ CONNECTED → 更新 connectedDevice, save last_connected_device,
│   │   │              disableAudioProfilesForDevice()
│   │   └─ DISCONNECTED → connectedDevice = null
│   │
│   ├─ onGetReport: replyReport(空报告)
│   ├─ onSetReport: (空实现)
│   └─ onVirtualCableUnplug: connectedDevice = null
│
└─ 5: hidDevice.registerApp(sdp, null, null, executor, callback)
```

### 5.3 HID Report Descriptor (完整)

**键盘报告 (Report ID=1):** 9 字节 (8 modifiers + 1 reserved + 6 key slots)

**鼠标报告 (Report ID=2):** 5 字节 (3 buttons + 1 reserved + X + Y + Wheel)

---

## 6. HID 数据发送流程

### 6.1 键盘报告

```kotlin
sendKeyboardReport(modifiers: Byte, keycodes: ByteArray)
  → 构建 9 字节: [0x01, modifiers, keycodes[0..5], 0x00..]
  → hidDevice.sendReport(device, REPORT_ID_KEYBOARD=1, report)
```

**Modifiers bitmask:**

| Bit | 值 | 含义 |
|-----|---|------|
| 0 | 0x01 | Left Ctrl |
| 1 | 0x02 | Left Shift |
| 2 | 0x04 | Left Alt |
| 3 | 0x08 | Left GUI (Win/Cmd) |
| 4 | 0x10 | Right Ctrl |
| 5 | 0x20 | Right Shift |
| 6 | 0x40 | Right Alt |
| 7 | 0x80 | Right GUI (Win/Cmd) |

### 6.2 鼠标报告

```kotlin
sendMouseReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte)
  → 构建 5 字节: [0x02, buttons, dx, dy, wheel]
  → hidDevice.sendReport(device, REPORT_ID_MOUSE=2, report)
```

### 6.3 按键发送方法

| 方法 | 功能 | 延时 |
|------|------|:---:|
| `sendKeystroke(mod, code, holdMs=15)` | 单键按下→释放 | 15ms |
| `sendAsciiString(text)` | 逐字符 ASCII 发送 | 每字符 2ms 间隔 |
| `sendUnicodeString(text, mode)` | Unicode 直投 (Mac/Win) | 每字符 2ms 间隔 |
| `sendBypassMacKeyboardSetup()` | 跳过 Mac 键盘设置向导: 快速交替 ↑↓ | 100ms × 20 次 |
| `sendKeyboardReport(mod, keys)` | 原始键盘状态报告 | 无延时 |

### 6.4 Unicode 直投实现

**Mac 模式 (Option Hex Input):**
```
对每个中文 unicode codepoint:
  1. 转换为 4 位十六进制 (如 U+4E2D → "4E2D")
  2. 按住 Option (0x04)
  3. 逐个发送 hex 字符 (a-f→0x04-0x09, 0-9→0x1E-0x27)
  4. 释放所有键
```

**Win 模式 (Alt Numpad):**
```
对每个中文 unicode codepoint:
  1. 转换为 4 位十六进制 (如 U+4E2D → "4E2D")
  2. 按住 Alt (0x04)
  3. 逐个发送 Numpad 键 (Numpad 0-9→0x59-0x62, A-F→0x59-0x5E)
  4. 释放所有键
```

### 6.5 触摸板手势

| 手势 | 鼠标操作 | 实现 |
|------|---------|------|
| 单指移动 | 鼠标移动 | `sendMouseReport(0, dx, dy, 0)` |
| 单指点击 | 左键单击 | 按下(button=1) → 15ms → 释放(button=0) |
| 双指点击 | 右键单击 | 按下(button=2) → 15ms → 释放(button=0) |
| 双指滑动 | 滚轮 | `sendMouseReport(0, 0, 0, wheel)` |

### 6.6 物理按键绑定

**顶部双排 5 键控制台:**

| 排 | 按键 |
|----|------|
| 上排 | SHIFT (modifier) | ESC (0x29) | WIN/CMD (modifier) | ... (切换键盘) | ⌫ (0x2A) |
| 下排 | CTRL (modifier) | ALT (modifier) | C (0x06) | V (0x19) | ENTER (0x28) |

**虚拟 QWERTY 键盘:** 完整 3 行字母 + Space/Tab/Del/Alt，全部映射到 HID ScanCode

**全局键盘:** 完整 104 键布局（F1-F12 + 数字行 + 字母 + 修饰键 + 方向键），动态切换 Mac/Win 标签

### 6.7 ASR 语音发送流程

```
用户按住 Mic 按钮 (ACTION_DOWN)
│
├─ startASRProcess()
│   ├─ 获取/缓存 讯飞凭证 (HTTP: applyCredentials → authenticate)
│   ├─ 建立 WebSocket (ws://wsapi.xfyun.cn/v1/aiui)
│   └─ onStarted() → voiceController.startRecording() → AudioRecord PCM 采集
│
用户松开 (ACTION_UP)
│
├─ stopASRProcess()
│   └─ voiceController.stopRecording() + asrClient.stop() → 发送 "--end--"
│
└─ onClosed() 收到终态文本:
    ├─ Pinyin 模式: KeycodeTranslator.translateText() → sendAsciiString()
    ├─ Mac 模式: sendUnicodeString(text, MODE_MAC)
    ├─ Win 模式: sendUnicodeString(text, MODE_WIN)
    └─ 追加 Shift+Enter (换行不发送消息)
```

---

## 7. 自动配对流程

### 7.1 Just Works 配对配置

```
applyJustWorksPairing()
│
├─ 反射: adapter.setBluetoothClass(Class.forName("...").newInstance(0x000540))
│   └─ Class of Device = 0x000540 (Keyboard Peripheral)
│
└─ 反射: adapter.setIoCapability(3)
    └─ IO Capability = NoInputNoOutput (3)
    └─ 效果: 主机配对时不弹 PIN 码确认框，直接免确认配对
```

### 7.2 广播拦截配对

```
BluetoothHidService.pairingReceiver (priority=MAX)
│
├─ 收到 PAIRING_REQUEST 广播
│
├─ 显示 Toast: "已自动确认配对请求！请直接在手机上点击【配对】或【确认】即可连接"
│
├─ 根据 pairingVariant 自动处理:
│   ├─ 0 (PIN): device.setPin("0000")
│   ├─ 1 (PASSKEY): device.setPin(String.format("%06d", pairingKey))
│   ├─ 2/3 (CONFIRMATION/CONSENT): device.setPairingConfirmation(true)
│   └─ 4/5 (DISPLAY): device.setPairingConfirmation(true)
│
└─ abortBroadcast() → 阻止系统配对对话框显示
```

---

## 8. 重连与保活流程

### 8.1 自动重连 (当前已禁用)

```
startReconnectLoop()
  → return  // 已禁用! 不再定时轮询
```

### 8.2 按需重连 (touch/key trigger)

```
用户触摸屏幕 / 点击任意按键
  ↓
dispatchTouchEvent() / 各按钮 onTouch
  ↓
triggerWakeReconnectIfNeeded()
│
├─ 检查: isResumedState && !isFinishing && !isDestroyed
├─ 检查: 3 秒节流 (lastWakeReconnectTime)
├─ 检查: 已有连接 → skip
│
└─ autoReconnectToLastDevice()
    ├─ 读取 SharedPreferences: last_connected_device
    ├─ getRemoteDevice(addr)
    ├─ 检查: BOND_BONDED
    └─ hidDevice.connect(device)
```

### 8.3 ACL 连接处理

```
BluetoothAdapter 广播 → BluetoothHidService.pairingReceiver
│
├─ ACTION_ACL_CONNECTED:
│   ├─ onAclConnected(device)
│   │   ├─ disableAudioProfilesForDevice(device)
│   │   └─ 延迟 400ms → 检查连接状态 → 更新 connectedDevice
│   │
└─ ACTION_ACL_DISCONNECTED:
    └─ onAclDisconnected(device)
        └─ connectedDevice = null
```

---

## 版本记录

### v1.0.44 (2026-07-23) — A2DP 精简终版

| 改动 | 说明 |
|------|------|
| **SystemProperties.set** (反射) | 替代 `Runtime.exec("setprop")`，系统应用原生 API |
| **Settings.Global.putInt(2052)** | 替代 `putString`，整型写入 SettingsProvider |
| **进入不重启蓝牙** | `init{}` 直接 `initProfileProxy()`，去掉 `performBtOffOnCycle` |
| **退出写 2048** | `restoreA2dpAndRestartBt()` 写 `2048`（关闭 Source，保留 Sink） |
| 移除死代码 | 删除 `disableA2dpAndRestartBt` / `originalProfilesValue` / `a2dpDisabled` / `A2DP_ALL_ENABLED` |
| 清除连接加转圈 | `resetBluetoothStackToApplyHidOnly` 调用 `onBtRestartState`，`isBtResetting` 抑制"失败"提示 |
| 退出键盘按钮 | 黄色"退出键盘"按钮，确认弹窗 → disconnect → restore(2048) → finish |
| 版本号 | v1.0.44 |

**验证结果：**
- 进入 APP → `putInt(2052)` → A2DP Source+Sink 双禁用 ✅（无需重启蓝牙）
- 退出 APP → `putInt(2048)` → A2DP Source 恢复，Sink 仍禁用 ✅

### v1.0.45 (2026-07-23) — 清除连接后重注册 HID

| 改动 | 说明 |
|------|------|
| `resetBluetoothStackToApplyHidOnly` | BT OFF→ON 后主动调用 `enforceSystemHidOnlyConfiguration()` + `initProfileProxy()` |
| 清除后立即可被发现 | 之前等 HID proxy 自动重连（时间不确定），现在主动注册 |

### v1.0.46 (2026-07-23) — 连接时隐藏设备

| 改动 | 说明 |
|------|------|
| `setScanMode(21)` 连接时 | 设备连上主机后切换为 `CONNECTABLE`（不被其他主机扫描到） |
| `setScanMode(23)` 断开时 | 断开后恢复 `CONNECTABLE_DISCOVERABLE`（可被新主机发现） |
| 新增 `setScanMode()` 反射方法 | 统一管理扫描模式切换 |

### 8.4 音频 Profile 禁用 (per-device)

```
disableAudioProfilesForDevice(device)
│
└─ 对每个音频 Profile ID [2, 11, 1, 16]:
    ├─ getProfileProxy() → onServiceConnected:
    │   ├─ proxy.disconnect(device)      ← 断开音频连接
    │   └─ proxy.setConnectionPolicy(device, 0)  ← 禁止自动重连
    └─ closeProfileProxy()
```

> Profile ID 含义: 2=A2DP Sink, 11=A2DP Source, 1=Headset, 16=Headset Client

---

## 9. 清除连接流程

```
用户点击 "清除连接" 按钮
│
└─ clearAllPairsAndDisconnect()
    │
    ├─ 1: isManualDisconnect = true
    │
    ├─ 2: 断开当前 HID 连接
    │   └─ hidDevice.disconnect(connectedDevice)
    │
    ├─ 3: 清除本地状态
    │   ├─ connectedDevice = null
    │   └─ SharedPreferences: remove("last_connected_device")
    │
    ├─ 4: 清除所有配对记录
    │   └─ 枚举 bondedDevices → 反射调用 removeBond()
    │
    └─ 5: softRestartBluetoothStackAndReinit()
        (不做 A2DP 配置变更)
```

---

## 10. 退出与恢复流程

### 10.1 用户按返回键

```
onBackPressed()
│
├─ if (isExiting) return  ← 防止重复执行
│
├─ isExiting = true
├─ btRestartSpinner.visibility = VISIBLE
├─ statusText = "正在恢复蓝牙设置..."
│
└─ btService.hidManager.restoreA2dpAndRestartBt()
    │
    ├─ putString("bluetooth_disabled_profiles", originalProfilesValue ?: "0")
    ├─ isRestoring = true
    └─ performBtRestart()
        └─ 完成后 → onRestoreComplete()
            ├─ isExiting = false
            └─ finishAffinity()
```

### 10.2 被系统杀死

```
onDestroy()
│
├─ isManualDisconnect = true
├─ stopReconnectLoop()
├─ hidManager.disconnectFromHost()
│
├─ if (!isExiting): restoreA2dpAndRestartBt()  ← 后台恢复
│
├─ unbindService(serviceConnection)
├─ stopService(BluetoothHidService)
├─ voiceController.release()
├─ asrClient.release()
└─ networkExecutor.shutdown()
```

### 10.3 崩溃/Dirty Shutdown 恢复

```
下次启动 init{}:
  wasDirty == true
  → putString("bluetooth_disabled_profiles", "0")
  → 清除 dirty flag
  → 再走正常 disable 流程
```

---

## 11. Settings / SharedPreferences 写入清单

### 11.1 Settings.Global

| Key | 类型 | 写入值 | 时机 |
|-----|------|--------|------|
| `bluetooth_disabled_profiles` | String | `"2052"` | App 启动 init{} |
| `bluetooth_disabled_profiles` | String | `"0"` (或原值) | App 退出 restore |
| `bluetooth_disabled_profiles` | String | `"0"` | Dirty shutdown 恢复 |
| `bluetooth_class_of_device` | Int | `0x000540` | enforceSystemHidOnlyConfiguration() |
| `bluetooth_device_class` | Int | `0x000540` | enforceSystemHidOnlyConfiguration() |

### 11.2 System Property

| Property | 写入值 | 时机 |
|----------|--------|------|
| `persist.sys_im.blutooth.is_a2dp_dynamic` | `true` | init{} (Runtime.exec setprop) |

### 11.3 SharedPreferences

| 文件 | Key | 值 | 时机 |
|------|-----|----|------|
| `kboard_prefs` | `input_mode` | MODE_PINYIN/MAC/WIN | 用户切换输入模式 |
| `kboard_prefs` | `last_connected_device` | MAC 地址 | HID 连接成功 |
| `kboard_bt_state` | `a2dp_original` | 原始 disabled_profiles 值 | disableA2dp 时保存 |
| `kboard_bt_state` | `a2dp_dirty_shutdown` | true/false | disable/restore 时切换 |
| `xunfei_prefs` | `app_id` | 讯飞 App ID | 凭证缓存 |
| `xunfei_prefs` | `api_key` | 讯飞 API Key | 凭证缓存 |
| `xunfei_prefs` | `token` | 讯飞 Token | 凭证缓存 |

---

## 12. 当前已知问题

### 12.1 A2DP 禁用未在蓝牙栈层面生效

**状态:** ⚠️ 待确认

**现象:**
- ✅ `persist.sys_im.blutooth.is_a2dp_dynamic = true` (setprop 成功)
- ✅ `bluetooth_disabled_profiles = "2052"` (Settings.Global 写入成功)
- ✅ 代码流程完整: setprop → putString → verify → force-stop BT → restart
- ❌ `com.android.bluetooth/.a2dp.A2dpService` 仍然在运行
- ❌ `dumpsys bluetooth_manager` 显示 `mRunningProfiles.size() = 8~9`
- ❌ 定制 Bluetooth.apk 日志中未发现读取 `bluetooth_disabled_profiles` 的记录

**可能原因:**

1. **定制 Bluetooth.apk 可能未正确部署**
   - 当前 APK: versionCode=31, versionName=12 (与 AOSP 一致)
   - APK 大小 8.4MB (大于典型 AOSP 的 ~3MB，可能已修改)
   - 但 strings 搜索未找到 `blutooth` / `disabled_profile` 等关键字
   - **建议:** 从源码确认定制版本的实际文件，对比 md5

2. **persist 属性可能需要在蓝牙进程首次启动前设置**
   - `persist.*` 属性由 `property_service` 在进程启动时读取
   - 动态 setprop 后 force-stop 再启动，理论上应生效
   - 但定制 APK 的实现可能在 `AdapterService.onCreate()` 更早的阶段读取

3. **定制 Bluetooth.apk 可能使用不同的配置 Key**
   - 当前代码写入 `bluetooth_disabled_profiles`
   - 定制版本可能读取不同的 Key (如 `persist.bluetooth.disabledprofiles`)

**验证方法:**
```bash
# 完全重启蓝牙服务
adb shell "setprop persist.sys_im.blutooth.is_a2dp_dynamic true"
adb shell "settings put global bluetooth_disabled_profiles 2052"
adb shell "am force-stop com.android.bluetooth"
adb shell "svc bluetooth disable"
sleep 3
adb shell "svc bluetooth enable"
sleep 5
# 检查 A2DP 是否还在
adb shell "dumpsys bluetooth_manager | grep -i a2dp"
```

### 12.2 自动重连已禁用

`startReconnectLoop()` 直接 `return`，不执行任何轮询。仅在用户触摸屏幕/按键时触发按需重连。

### 12.3 蓝牙名称在 force-stop 后可能被重置

`force-stop com.android.bluetooth` 后由系统重新拉起时，`LOCAL_NAME_CHANGED` 广播触发，但 `KEMI-KB-XXXX` 名称可能被重置为默认值。

---

## 附录: 关键日志 TAG

| TAG | 来源 | 关注内容 |
|-----|------|---------|
| `BluetoothHidManager` | BluetoothHidManager.kt | A2DP disable/restore, BT restart, HID register |
| `MainActivity` | MainActivity.kt | 状态变更, ASR, 按键 |
| `BluetoothHidService` | BluetoothHidService.kt | 配对广播, ACL |
| `VoiceInputController` | VoiceInputController.kt | 录音状态 |
| `IflytekASRClient` | IflytekASRClient.kt | WebSocket 消息, ASR 结果 |
| `BluetoothAdapterService` | com.android.bluetooth | BT stack 状态, profile 管理 |

**抓取全部关键日志:**
```bash
adb logcat -s BluetoothHidManager:* MainActivity:* BluetoothHidService:* BluetoothAdapterService:*
```

---

## 附录 B: 自动化验证脚本

位于 `scripts/verify_a2dp.ps1`，用于一键验证 A2DP 关闭是否正确生效。

```bash
# 运行（默认连接 192.168.3.46，等待 30 秒让 app 完成蓝牙重启）
powershell -ExecutionPolicy Bypass -File scripts\verify_a2dp.ps1

# 指定设备和等待时间
powershell -ExecutionPolicy Bypass -File scripts\verify_a2dp.ps1 -Device 192.168.3.46 -WaitSec 25
```

**检查项：**
1. `bluetooth_disabled_profiles` 应为 `2052`
2. `persist.sys_im.blutooth.is_a2dp_dynamic` 应为 `true`
3. 应用日志链路：setprop → save orig → write 2052 → force-stop BT → BT OFF/ON cycle
4. Settings 值类型验证（putString/getString 一致性）

**结果文件：** `scripts/verify_a2dp_result.txt`

---

## 附录 C: 版本更新记录

### v1.0.40 (2026-07-23)

**A2DP 音频 Profile 关闭**

| 文件 | 改动 |
|------|------|
| `BluetoothHidManager.kt` | 新增 A2DP bitmask 常量 (`2052` = Sink 4 + Source 2048) |
| `BluetoothHidManager.kt` | `init{}` 增加 `setprop is_a2dp_dynamic = true` |
| `BluetoothHidManager.kt` | 新增 `disableA2dpAndRestartBt()`: 写入 `2052` → BT OFF/ON（**不 force-stop**，避免 HID 断连引发右键 bug） |
| `BluetoothHidManager.kt` | 新增 `performBtOffOnCycle()`: BT OFF → 等 800ms → BT ON → 等 1500ms → re-apply `2052`（防止 `enforceSystemHidOnlyConfiguration` 覆盖）→ `initProfileProxy` |
| `BluetoothHidManager.kt` | 新增 `restoreA2dpAndRestartBt()`: 退出时写回原始 `bluetooth_disabled_profiles` 值 |
| `BluetoothHidManager.kt` | `HidStateListener` 新增 `onBtRestartState(isRestarting)` 回调 |
| `MainActivity.kt` | 新增 `btRestartSpinner` + `onBtRestartState()` 实现：BT 重启时显示转圈动画 |
| `MainActivity.kt` | 新增"退出键盘"按钮：确认弹窗 → disconnectFromHost → restoreA2dp → finishAffinity |
| `MainActivity.kt` | `onDestroy` 增加 `isExiting` 保护，避免退出按钮与系统回调重复执行 |
| `activity_main.xml` | `statusText` 旁加 `ProgressBar` (btRestartSpinner) |
| `activity_main.xml` | 顶部状态栏加黄色"退出键盘"按钮 |

**关键发现：**
- `force-stop com.android.bluetooth` 导致 HID 连接丢失 → Mac 持续收到右键（根因已定位）
- 替换定制 `Bluetooth.apk`（MD5: `fd183196...`）后 `2052` 生效，A2DP Source/Sink 均 Disabled
- 设备 Class 确认为 `0x540` (Keyboard Peripheral)，非音频设备

**已知问题：**
- Mac 需"忘记设备"后重新配对，否则仍显示为音响设备（旧 SDP 缓存）
