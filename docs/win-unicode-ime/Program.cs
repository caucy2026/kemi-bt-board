using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

class Program : ApplicationContext
{
    // ================================================================
    // WinUnicodeIME v4 — Windows HID Unicode Hex 接收器
    //
    // 架构：WH_KEYBOARD_LL 低层全局键盘钩子 + 托盘常驻
    // 触发：按住 Left Alt → Numpad+ → 键入 4 位 hex (0-9 a-f) → 松开 Alt
    // 输出：SendInput + KEYEVENTF_UNICODE（任意应用通用）
    // ================================================================

    private const string AppName = "WinUnicodeIME";
    private const string StartupValueName = "WinUnicodeIME";
    private const string StartupRunKey = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private const string TrayText = "WinUnicodeIME - Alt+Numpad+Hex 转 Unicode";
    private const string FeatureSummary = "拦截 Alt+Numpad+Hex 并输出 Unicode 中文";

    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x0100;
    private const int WM_SYSKEYDOWN = 0x0104;
    private const uint LLKHF_UP = 0x0080;
    private const int INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    private delegate IntPtr LowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);
    private static readonly LowLevelKeyboardProc HookProc = HookCallback;
    private static IntPtr hookId = IntPtr.Zero;

    private static bool altDown = false;
    private static bool capturing = false;
    private static bool loggingEnabled = false;
    private static readonly StringBuilder hexBuf = new StringBuilder(8);
    private static readonly object syncLock = new object();
    private static StreamWriter logFile;
    private static string logPath;
    private static Mutex singleInstanceMutex;

    private readonly NotifyIcon notifyIcon;
    private readonly Icon trayManagedIcon;
    private readonly ToolStripMenuItem loggingMenuItem;

    [StructLayout(LayoutKind.Sequential)]
    private struct KBDLLHOOKSTRUCT { public uint vkCode; public uint scanCode; public uint flags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public KEYBDINPUT ki; public uint pad1; public uint pad2; }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelKeyboardProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetModuleHandle(string lpModuleName);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    [STAThread]
    static void Main()
    {
        bool createdNew;
        singleInstanceMutex = new Mutex(true, "WinUnicodeIME.SingleInstance", out createdNew);
        if (!createdNew)
        {
            MessageBox.Show("WinUnicodeIME 已在运行。", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        logPath = Path.Combine(Path.GetTempPath(), "WinUnicodeIME.log");
        TryDebugLog("=== WinUnicodeIME v4 started === Log path: " + logPath + " (disabled by default)");

        try
        {
            InstallStartup();

            using (var proc = Process.GetCurrentProcess())
            using (var mod = proc.MainModule)
                hookId = SetWindowsHookEx(WH_KEYBOARD_LL, HookProc, GetModuleHandle(mod.ModuleName), 0);

            if (hookId == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                Log("FATAL: Hook install failed. Err=" + err);
                MessageBox.Show("键盘钩子安装失败，错误码=" + err, AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new Program());
        }
        finally
        {
            if (hookId != IntPtr.Zero)
            {
                UnhookWindowsHookEx(hookId);
                hookId = IntPtr.Zero;
            }
            if (logFile != null)
            {
                try { logFile.Close(); } catch { }
                logFile = null;
            }
            if (singleInstanceMutex != null)
            {
                try { singleInstanceMutex.ReleaseMutex(); } catch { }
                singleInstanceMutex.Dispose();
                singleInstanceMutex = null;
            }
        }
    }

    private Program()
    {
        trayManagedIcon = CreateMacStyleUPlusIcon();

        var featureItem = new ToolStripMenuItem(FeatureSummary);
        featureItem.Enabled = false;

        var startupStatusItem = new ToolStripMenuItem("开机自启动: " + (IsStartupInstalled() ? "已启用" : "未启用"));
        startupStatusItem.Enabled = false;

        loggingMenuItem = new ToolStripMenuItem();
        UpdateLoggingMenuText();
        loggingMenuItem.Click += delegate { ToggleLogging(); };

        var openLogItem = new ToolStripMenuItem("打开日志", null, delegate { OpenLog(); });
        var aboutItem = new ToolStripMenuItem("查看功能说明", null, delegate { ShowAbout(); });
        var exitItem = new ToolStripMenuItem("退出", null, delegate { ExitThread(); });

        notifyIcon = new NotifyIcon();
        notifyIcon.Icon = trayManagedIcon;
        notifyIcon.Text = TrayText;
        notifyIcon.Visible = true;
        notifyIcon.ContextMenuStrip = new ContextMenuStrip();
        notifyIcon.ContextMenuStrip.Items.Add(featureItem);
        notifyIcon.ContextMenuStrip.Items.Add(startupStatusItem);
        notifyIcon.ContextMenuStrip.Items.Add(loggingMenuItem);
        notifyIcon.ContextMenuStrip.Items.Add(new ToolStripSeparator());
        notifyIcon.ContextMenuStrip.Items.Add(aboutItem);
        notifyIcon.ContextMenuStrip.Items.Add(openLogItem);
        notifyIcon.ContextMenuStrip.Items.Add(exitItem);
        notifyIcon.DoubleClick += delegate { ShowAbout(); };
        notifyIcon.BalloonTipTitle = AppName;
        notifyIcon.BalloonTipText = FeatureSummary + "\n日志默认关闭，需要时可在托盘手动启用。";
        notifyIcon.ShowBalloonTip(3000);

        Log("Hook OK. Tray icon visible. Startup enabled=" + IsStartupInstalled());
    }

    private void ToggleLogging()
    {
        if (!loggingEnabled)
        {
            var result = MessageBox.Show(
                "默认不保存日志。\n\n确认后将开始写入日志到:\n" + logPath +
                "\n\n是否启用日志记录？",
                AppName,
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (result != DialogResult.Yes)
            {
                return;
            }
            SetLoggingEnabled(true);
            MessageBox.Show("日志记录已启用。", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        else
        {
            SetLoggingEnabled(false);
            MessageBox.Show("日志记录已关闭。", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        UpdateLoggingMenuText();
    }

    private void UpdateLoggingMenuText()
    {
        loggingMenuItem.Text = "日志记录: " + (loggingEnabled ? "开启" : "关闭（点击启用）");
    }

    private static void SetLoggingEnabled(bool enabled)
    {
        if (enabled == loggingEnabled) return;

        if (enabled)
        {
            try
            {
                logFile = new StreamWriter(logPath, true) { AutoFlush = true };
                loggingEnabled = true;
                Log("=== Logging enabled ===");
            }
            catch (Exception ex)
            {
                loggingEnabled = false;
                logFile = null;
                MessageBox.Show("启用日志失败: " + ex.Message, AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
        else
        {
            try { if (logFile != null) logFile.WriteLine("=== Logging disabled ==="); } catch { }
            try { if (logFile != null) logFile.Close(); } catch { }
            logFile = null;
            loggingEnabled = false;
            TryDebugLog("Logging disabled by user.");
        }
    }

    protected override void ExitThreadCore()
    {
        Log("Shutting down...");
        if (notifyIcon != null)
        {
            notifyIcon.Visible = false;
            notifyIcon.Dispose();
        }
        if (trayManagedIcon != null)
        {
            trayManagedIcon.Dispose();
        }
        base.ExitThreadCore();
    }

    private static Icon CreateMacStyleUPlusIcon()
    {
        Bitmap bitmap = new Bitmap(16, 16);
        using (Graphics g = Graphics.FromImage(bitmap))
        {
            g.Clear(Color.Transparent);

            Color bg = Color.White;
            Color border = Color.FromArgb(180, 180, 180);
            Color fg = Color.FromArgb(51, 51, 51);

            // Pixel-aligned rounded rectangle (Mac style white badge).
            bitmap.SetPixel(2, 1, bg);
            for (int x = 3; x <= 12; x++) bitmap.SetPixel(x, 1, bg);
            bitmap.SetPixel(13, 1, bg);

            for (int y = 2; y <= 13; y++)
            {
                bitmap.SetPixel(1, y, bg);
                for (int x = 2; x <= 13; x++) bitmap.SetPixel(x, y, bg);
                bitmap.SetPixel(14, y, bg);
            }

            bitmap.SetPixel(2, 14, bg);
            for (int x = 3; x <= 12; x++) bitmap.SetPixel(x, 14, bg);
            bitmap.SetPixel(13, 14, bg);

            // Border.
            for (int x = 3; x <= 12; x++)
            {
                bitmap.SetPixel(x, 1, border);
                bitmap.SetPixel(x, 14, border);
            }
            for (int y = 3; y <= 12; y++)
            {
                bitmap.SetPixel(1, y, border);
                bitmap.SetPixel(14, y, border);
            }
            bitmap.SetPixel(2, 2, border);
            bitmap.SetPixel(13, 2, border);
            bitmap.SetPixel(2, 13, border);
            bitmap.SetPixel(13, 13, border);

            // "U" glyph.
            for (int y = 4; y <= 8; y++)
            {
                bitmap.SetPixel(4, y, fg);
                bitmap.SetPixel(7, y, fg);
            }
            for (int x = 4; x <= 7; x++) bitmap.SetPixel(x, 9, fg);

            // "+" glyph.
            for (int x = 9; x <= 13; x++) bitmap.SetPixel(x, 6, fg);
            for (int y = 4; y <= 8; y++) bitmap.SetPixel(11, y, fg);
        }

        IntPtr hIcon = bitmap.GetHicon();
        Icon icon = Icon.FromHandle(hIcon);
        Icon clonedIcon = (Icon)icon.Clone();
        DestroyIcon(hIcon);
        icon.Dispose();
        bitmap.Dispose();
        return clonedIcon;
    }

    private static void InstallStartup()
    {
        string exePath = Application.ExecutablePath;
        string value = '"' + exePath + '"';
        try
        {
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(StartupRunKey))
            {
                if (key != null)
                {
                    object currentValue = key.GetValue(StartupValueName);
                    if (!string.Equals(currentValue as string, value, StringComparison.OrdinalIgnoreCase))
                    {
                        key.SetValue(StartupValueName, value, RegistryValueKind.String);
                    }
                }
            }
            Log("Startup entry ensured: " + value);
        }
        catch (Exception ex)
        {
            Log("Failed to install startup entry: " + ex.Message);
        }
    }

    private static bool IsStartupInstalled()
    {
        try
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(StartupRunKey, false))
            {
                if (key == null) return false;
                string currentValue = key.GetValue(StartupValueName) as string;
                string expectedValue = '"' + Application.ExecutablePath + '"';
                return string.Equals(currentValue, expectedValue, StringComparison.OrdinalIgnoreCase);
            }
        }
        catch
        {
            return false;
        }
    }

    private static void OpenLog()
    {
        if (!loggingEnabled)
        {
            MessageBox.Show("日志当前未启用。\n请先在托盘菜单里开启日志记录。", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!File.Exists(logPath))
        {
            MessageBox.Show("日志文件尚未生成。", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        try
        {
            var startInfo = new ProcessStartInfo(logPath);
            startInfo.UseShellExecute = true;
            Process.Start(startInfo);
        }
        catch (Exception ex)
        {
            MessageBox.Show("打开日志失败: " + ex.Message, AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void ShowAbout()
    {
        string message = FeatureSummary + "\n\n" +
            "工作方式:\n" +
            "1. 拦截 Alt + Numpad+ + 4位Hex\n" +
            "2. 阻止 A-F 被应用菜单抢占\n" +
            "3. 转成 Unicode 直接输入到当前窗口\n\n" +
            "日志状态: " + (loggingEnabled ? "已开启" : "已关闭") +
            "\n日志路径: " + logPath;
        MessageBox.Show(message, AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode < 0) return CallNextHookEx(hookId, nCode, wParam, lParam);
        try
        {
            int msg = (int)wParam;
            var kb = (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(KBDLLHOOKSTRUCT));
            bool isUp = (kb.flags & LLKHF_UP) != 0;

            lock (syncLock)
            {
                if (!isUp && (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN))
                {
                    if (kb.vkCode == 0xA4)
                    {
                        altDown = true; capturing = false; hexBuf.Clear();
                        Log("ALT DN");
                        return (IntPtr)1;
                    }
                    if (altDown)
                    {
                        if (!capturing && kb.vkCode == 0x6B)
                        {
                            capturing = true;
                            Log("  NUMPAD+ - capture started");
                            return (IntPtr)1;
                        }
                        if (!capturing)
                        {
                            Log("  X before Numpad+ vk=0x" + kb.vkCode.ToString("X2") + " CANCEL");
                            altDown = false; hexBuf.Clear();
                            return CallNextHookEx(hookId, nCode, wParam, lParam);
                        }
                        char hc = VkToHex(kb.vkCode);
                        if (hc != '\0')
                        {
                            hexBuf.Append(hc);
                            Log("  +" + hc + "=" + hexBuf);
                            return (IntPtr)1;
                        }
                        Log("  X vk=0x" + kb.vkCode.ToString("X2") + " CANCEL");
                        altDown = false; capturing = false; hexBuf.Clear();
                    }
                }
                else if (isUp && kb.vkCode == 0xA4 && altDown)
                {
                    altDown = false;
                    if (capturing && hexBuf.Length == 4)
                    {
                        string h = hexBuf.ToString();
                        int cp;
                        if (int.TryParse(h, System.Globalization.NumberStyles.HexNumber, null, out cp) && cp > 0)
                        {
                            Log("OUT U+" + cp.ToString("X4"));
                            OutputUnicodeChar(cp);
                        }
                    }
                    else
                    {
                        Log("ALT UP buf=" + hexBuf + " len=" + hexBuf.Length);
                    }
                    capturing = false; hexBuf.Clear();
                    return (IntPtr)1;
                }
                else if (isUp && altDown && capturing &&
                         (kb.vkCode == 0x6B || VkToHex(kb.vkCode) != '\0'))
                {
                    return (IntPtr)1;
                }
            }
        }
        catch (Exception ex)
        {
            Log("ERR: " + ex.Message);
        }
        return CallNextHookEx(hookId, nCode, wParam, lParam);
    }

    private static char VkToHex(uint vk)
    {
        if (vk >= '0' && vk <= '9') return (char)vk;
        if (vk >= 0x30 && vk <= 0x39) return (char)vk;
        if (vk >= 0x41 && vk <= 0x46) return (char)(vk + 32);
        if (vk >= 0x60 && vk <= 0x69) return (char)(vk - 0x60 + '0');
        return '\0';
    }

    private static void OutputUnicodeChar(int cp)
    {
        string text = char.ConvertFromUtf32(cp);
        INPUT[] inputs = new INPUT[text.Length * 2];
        int i = 0;
        foreach (char c in text)
        {
            inputs[i].type = INPUT_KEYBOARD; inputs[i].ki.wScan = (ushort)c; inputs[i].ki.dwFlags = KEYEVENTF_UNICODE; i++;
            inputs[i].type = INPUT_KEYBOARD; inputs[i].ki.wScan = (ushort)c; inputs[i].ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP; i++;
        }
        int inputSize = Marshal.SizeOf(typeof(INPUT));
        uint sent = SendInput((uint)inputs.Length, inputs, inputSize);
        Log("SendInput sent=" + sent + "/" + inputs.Length + " size=" + inputSize + " err=" + Marshal.GetLastWin32Error());
    }

    private static void Log(string s)
    {
        string line = string.Format("[{0:HH:mm:ss.fff}] {1}", DateTime.Now, s);
        TryDebugLog(line);
        if (!loggingEnabled) return;
        try { if (logFile != null) logFile.WriteLine(line); } catch { }
    }

    private static void TryDebugLog(string s)
    {
        try { Debug.WriteLine(s); } catch { }
    }
}
