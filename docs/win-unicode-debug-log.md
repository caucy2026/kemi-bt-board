# Windows 蓝牙 HID Unicode 直投调试总结

> 2026-07-24 · kemi-bt-board v1.1.1

---

## 以"你"字为例，从头讲清楚

"你"的 Unicode 码点是 **U+4F60**。要在 Windows 上输入它，Android 通过蓝牙 HID 发送键盘按键，Windows 收到后转换成字符。

---

## 方案一（失败）：纯 Windows 原生 HexNumpad

### 原理

Windows 有个隐藏功能：`EnableHexNumpad=1`（注册表）。开启后，在**任何应用中**按住 Alt，按小键盘 `+`，再输入 4 位十六进制，松开 Alt，Windows 就把这 4 位 hex 转成 Unicode 字符。

### Android 实际发送了什么

8 字节 HID 键盘报告（modifiers + 6 个键码），逐帧发送：

```
时序  报告内容                           含义
──────────────────────────────────────────────────
 1    mod=0x04  keys=[]                  Alt 按下
 2    mod=0x04  keys=[0x57]             Keypad+ 按下（0x57）
 3    mod=0x04  keys=[]                 Keypad+ 松开，Alt 保持

 4    mod=0x04  keys=[0x21]             "4" 按下（0x21 = 主键盘 4）
 5    mod=0x04  keys=[]                 "4" 松开
 6    mod=0x04  keys=[0x09]             "F" 按下（0x09 = 主键盘 F）
 7    mod=0x04  keys=[]                 "F" 松开
 8    mod=0x04  keys=[0x23]             "6" 按下（0x23 = 主键盘 6）
 9    mod=0x04  keys=[]                 "6" 松开
10    mod=0x04  keys=[0x27]             "0" 按下（0x27 = 主键盘 0）
11    mod=0x04  keys=[]                 "0" 松开

12    mod=0x00  keys=[]                 Alt 松开 → 此时 Windows 应输出 "你"
```

### 实际结果

记事本**什么也没收到**（TextLength=0）。

### 为什么失败

问题出在第 6 步：`mod=0x04 + keys=[0x09]`。

这个组合在 Windows 眼里是 **`Alt+F`**。而 `Alt+F` 是记事本的**"文件"菜单**快捷键。Windows 把 `F` 当成菜单命令吃掉了，根本没把它当十六进制数字用。

```
Alt + Keypad+ → 进入 HexNumpad 模式
4  → ✅ 正确识别为十六进制数字
F  → ❌ 被记事本解释为 Alt+F 菜单，HexNumpad 解析中断
6、0 → 已经无效，因为 F 破坏了输入序列
Alt↑ → 无有效 hex 可提交
```

### 旁证：纯数字码点可以成功

如果输入的是 U+2468（⑨），整个序列是 `2→4→6→8`，没有 A-F：

```
Alt↓ → Keypad+ → 2 → 4 → 6 → 8 → Alt↑
```

**没有任何键被菜单抢占**，HexNumpad 完整收到 `2468`，成功输出 `⑨`。

这就证明了：
- ✅ HID Descriptor 正确，Keypad+ 被 Windows 识别
- ✅ `EnableHexNumpad` 注册表已生效
- ✅ 蓝牙 HID 链路正常
- ❌ 唯一的问题是 **A-F 字母键会被应用菜单抢走**

---

## 尝试过的方案（全部失败）

### 尝试一：加 Ctrl 抑制菜单

思路：`Ctrl+Alt+F` 不会触发记事本菜单（菜单是 `Alt+F`，不是 `Ctrl+Alt+F`）。

Android 发送：

```
mod=0x05 (LeftCtrl + LeftAlt) + keys=[0x09]
```

结果：**TextLength=0**。Windows HexNumpad 只接受**纯净的 Alt + 字母**，多了 Ctrl 就完全不认。

### 尝试二：用 Shift 产生大写

思路：Windows 菜单区分大小写——`Alt+F` 触发菜单，`Alt+Shift+F` 不一定。

结果：**TextLength=0**。HexNumpad 不解析大写的 A-F。

### 尝试三：Ctrl 先放、Alt 后放

思路：Ctrl 保持到 hex 输完，然后先松开 Ctrl、保持 Alt，再松开 Alt 提交。

结果：**TextLength=0**。无论释放顺序如何，只要序列中有 Ctrl，HexNumpad 就不工作。

### 尝试四：十进制 Alt 码

思路：绕开十六进制，用 `Alt+十进制码点`（`Alt+20320`）。

结果：
- `Alt+20320` → 输出的是 `U+0060`，不是 `U+4F60`
- `Alt+020320` → 输出的是 `U+003F`

**十进制 Alt 码根本不出 Unicode**。

---

## 最终方案（成功）：Windows 接收器

### 核心思路

既然 A-F 会在应用层被菜单抢走，那就在**按键到达应用之前**拦截它。

### 第一步：拦截按键

在 Windows 上运行一个全局键盘钩子程序（`WinUnicodeIME.exe`）。这个钩子优先级高于应用，它在**系统层**看到每一个按键，**比记事本更早**。

Android 发送的 12 帧 HID 报告不变（和"方案一"完全一样），但这次 Windows 侧的接收流程变了：

```
Android 发送:  Alt↓ → Keypad+ → 4 → F → 6 → 0 → Alt↑
                         ↓
            ┌─────────────────────────┐
            │  WinUnicodeIME.exe      │
            │  (WH_KEYBOARD_LL 钩子)  │
            │                         │
            │  看到 Alt↓              │
            │  看到 Keypad+ → 开始捕获│
            │  看到 4 → hexBuf="4"    │
            │  看到 F → hexBuf="4f"   │ ← 钩子直接吞掉，return (IntPtr)1
            │  看到 6 → hexBuf="4f6"  │   记事本 / 任何应用都看不到这个 "F"
            │  看到 0 → hexBuf="4f60" │
            │  看到 Alt↑ → 触发输出   │
            │                         │
            │  解析 "4f60" → 0x4F60   │
            └────────┬────────────────┘
                     ↓
            SendInput(KEYEVENTF_UNICODE, wScan=0x4F60)
                     ↓
              记事本收到 "你" ✅
```

### 关键：钩子吞掉按键

钩子函数中，每收到一个十六进制按键，返回 `(IntPtr)1` 而不是调用 `CallNextHookEx`。这意味着**这个按键不会继续往下传递**：

```csharp
// 吞掉 hex 按键，不让它到达记事本
if (hc != '\0') {
    _hexBuf.Append(hc);
    Log("  +" + hc + "=" + _hexBuf);
    return (IntPtr)1;  // ← 就这一行，阻止了 Alt+F 菜单
}
```

### 第二步：输出 Unicode

解析完成后，不用任何 HID 方式，直接用 Windows API `SendInput` 的 **`KEYEVENTF_UNICODE`** 模式：

```csharp
INPUT[] inputs = new INPUT[2];
// 按下 "你"
inputs[0].type = INPUT_KEYBOARD;
inputs[0].ki.wScan = 0x4F60;           // Unicode 码点直接放进 wScan
inputs[0].ki.dwFlags = KEYEVENTF_UNICODE;
// 松开 "你"
inputs[1].type = INPUT_KEYBOARD;
inputs[1].ki.wScan = 0x4F60;
inputs[1].ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;

SendInput(2, inputs, 40);  // 40 字节 = x64 INPUT 结构大小
```

`KEYEVENTF_UNICODE` 是 Windows 的"万能输入"方式——不管当前输入法是什么，直接向目标窗口注入 Unicode 字符。

### 第三步：完整数据流（实测日志）

以 `你好⑨` 为例，Android 每字发送 12 帧 HID 报告：

| Android logcat | 接收器日志 | 记事本 |
|:-:|:-|:-:|
| `KB RPT OK: mod=0x4 keys=[]` (Alt) | `ALT DN` | |
| `KB RPT OK: mod=0x4 keys=[0x57]` | `NUMPAD+ — capture started` | |
| `KB RPT OK: mod=0x4 keys=[0x27]` | `+4=4` | |
| `KB RPT OK: mod=0x4 keys=[0x09]` | `+f=4f` | |
| `KB RPT OK: mod=0x4 keys=[0x23]` | `+6=4f6` | |
| `KB RPT OK: mod=0x4 keys=[0x27]` | `+0=4f60` | |
| `KB RPT OK: mod=0x0 keys=[]` | `OUT U+4F60` | |
| | `SendInput sent=2/2 size=40 err=0` | `U+4F60` ("你") |
| (下一字重复上述过程) | `+5=5, +9=59, +7=597, +d=597d, OUT U+597D` | `U+597D` ("好") |
| | `+2=2, +4=24, +6=246, +8=2468, OUT U+2468` | `U+2468` ("⑨") |

**最终记事本内容**：`你好⑨`，逐字码点 `U+4F60 U+597D U+2468`，**零错误**。

---

## 中间踩过的坑

### 坑 1：x64 INPUT 结构体布局错误

**现象**：接收器日志显示 `OUT U+4F60`，但记事本为空。

**原因**：C# 中 `INPUT` 是 native union。在 x64 上结构体必须 40 字节，union 从偏移 8 开始。原始代码用了 `[FieldOffset(4)]`，size 不对，`SendInput` 静默失败但没有崩溃。

**修复前**：
```csharp
[StructLayout(LayoutKind.Explicit)]
struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(4)] public KEYBDINPUT ki; }
```

**修复后**：
```csharp
[StructLayout(LayoutKind.Sequential)]
struct INPUT { public uint type; public KEYBDINPUT ki; public uint pad1; public uint pad2; }
```

修复后 `SendInput sent=2/2 size=40 err=0`，字符立刻进入记事本。

### 坑 2：蓝牙注册冲突

**现象**：Android 日志 `registerApp(): failed because another app is registered`。

**原因**：安装替换 APK 后，旧进程的 HID 注册没来得及清理，`svc bluetooth disable/enable` 连续执行太快。

**修复**：先 `disable` → `dumpsys bluetooth_manager` 确认 `state: OFF` → 再 `enable` → 确认 `state: ON` → 最后启动应用。改用广播事件替代 Thread.sleep 轮询。

### 坑 3：ADB 测试 action 写错

闭环测试脚本中 action 写了 `com.kboard.TEST_WIN_HEX`，但源码定义的是 `com.kboard.action.TEST_WIN_HEX`。导致多轮测试没进入 `sendUnicodeString()`，记事本为空，却被误判为协议失败。

---

## 总结

| | 方案一（失败） | 最终方案（成功） |
|------|:---:|:---:|
| Android 发送 | Alt + Keypad+ + Hex | **相同** |
| Windows 侧 | 系统原生 EnableHexNumpad | WinUnicodeIME.exe 全局钩子 |
| 遇到 A-F | 被应用菜单抢走 → 解析中断 | **钩子吞掉，不传给应用** |
| 输出方式 | 系统 HexNumpad 转码 | SendInput + KEYEVENTF_UNICODE |
| 纯数字码点 | ✅ 可以 | ✅ 可以 |
| 含 A-F 中文 | ❌ 失败 | ✅ 成功 |

**一句话**：不是 Android 发错了，是 Windows 把 `Alt+F` 当菜单快捷键吃掉了。解决方案是在按键到达应用之前用钩子拦截，自己解析码点后用 `KEYEVENTF_UNICODE` 输出。
