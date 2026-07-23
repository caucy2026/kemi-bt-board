package com.kboard.asr

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class XunfeiCredentialProvider {

    companion object {
        private const val TAG = "XunfeiCredential"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder().build()

    data class Credentials(
        val token: String,
        val appId: String,
        val apiKey: String
    )

    // HTTP Auth with Xunfei service (per iflytek_asr_interface_doc.md §3)
    fun authenticate(mac: String, token: String): Boolean {
        val url = "http://api.voice.gskiot.com/voice-api/voice/auth"
        val timestamp = System.currentTimeMillis().toString()
        val sn = "QUALMETA-$mac"

        val requestJson = JSONObject().apply {
            put("xiriSn", mac)
            put("license", token)
            put("channel", "NEWLINK01")
            put("devicewifiMac", mac)
            put("deviceMac", mac)
            put("sn", sn)
            put("clientVersion", "V1.0.1.2:2026-06-02:V1.4.4")
            put("timestamp", timestamp)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Auth HTTP call failed: HTTP ${response.code}")
                    return false
                }
                val bodyStr = response.body?.string() ?: return false
                Log.d(TAG, "Auth Response: $bodyStr")
                val json = JSONObject(bodyStr)
                val code = json.optString("code")
                val dataObj = json.optJSONObject("data")
                val status = dataObj?.optInt("status", -1) ?: -1

                return code == "00000" && status == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during authentication", e)
            return false
        }
    }
}
