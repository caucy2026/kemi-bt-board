# KEMI 语音智能键盘 (kemi-bt-board)

**KEMI 语音智能键盘 (kemi-bt-board)** 是一款运行在 Android 设备上的蓝牙外设模拟工具。它通过 Android 系统的蓝牙 HID (Human Interface Device) 协议，将 Android 设备直接模拟为**标准的物理蓝牙键盘和鼠标触摸板**。

项目的核心价值在于：**在接收端主机（如 Mac, Windows）不需要安装任何额外的接收软件或 Agent 的情况下，利用 Android 端的高精准本地语音识别（ASR）直接极速向主机直投输入中文字符。**

---

## 🌟 核心特色与功能

1. **零依赖免驱直投 (Zero-Dependency Direct Input)**:
   * **macOS 模式 (Option Hex Input)**: 利用 macOS 系统内置的“Unicode 十六进制输入”输入法，长按 Option + 录入 Hex 编码，实现高精准免驱中文录入。
   * **Windows 模式 (Alt Numpad Hex Input)**: 利用 Windows 系统的 `EnableHexNumpad` 机制进行录入。
   * **拼音模式**: 转换中文为拼音码并空格提交，实现通用设备打字回退兼容。
2. **极速传输响应**:
   * 精确的微秒级时钟微调，普通按键延时 **`5ms`**，控制修饰键延时 **`8ms`**。单字直投耗时仅约 **`40ms`**，说话松开即瞬间完成输入。
3. **ASR 自动换行**:
   * 每句语音录入松开后，自动在队列末尾追加 `Shift + Enter`，在聊天软件（如微信）中实现换行分段而不意外发送消息。
4. **对称式黄金比例布局**:
   * 采用顶部全宽状态卡片，右侧触摸板（Touchpad）自动限制在极佳的 **`4:3` 黄金滑动比例**，操作舒适。
   * **常驻双排 5 键物理控制台**: `SHIFT` | `ESC` | `WIN` | `...` | `⌫` 和 `CTRL` | `ALT` | `C` | `V` | `ENTER`。
5. **集成弹出式 QWERTY 软键盘**:
   * 点击 `...` 可直接在触摸板位置展开/收回标准的 QWERTY 虚拟小键盘，底部采用极佳对称的 `ALT` | `SPACE` | `DEL` 布局，左侧配有 `TAB`。
6. **被动断连与自动重连**:
   * 退出 App 时主动发送断开指令，拒绝假死。启动时自动识别并对上次配对的 MAC 物理地址进行平滑重连。

---

## 🛠️ 主机配置指南

为了让“直投模式”正常工作，接收端主机需要做一次简单的初始化配置：

### 1. macOS 接收端配置
1. 打开 **系统设置** -> **键盘** -> **输入法**。
2. 点击 **`+`** 号，在左侧选择列表的最下方找到 **“其它 (Others)”**，添加并启用 **“Unicode 十六进制输入 (Unicode Hex Input)”**。
3. 在需要输入中文时，将 macOS 输入法切换为 **`[U+]`** 标志的状态，即可开始语音输入。

### 2. Windows 接收端配置
1. 按 `Win + R` 键，输入 `regedit` 打开注册表编辑器。
2. 导航至 `HKEY_CURRENT_USER\Control Panel\Input Method`。
3. 在右侧空白处右键 -> 新建 -> **字符串值 (String Value)**，命名为 **`EnableHexNumpad`**，将其值设置为 **`1`**。
4. **重启电脑**使配置生效，之后即可开始直投。

---

## 📂 项目结构说明

* `app/src/main/java/com/kboard/`
  * `MainActivity.kt`: 核心 UI 操作、输入模式切换、物理底栏按键绑定、以及 ASR 发送逻辑。
  * `bluetooth/BluetoothHidManager.kt`: HID Profile 代理、SDP 协议注册与重连延迟挂载、FIF0 异步传输队列。
  * `bluetooth/KeycodeTranslator.kt`: ASCII 与 USB HID ScanCodes 映射表。
  * `asr/VoiceInputController.kt` & `IflytekASRClient.kt`: 讯飞语音听写 WebSocket 交互客户端及录音控制。
  * `ui/TouchpadView.kt`: 坐标移动 delta (dx, dy) 传输、单双指手势判定、模拟鼠标左右键与滑轮。

---

## 🚀 编译与运行

本项目采用 Gradle 进行构建，签名打包后作为系统 UID 应用运行在拥有蓝牙/系统层高权限的 Android 设备上。
可以使用项目根目录下的快捷脚本进行编译：
```bash
# 执行清理并生成 release 版本的 APK
build.bat
```
编译成功后，生成的 APK 将输出在：
`app/build/outputs/apk/release/app-release.apk`
