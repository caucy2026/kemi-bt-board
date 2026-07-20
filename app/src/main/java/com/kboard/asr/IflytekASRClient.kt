package com.kboard.asr

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.MessageDigest

class IflytekASRClient(
    private val credentials: XunfeiCredentialProvider.Credentials,
    private val wifiMac: String,
    private val callback: ASRCallback
) {

    interface ASRCallback {
        fun onStarted()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(code: Int, message: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "IflytekASRClient"
        private const val WS_URL = "ws://wsapi.xfyun.cn/v1/aiui"
    }

    private val client = OkHttpClient.Builder().build()
    private var webSocket: WebSocket? = null
    private var isSessionActive = false

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun start() {
        val curTime = (System.currentTimeMillis() / 1000).toString()
        val cleanMac = wifiMac.replace(":", "").lowercase()
        val authId = md5(cleanMac)

        // Build parameters JSON
        val paramJson = JSONObject().apply {
            put("result_level", "plain")
            put("auth_id", authId)
            put("data_type", "audio")
            put("aue", "raw")
            put("scene", "main")
            put("sample_rate", "16000")
            put("dwa", "wpgs")
            put("cloud_vad_eos", "60000") // Large eos to avoid early cutoff during holding
        }

        val paramBase64 = Base64.encodeToString(
            paramJson.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        // Calculate checksum
        val checksum = sha256(credentials.apiKey + curTime + paramBase64)

        // Construct WS handshake URL
        val url = "$WS_URL?appid=${credentials.appId}&checksum=$checksum&curtime=$curTime&param=$paramBase64&signtype=sha256"

        val request = Request.Builder()
            .url(url)
            .header("Origin", "http://wsapi.xfyun.cn")
            .build()

        isSessionActive = true

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "OnMessage: $text")
                try {
                    val json = JSONObject(text)
                    val action = json.optString("action")
                    val code = json.optInt("code", -1)

                    if (code != 0) {
                        val desc = json.optString("desc")
                        Log.e(TAG, "Xunfei ASR returned error code: $code, desc: $desc")
                        callback.onError(code, desc)
                        return
                    }

                    when (action) {
                        "started" -> {
                            callback.onStarted()
                        }
                        "result" -> {
                            val dataObj = json.optJSONObject("data")
                            if (dataObj != null) {
                                val sub = dataObj.optString("sub")
                                if (sub == "iat") {
                                    val resultText = dataObj.optString("text")
                                    val isFinish = dataObj.optBoolean("is_finish", false)
                                    
                                    if (isFinish) {
                                        Log.d(TAG, "Final Result text: $resultText")
                                        callback.onFinalResult(resultText)
                                    } else {
                                        Log.d(TAG, "Partial Result text: $resultText")
                                        callback.onPartialResult(resultText)
                                    }
                                }
                            }
                        }
                        "error" -> {
                            val desc = json.optString("desc")
                            callback.onError(code, desc)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed")
                isSessionActive = false
                callback.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isSessionActive = false
                callback.onError(-1, t.message ?: "Handshake / network failure")
                callback.onClosed()
            }
        })
    }

    // Sends raw PCM chunk to ASR server
    fun sendAudio(buffer: ByteArray, len: Int) {
        val ws = webSocket ?: return
        if (!isSessionActive) return
        val byteString = buffer.toByteString(0, len)
        ws.send(byteString)
    }

    // Sends the end sign "--end--" to Xunfei to signal completion of speech
    fun stop() {
        val ws = webSocket ?: return
        if (!isSessionActive) return
        Log.d(TAG, "Sending '--end--' token to ASR server")
        // Send end token ASCII bytes
        val endBytes = "--end--".toByteArray(Charsets.US_ASCII)
        ws.send(endBytes.toByteString())
    }

    fun release() {
        webSocket?.close(1000, "App closed")
        webSocket = null
        isSessionActive = false
    }
}
