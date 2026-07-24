package com.kboard.net

import android.net.wifi.WifiManager
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class WinInstallServer(private val context: Context) {

    companion object {
        private const val TAG = "WinInstallServer"
        private const val ASSET_HELPER_PATH = "win-unicode-ime/WinUnicodeIME.exe"
        private const val FIXED_PORT = 8686
    }

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    fun ensureStarted(): Boolean {
        if (running && serverSocket != null && !serverSocket!!.isClosed) {
            return true
        }
        synchronized(this) {
            if (running && serverSocket != null && !serverSocket!!.isClosed) {
                return true
            }
            // Try fixed port first; fallback to OS-assigned if occupied
            val ports = intArrayOf(FIXED_PORT, 0)
            for (port in ports) {
                try {
                    val ss = ServerSocket(port)
                    ss.reuseAddress = true
                    serverSocket = ss
                    running = true
                    acceptThread = thread(start = true, name = "win-install-server") {
                        acceptLoop(ss)
                    }
                    Log.i(TAG, "Install server started at port=${ss.localPort}")
                    return true
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Port $port unavailable, trying next", e)
                }
            }
            Log.e(TAG, "Failed to start install server on any port")
            running = false
            serverSocket = null
            return false
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            acceptThread = null
        }
    }

    fun getAccessUrls(): List<String> {
        val port = serverSocket?.localPort ?: return emptyList()
        val ips = getLocalIpv4Addresses()
        return ips.map { "http://$it:$port" }
    }

    fun getPrimaryAccessUrl(): String? {
        return getAccessUrls().firstOrNull()
    }

    fun getCurrentWifiName(): String? {
        return readCurrentWifiSsid()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept()
                thread(start = true, name = "win-install-client") {
                    handleClient(client)
                }
            } catch (e: SocketException) {
                if (running) {
                    Log.w(TAG, "Socket exception in accept loop", e)
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected exception in accept loop", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 7000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))

            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) {
                writeTextDirect(output, 400, "Bad Request", "text/plain; charset=utf-8")
                return
            }

            val method = requestParts[0].uppercase(Locale.ROOT)
            val pathRaw = requestParts[1]
            val headers = readHeaders(reader)
            if (method != "GET") {
                writeTextDirect(output, 405, "Method Not Allowed", "text/plain; charset=utf-8")
                return
            }

            val path = normalizePath(pathRaw)
            when (path) {
                "/" -> {
                    val host = headers["host"]
                    val html = buildInstallHtml(host)
                    writeTextDirect(output, 200, html, "text/html; charset=utf-8")
                }

                "/install.ps1" -> {
                    val host = headers["host"]
                    val script = buildInstallPowerShell(host)
                    val bytes = script.toByteArray(StandardCharsets.UTF_8)
                    writeHeadersDirect(output, 200, "application/octet-stream", bytes.size.toLong(),
                        attachmentName = "install.ps1")
                    output.write(bytes)
                    output.flush()
                }

                "/install.bat" -> {
                    val host = headers["host"]
                    val bat = buildInstallBat(host)
                    val bytes = bat.toByteArray(Charsets.US_ASCII)
                    writeHeadersDirect(output, 200, "application/octet-stream", bytes.size.toLong(),
                        attachmentName = "install.bat")
                    output.write(bytes)
                    output.flush()
                }

                "/WinUnicodeIME.exe" -> {
                    writeBinaryDirect(output, ASSET_HELPER_PATH)
                }

                "/health" -> {
                    writeTextDirect(output, 200, "ok", "text/plain; charset=utf-8")
                }

                else -> {
                    writeTextDirect(output, 404, "Not Found", "text/plain; charset=utf-8")
                }
            }
            // Ensure all data is flushed to the socket before closing
            output.flush()
            try { client.shutdownOutput() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = ConcurrentHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase(Locale.ROOT)
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun normalizePath(pathRaw: String): String {
        val noQuery = pathRaw.substringBefore('?')
        val decoded = try {
            URLDecoder.decode(noQuery, "UTF-8")
        } catch (_: Exception) {
            noQuery
        }
        return if (decoded.startsWith("/")) decoded else "/$decoded"
    }

    private fun writeTextDirect(output: OutputStream, statusCode: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writeHeadersDirect(output, statusCode, contentType, bytes.size.toLong())
        output.write(bytes)
        output.flush()
    }

    private fun writeBinaryDirect(output: OutputStream, assetPath: String) {
        try {
            context.assets.open(assetPath).use { ins ->
                val bytes = ins.readBytes()
                Log.d(TAG, "Serving binary asset $assetPath (${bytes.size} bytes)")
                writeHeadersDirect(output, 200, "application/x-msdownload", bytes.size.toLong(),
                    attachmentName = "WinUnicodeIME.exe")
                output.write(bytes)
                output.flush()
                Log.d(TAG, "Binary asset $assetPath served successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve binary asset $assetPath", e)
            val msg = "Windows U+ helper is not bundled in this build. (${e.message})"
            writeTextDirect(output, 404, msg, "text/plain; charset=utf-8")
        }
    }

    private fun writeHeadersDirect(
        output: OutputStream,
        statusCode: Int,
        contentType: String,
        contentLength: Long,
        attachmentName: String? = null
    ) {
        val reason = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        headerBuilder.append("Content-Type: ").append(contentType).append("\r\n")
        headerBuilder.append("Content-Length: ").append(contentLength).append("\r\n")
        headerBuilder.append("Connection: close\r\n")
        attachmentName?.let {
            headerBuilder.append("Content-Disposition: attachment; filename=\"")
                .append(it)
                .append("\"\r\n")
        }
        headerBuilder.append("\r\n")
        output.write(headerBuilder.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun buildInstallHtml(hostHeader: String?): String {
        val base = resolveBaseUrl(hostHeader)
        val wifiName = readCurrentWifiSsid() ?: "未知网络"
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>KEMI Windows U+ 助手 — 安装</title>
              <style>
                * { margin:0; padding:0; box-sizing:border-box; }
                body {
                  font-family: "Segoe UI", "Microsoft YaHei", system-ui, sans-serif;
                  background: linear-gradient(135deg, #0b1120 0%, #162240 100%);
                  color: #cbd5e1;
                  min-height: 100vh;
                  display: flex; align-items: center; justify-content: center;
                  padding: 24px;
                }
                .wrap { max-width: 520px; width: 100%; }
                .logo {
                  text-align: center; margin-bottom: 24px;
                  font-size: 28px; font-weight: 700; color: #38bdf8;
                  letter-spacing: 2px;
                }
                .card {
                  background: #111c2e; border: 1px solid #1e3a5f;
                  border-radius: 16px; padding: 28px 24px;
                  box-shadow: 0 8px 40px rgba(0,0,0,.45);
                }
                h2 { font-size: 18px; font-weight: 600; color: #e2e8f0; margin-bottom: 16px; }
                .info-row {
                  display: flex; align-items: center; gap: 10px;
                  padding: 10px 14px; border-radius: 10px;
                  background: #0a1628; margin-bottom: 8px;
                  font-size: 13px;
                }
                .info-row .label { color: #64748b; flex-shrink: 0; }
                .info-row .value { color: #facc15; font-weight: 600; word-break: break-all; }
                .section-title { font-size: 14px; font-weight: 600; color: #e2e8f0; margin-top: 18px; margin-bottom: 8px; }
                .btn {
                  display: inline-block; margin-top: 8px; margin-right: 10px;
                  padding: 10px 22px; border-radius: 10px;
                  font-size: 14px; font-weight: 600; text-decoration: none;
                  cursor: pointer; border: none; transition: .15s;
                }
                .btn-primary { background: #0284c7; color: #fff; }
                .btn-primary:hover { background: #0369a1; }
                .btn-outline { background: transparent; color: #38bdf8; border: 1px solid #334155; }
                .btn-outline:hover { background: #1e293b; }
                .btn-bat { background: #16a34a; color: #fff; }
                .btn-bat:hover { background: #15803d; }
                .note {
                  margin-top: 16px; padding: 12px; border-radius: 10px;
                  background: #1a1a0a; border: 1px solid #4a3f0a;
                  font-size: 12px; color: #facc15;
                }
                .footer { text-align: center; margin-top: 20px; font-size: 11px; color: #475569; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="logo">KEMI U+ 助手</div>
                <div class="card">
                  <h2>安装（三选一）</h2>
                  <div class="info-row">
                    <span class="label">Wi-Fi</span>
                    <span class="value">$wifiName</span>
                  </div>

                  <div class="section-title">方式一：一键安装（推荐）</div>
                  <p style="font-size:12px;color:#94a3b8;">下载 install.bat → 双击运行，自动完成安装和开机自启。</p>
                  <a class="btn btn-bat" href="$base/install.bat">⬇ 下载 install.bat</a>

                  <div class="section-title">方式二：下载 EXE 手动安装</div>
                  <a class="btn btn-primary" href="$base/WinUnicodeIME.exe">下载 WinUnicodeIME.exe</a>
                  <p style="font-size:11px;color:#94a3b8;margin-top:4px;">下载后双击运行，需手动放到固定位置并添加开机自启。</p>

                  <div class="section-title">方式三：PowerShell 脚本</div>
                  <a class="btn btn-outline" href="$base/install.ps1">下载 install.ps1</a>
                  <p style="font-size:11px;color:#94a3b8;margin-top:4px;">右键 → 使用 PowerShell 运行。可能被 Windows 拦截。</p>

                  <div class="note">
                    ⚠️ 本页面通过局域网 HTTP 传输，浏览器提示"不安全"是正常现象。所有文件由手机直接提供，不会经过互联网。
                  </div>
                </div>
                <div class="footer">仅局域网内传输 · 不上传任何数据到互联网</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildInstallBat(hostHeader: String?): String {
        val base = resolveBaseUrl(hostHeader)
        // Extract host:port from base for the download URL
        return "@echo off\r\n" +
            "title KEMI U+ 助手 一键安装 v1.1.2\r\n" +
            "echo ========================================\r\n" +
            "echo   KEMI Windows U+ 助手 - 一键安装\r\n" +
            "echo ========================================\r\n" +
            "echo.\r\n" +
            "set \"d=%LOCALAPPDATA%\\KemiUnicodeIME\"\r\n" +
            "set \"e=%d%\\WinUnicodeIME.exe\"\r\n" +
            "echo [1/4] 创建安装目录: %d%\r\n" +
            "mkdir \"%d%\" 2>nul\r\n" +
            "echo        OK\r\n" +
            "echo.\r\n" +
            "echo [2/4] 下载 WinUnicodeIME.exe ...\r\n" +
            "powershell -ExecutionPolicy Bypass -Command \"[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;Invoke-WebRequest '$base/WinUnicodeIME.exe' -OutFile '%e%' -TimeoutSec 30\"\r\n" +
            "if not exist \"%e%\" (\r\n" +
            "    echo        下载失败！请确认手机 App 处于 Win 直投模式且网络连通。\r\n" +
            "    echo.\r\n" +
            "    pause\r\n" +
            "    exit /b 1\r\n" +
            ")\r\n" +
            "echo        OK\r\n" +
            "echo.\r\n" +
            "echo [3/4] 注册开机自启动...\r\n" +
            "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v KemiWinUnicodeIME /d \"\\\"%e%\\\"\" /f\r\n" +
            "echo        OK\r\n" +
            "echo.\r\n" +
            "echo [4/4] 启动 Windows U+ 助手...\r\n" +
            "start \"\" \"%e%\"\r\n" +
            "echo        OK\r\n" +
            "echo.\r\n" +
            "echo ========================================\r\n" +
            "echo   安装完成！回到 App 切 Win 直投即可输入中文。\r\n" +
            "echo ========================================\r\n" +
            "echo.\r\n" +
            "pause\r\n"
    }

    private fun buildInstallPowerShell(hostHeader: String?): String {
        val base = resolveBaseUrl(hostHeader)
        return """
            Write-Host '========================================' -ForegroundColor Cyan
            Write-Host '  KEMI Windows U+ 助手 - 一键安装 v1.1.2' -ForegroundColor Cyan
            Write-Host '========================================' -ForegroundColor Cyan
            Write-Host ''

            ${'$'}baseUrl = '$base'
            ${'$'}targetDir = Join-Path ${'$'}env:LOCALAPPDATA 'KemiUnicodeIME'
            ${'$'}exePath = Join-Path ${'$'}targetDir 'WinUnicodeIME.exe'

            Write-Host "[1/4] 创建安装目录: ${'$'}targetDir" -ForegroundColor White
            New-Item -ItemType Directory -Force -Path ${'$'}targetDir | Out-Null
            Write-Host '       OK' -ForegroundColor Green

            Write-Host "[2/4] 下载 WinUnicodeIME.exe ..." -ForegroundColor White
            try {
                Invoke-WebRequest -Uri "${'$'}baseUrl/WinUnicodeIME.exe" -OutFile ${'$'}exePath -TimeoutSec 30
                if (Test-Path ${'$'}exePath) {
                    ${'$'}size = (Get-Item ${'$'}exePath).Length
                    Write-Host "       OK (${'$'}size bytes)" -ForegroundColor Green
                } else {
                    throw "File not found after download"
                }
            } catch {
                Write-Host "       下载失败: ${'$'}_" -ForegroundColor Red
                Write-Host "       请确认手机与电脑在同一网络，且手机 App 处于 Win 直投模式。" -ForegroundColor Yellow
                Write-Host ''
                Write-Host '按任意键退出...' -ForegroundColor Gray
                ${'$'}null = ${'$'}host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
                exit 1
            }

            Write-Host "[3/4] 注册开机自启动..." -ForegroundColor White
            try {
                ${'$'}runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
                New-Item -Path ${'$'}runKey -Force | Out-Null
                Set-ItemProperty -Path ${'$'}runKey -Name 'KemiWinUnicodeIME' -Value ('"' + ${'$'}exePath + '"')
                Write-Host '       OK (HKLM\...\Run\KemiWinUnicodeIME)' -ForegroundColor Green
            } catch {
                Write-Host "       注册失败: ${'$'}_" -ForegroundColor Red
                Write-Host '       不影响使用，但下次需手动启动助手。' -ForegroundColor Yellow
            }

            Write-Host "[4/4] 启动 Windows U+ 助手..." -ForegroundColor White
            try {
                Start-Process -FilePath ${'$'}exePath
                Write-Host '       OK - 助手已在系统托盘运行' -ForegroundColor Green
            } catch {
                Write-Host "       启动失败: ${'$'}_" -ForegroundColor Red
                Write-Host "       请手动运行: ${'$'}exePath" -ForegroundColor Yellow
            }

            Write-Host ''
            Write-Host '========================================' -ForegroundColor Cyan
            Write-Host '  安装完成！回到 App 切 Win 直投即可输入中文。' -ForegroundColor Cyan
            Write-Host '========================================' -ForegroundColor Cyan
            Write-Host ''
            Write-Host '按 Enter 退出...' -ForegroundColor Gray
            try {
                ${'$'}null = ${'$'}host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
            } catch {
                Read-Host
            }
        """.trimIndent()
    }

    private fun resolveBaseUrl(hostHeader: String?): String {
        val port = serverSocket?.localPort ?: 80
        val cleanedHost = hostHeader?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedHost != null) {
            return "http://$cleanedHost"
        }
        val firstIp = getLocalIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        return "http://$firstIp:$port"
    }

    private fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (isUsableIpv4(addr)) {
                        result.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate local IP addresses", e)
        }
        return result.distinct().sorted()
    }

    private fun isUsableIpv4(addr: InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) return false
        val host = addr.hostAddress ?: return false
        return host.isNotBlank() && host != "0.0.0.0"
    }

    private fun readCurrentWifiSsid(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val info = wifiManager.connectionInfo ?: return null
            val rawSsid = info.ssid ?: return null
            val normalized = rawSsid.removePrefix("\"").removeSuffix("\"").trim()
            if (normalized.isBlank() || normalized.equals("<unknown ssid>", ignoreCase = true)) {
                null
            } else {
                normalized
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read Wi-Fi SSID", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Wi-Fi SSID", e)
            null
        }
    }
}
