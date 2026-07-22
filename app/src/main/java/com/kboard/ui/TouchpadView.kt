package com.kboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kboard.R
import com.kboard.bluetooth.BluetoothHidManager
import kotlin.math.abs
import kotlin.math.roundToInt

class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hidManager: BluetoothHidManager? = null

    // Touch tracking state
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isMove = false
    private var activePointerId = -1

    // Multi-touch scroll tracking
    private var isScroll = false
    private var lastScrollY = 0f
    private var scrollThreshold = 10f

    // Configs
    private val clickDurationThreshold = 200L // ms
    private val moveThreshold = 8f // pixels
    private var sensitivity = 1.2f
    private var scrollSensitivity = 5f

    // Visual feedback paint
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.touchpad_bg)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.touchpad_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val yellowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#FFCA28")
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setHidManager(manager: BluetoothHidManager) {
        this.hidManager = manager
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw background and rounded borders
        val rx = 24f
        val ry = 24f
        canvas.drawRoundRect(2f, 2f, width.toFloat() - 2f, height.toFloat() - 2f, rx, ry, backgroundPaint)
        canvas.drawRoundRect(2f, 2f, width.toFloat() - 2f, height.toFloat() - 2f, rx, ry, strokePaint)

        // Draw helper text in center
        canvas.drawText("移动单指控制鼠标", width / 2f, height / 2f - 40f, textPaint)
        canvas.drawText("单击左键 | 双指敲击右键 | 双指滑动滚轮", width / 2f, height / 2f + 10f, textPaint)
        canvas.drawText("💡 未连接时，触摸任意按键或屏幕即可重连", width / 2f, height / 2f + 60f, yellowTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (hidManager == null) return false

        val action = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                downTime = System.currentTimeMillis()
                isMove = false
                isScroll = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    isScroll = true
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1 && !isScroll) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dx = (x - lastX) * sensitivity
                        val dy = (y - lastY) * sensitivity

                        if (abs(x - downX) > moveThreshold || abs(y - downY) > moveThreshold) {
                            isMove = true
                        }

                        if (isMove) {
                            // Send relative movement report
                            // Clamp values to Byte range (-127 to 127)
                            val moveX = clampToByte(dx.roundToInt())
                            val moveY = clampToByte(dy.roundToInt())
                            hidManager?.sendMouseReport(0, moveX, moveY, 0)
                        }

                        lastX = x
                        lastY = y
                    }
                } else if (pointerCount == 2 && isScroll) {
                    val currentScrollY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = currentScrollY - lastScrollY
                    if (abs(dy) > scrollThreshold) {
                        // Reverse standard direction for natural scrolling
                        val wheelDelta = clampToByte(-(dy / scrollSensitivity).roundToInt())
                        hidManager?.sendMouseReport(0, 0, 0, wheelDelta)
                        lastScrollY = currentScrollY
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                val deltaX = abs(event.x - downX)
                val deltaY = abs(event.y - downY)

                if (!isMove && duration < clickDurationThreshold && deltaX < moveThreshold && deltaY < moveThreshold) {
                    // Left click
                    performLeftClick()
                }
                activePointerId = -1
                isMove = false
                isScroll = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // If two fingers tap and release quickly, it's a right click
                val duration = System.currentTimeMillis() - downTime
                if (pointerCount == 2 && duration < clickDurationThreshold && !isMove) {
                    performRightClick()
                }
                isScroll = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                isMove = false
                isScroll = false
            }
        }
        return true
    }

    private fun performLeftClick() {
        Thread {
            // Mouse Left Button down
            hidManager?.sendMouseReport(1, 0, 0, 0)
            try {
                Thread.sleep(15)
            } catch (e: InterruptedException) {
                // ignore
            }
            // Mouse Left Button up
            hidManager?.sendMouseReport(0, 0, 0, 0)
        }.start()
    }

    private fun performRightClick() {
        Thread {
            // Mouse Right Button down
            hidManager?.sendMouseReport(2, 0, 0, 0)
            try {
                Thread.sleep(15)
            } catch (e: InterruptedException) {
                // ignore
            }
            // Mouse Right Button up
            hidManager?.sendMouseReport(0, 0, 0, 0)
        }.start()
    }

    private fun clampToByte(value: Int): Byte {
        return when {
            value > 127 -> 127.toByte()
            value < -127 -> (-127).toByte()
            else -> value.toByte()
        }
    }
}
