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

    // Call 1: Apply for credentials from local endpoint
    fun applyCredentials(mac: String): Credentials? {
        val url = "http://www.newlinksz.cn/chat/voice/xunfei/apply"
        val cleanMac = mac.lowercase()
        val requestJson = JSONObject().apply {
            put("ai_type", "aiagent")
            put("sn", "QUALMETA-${mac.uppercase()}")
            put("project_id", "KEMI_7M600_T1")
            put("macaddr", cleanMac)
            put("system_version", "V1.0.0.1:2026-06-09:1.0.1")
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Apply credentials failed: HTTP ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                Log.d(TAG, "Apply Response: $bodyStr")
                val json = JSONObject(bodyStr)

                // The JSON can contain a nested "xunfei" object or flat keys
                val xunfeiObj = json.optJSONObject("xunfei")
                val token = xunfeiObj?.optString("token") ?: json.optString("xunfei.token") ?: json.optString("token")
                val appId = xunfeiObj?.optString("app_id") ?: json.optString("xunfei.app_id") ?: json.optString("app_id")
                val apiKey = xunfeiObj?.optString("api_key") ?: json.optString("xunfei.api_key") ?: json.optString("api_key")

                if (token.isEmpty() || appId.isEmpty() || apiKey.isEmpty()) {
                    Log.e(TAG, "Missing credentials in response: token=$token, appId=$appId, apiKey=$apiKey")
                    return null
                }

                return Credentials(token, appId, apiKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying credentials", e)
            return null
        }
    }

    // Call 2: Auth with Xunfei service
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
