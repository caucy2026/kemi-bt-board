package com.kboard.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInputController(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputController"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 3200 // ~100ms of 16kHz 16-bit mono PCM (16000 * 2 bytes * 0.1s = 3200 bytes)
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording(asrClient: IflytekASRClient) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioRecord")
            return
        }

        // Ensure buffer size is larger than min buffer size and multiple of chunk size
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                release()
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread {
                val buffer = ByteArray(CHUNK_SIZE)
                Log.d(TAG, "Audio recording thread started")
                while (isRecording.get()) {
                    val record = audioRecord ?: break
                    val readBytes = record.read(buffer, 0, CHUNK_SIZE)
                    if (readBytes > 0) {
                        asrClient.sendAudio(buffer, readBytes)
                    } else if (readBytes < 0) {
                        Log.e(TAG, "Error reading audio data: $readBytes")
                        break
                    }
                }
                Log.d(TAG, "Audio recording thread finished")
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio record", e)
            release()
        }
    }

    fun stopRecording(asrClient: IflytekASRClient?) {
        if (!isRecording.get()) return
        Log.d(TAG, "Stopping audio recording")
        isRecording.set(false)

        try {
            recordThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for record thread to finish", e)
        }
        recordThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        // Send --end-- to Xunfei ASR Client to initiate final sentence reconstruction
        asrClient?.stop()
    }

    fun release() {
        isRecording.set(false)
        recordThread = null
        audioRecord?.release()
        audioRecord = null
    }
}
