package com.dotnative.plugins

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*

/** The wheel is drawn in logical pixels, with the same projection on both platforms. */
internal class HoloWheel(context: Context) : View(context) {
    var changed: ((Int) -> Unit)? = null
    private var lower = 1
    private var upper = 31
    private var looping = true
    private var year = false
    private var fontSize = 16f
    private var typeface = Typeface.DEFAULT
    private var ink = Color.BLACK
    private var offset = 0f
    private var reported = 1
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cache = mutableMapOf<Int, Bitmap>()
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var downY = 0f
    private var animator: ValueAnimator? = null
    private val count
        get() = upper - lower + 1

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun configure(
        minimum: Int,
        maximum: Int,
        selected: Int,
        loop: Boolean,
        isYear: Boolean,
        face: Typeface,
        size: Float,
        color: Int,
    ) {
        animator?.cancel()
        cache.values.forEach { it.recycle() }
        cache.clear()
        lower = minimum
        upper = maximum
        reported = selected
        looping = loop
        year = isYear
        typeface = face
        fontSize = size
        ink = color
        offset = (selected - lower).toFloat()
        invalidate()
    }

    private fun value(index: Int) = lower + ((index % count) + count) % count

    private fun update(next: Float) {
        offset = if (looping) next else next.coerceIn(0f, (count - 1).toFloat())
        val selected = value(offset.roundToInt())
        if (selected != reported) {
            reported = selected
            changed?.invoke(selected)
        }
        contentDescription = reported.toString()
        invalidate()
    }

    private fun textBitmap(value: Int): Bitmap =
        cache.getOrPut(value) {
            val text =
                if (year) value.toString() else String.format(java.util.Locale.ROOT, "%02d", value)
            paint.typeface = typeface
            paint.textSize = fontSize * density
            val width = max(1, ceil(paint.measureText(text) + 4 * density).toInt())
            val bitmap =
                Bitmap.createBitmap(width, ceil(36 * density).toInt(), Bitmap.Config.ARGB_8888)
            paint.color = ink
            paint.alpha = Color.alpha(ink)
            val metrics = paint.fontMetrics
            Canvas(bitmap)
                .drawText(
                    text,
                    2 * density,
                    bitmap.height / 2f - (metrics.ascent + metrics.descent) / 2f,
                    paint,
                )
            bitmap
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width / density
        canvas.save()
        canvas.scale(density, density)
        val centerX = width / 2f
        for (index in floor(offset).toInt() - 3..floor(offset).toInt() + 3) {
            if (!looping && index !in 0 until count) continue
            val delta = (index - offset) * 36f
            val angle = -delta / 124 * 2 * asin(1.0 / 1.5) / 0.95
            if (abs(angle) > Math.PI / 2) continue
            val bitmap = textBitmap(value(index))
            val textWidth = bitmap.width / density
            val source =
                floatArrayOf(
                    0f,
                    0f,
                    bitmap.width.toFloat(),
                    0f,
                    bitmap.width.toFloat(),
                    bitmap.height.toFloat(),
                    0f,
                    bitmap.height.toFloat(),
                )
            val destination = FloatArray(8)
            for (corner in 0..3) {
                val x = if (corner == 0 || corner == 3) -textWidth / 2 else textWidth / 2
                val y = if (corner < 2) -18.0 else 18.0
                val w = 1 + 0.003 * 93 * (1 - cos(angle)) - 0.003 * sin(angle) * y
                destination[corner * 2] = centerX + (x / w).toFloat()
                destination[corner * 2 + 1] =
                    80 + ((cos(angle) * y - 93 * sin(angle)) / w).toFloat()
            }
            val matrix = Matrix().apply { setPolyToPoly(source, 0, destination, 0, 4) }
            paint.alpha = (255 * 0.447).roundToInt()
            for ((top, bottom) in listOf(18f to 62f, 98f to 142f)) {
                canvas.save()
                canvas.clipRect(7f, top, width - 7, bottom)
                canvas.drawBitmap(bitmap, matrix, paint)
                canvas.restore()
            }
            paint.alpha = 255
            canvas.save()
            canvas.clipRect(7f, 62f, width - 7, 98f)
            val flat =
                Matrix().apply {
                    setScale(1 / density, 1 / density)
                    postTranslate(centerX - textWidth / 2, 62 + delta)
                }
            canvas.drawBitmap(bitmap, flat, paint)
            canvas.restore()
        }
        paint.color = ink
        paint.alpha = Color.alpha(ink)
        val spacing = resources.displayMetrics.widthPixels / density * 0.02f
        canvas.drawRect(spacing, 61.5f, width - spacing, 63.5f, paint)
        canvas.drawRect(spacing, 97.5f, width - spacing, 99.5f, paint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                animator?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                lastY = event.y
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                update(offset + (lastY - event.y) / density / 36)
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = -(tracker?.yVelocity ?: 0f) / density
                if (abs(event.y - downY) < 4 * density) {
                    val target = offset.roundToInt() + ((event.y / density - 80) / 36).roundToInt()
                    animateTo(target.toFloat(), 300)
                    performClick()
                } else settle(velocity)
                tracker?.recycle()
                tracker = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                settle(0f)
                tracker?.recycle()
                tracker = null
                return true
            }
        }
        tracker?.addMovement(event)
        return true
    }

    private fun settle(velocity: Float) {
        val start = offset
        var target = (start + velocity / 36 / -ln(0.135)).roundToInt().toFloat()
        if (!looping) target = target.coerceIn(0f, (count - 1).toFloat())
        val distance = (target - start) * 36
        if (abs(distance) < 0.001f) {
            update(target)
            return
        }
        if (abs(velocity) < 20 || distance * velocity <= 0) {
            animateTo(target, 300)
            return
        }
        val endVelocity = 20 / resources.displayMetrics.density
        val drag = (endVelocity - abs(velocity)) / abs(distance)
        val duration =
            if (drag < 0)
                (ln(endVelocity / abs(velocity)) / drag * 1000).toLong().coerceIn(100, 3000)
            else 300L
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val t = (it.animatedValue as Float) * duration / 1000
                    val progress =
                        if (drag < 0) (1 - exp(drag * t)) / (1 - exp(drag * duration / 1000))
                        else it.animatedValue as Float
                    update(start + (target - start) * progress)
                }
                start()
            }
    }

    private fun animateTo(target: Float, duration: Long) {
        val end = if (looping) target else target.coerceIn(0f, (count - 1).toFloat())
        animator =
            ValueAnimator.ofFloat(offset, end).apply {
                this.duration = duration
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { update(it.animatedValue as Float) }
                start()
            }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.isScrollable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (
            action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) {
            animateTo(
                offset.roundToInt() +
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1f else -1f,
                300,
            )
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        tracker?.recycle()
        tracker = null
        cache.values.forEach { it.recycle() }
        cache.clear()
        super.onDetachedFromWindow()
    }
}
