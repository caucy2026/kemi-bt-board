# KEMI 语音智能键盘 (kemi-bt-board)

**KEMI 语音智能键盘 (kemi-bt-board)** 是一款运行在 Android 设备上的蓝牙外设模拟工具。它通过 Android 系统的蓝牙 HID (Human Interface Device) 协议，将 Android 设备直接模拟为**标准的物理蓝牙键盘和鼠标触摸板**。

项目通过 Android 端的高精准本地语音识别（ASR）向主机直投中文：macOS 使用系统内置 Unicode 输入法，Windows 使用项目附带的轻量接收器拦截 HID 十六进制序列并输出 Unicode。

---

## 🌟 核心特色与功能

1. **标准 HID 直投 (Standard HID Direct Input)**:
   * **macOS 模式 (Option Hex Input)**: 利用 macOS 系统内置的“Unicode 十六进制输入”输入法，长按 Option + 录入 Hex 编码，实现高精准免驱中文录入。
   * **Windows 模式 (Alt Numpad Hex Input)**: Android 发送标准 `Alt + Numpad+ + Hex` 序列，配套 `WinUnicodeIME.exe` 负责阻止 A-F 被应用菜单抢占并输出 Unicode。
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

### 2. Windows 接收端配置（v1.1.2 新方案）

**无需提前准备文件——App 内置安装服务，同网络即可完成。**

1. 在 Android App 中切换到 **Win 直投** 模式，后台自动启动安装服务（端口 8686）。
2. 点击「如何安装 Windows U+ 助手」查看访问地址（如 `http://192.168.x.x:8686`）。
3. 确保 Windows 与手机在同一局域网，浏览器输入该地址。
4. 下载 **install.bat** → 双击运行，自动完成：下载 EXE → 注册开机自启 → 启动助手。
5. 助手在系统托盘运行后，回到 App 的 Win 直投模式即可输入中文。

> 旧方案（手动复制 EXE + 注册表 EnableHexNumpad）仍可用，但推荐新方案更简单可靠。

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

---

## 📋 版本记录

### v1.1.2 (2026-07-24)
- **Windows U+ 助手一键安装**：App 内置 HTTP 服务（固定端口 8686），同局域网浏览器访问即可下载安装
- **install.bat 方案**：下载双击自动完成 EXE 安装 + 开机自启注册，避免 PowerShell 脚本转义/执行策略问题
- **安装说明页重设计**：三选一安装方式（bat / EXE / ps1），Wi-Fi 名称和地址实时显示
- **切 Win 直投自动启动后台服务**，无需额外操作
- 修复 install.ps1 右键运行闪退（Mark of the Web + 执行策略 + `$` 变量展开）
- 修复 EXE 二进制下载 BufferedOutputStream 截断问题
- 安装页添加局域网 HTTP 安全提示

### v1.1.0
- 修复动画/ASR重构/蓝牙优化

### v1.0.45-46
- 清除连接重注册HID+连接时隐藏设备(setScanMode)

### v1.0.44
- A2DP终版: SystemProperties.set+putInt(2052), 不重启蓝牙, 退出写2048

### v1.0.41
- 清除连接时不显示失败+reset加转圈+isBtResetting提前屏蔽

### v1.0.40
- A2DP音频关闭 + BT重启动画 + 退出键盘按钮 + 完整文档
