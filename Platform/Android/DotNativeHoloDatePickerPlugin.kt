package com.dotnative.plugins

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.*

private var activeHoloDatePicker: Dialog? = null
private val holoFonts = mutableMapOf<String, Typeface>()
private val holoFontBuffers =
    mutableMapOf<String, Triple<java.io.ByteArrayOutputStream, Int, Int>>()

fun DotNativeHoloDatePickerPlugin(activity: Activity) {
    val channel = NativeChannels.channel("dotnative.holodatepicker")
    channel.handle("fontChunk") { args, reply ->
        val map = args as? Map<*, *>
        val id = map?.get("id") as? String
        val index = (map?.get("index") as? Number)?.toInt()
        val total = (map?.get("total") as? Number)?.toInt()
        val data = map?.get("data") as? ByteArray
        if (
            id == null ||
                id.length != 64 ||
                index == null ||
                total == null ||
                total !in 1..16 ||
                index !in 0 until total ||
                data == null ||
                data.size > 512 * 1024
        ) {
            reply.failure("invalid_font", "Invalid font chunk")
        } else {
            if (index == 0) holoFontBuffers[id] = Triple(java.io.ByteArrayOutputStream(), 0, total)
            val buffer = holoFontBuffers[id]
            if (buffer == null || buffer.second != index || buffer.third != total)
                reply.failure("invalid_font", "Font chunks must arrive in order")
            else {
                buffer.first.write(data)
                if (index + 1 == total) {
                    holoFontBuffers.remove(id)
                    val bytes = buffer.first.toByteArray()
                    val hash =
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                    if (hash != id) reply.failure("invalid_font", "Font data is invalid")
                    else {
                        val file = File.createTempFile("holo-font-", ".ttf", activity.cacheDir)
                        try {
                            file.writeBytes(bytes)
                            holoFonts[id] = Typeface.createFromFile(file)
                            reply.success(null)
                        } catch (_: Exception) {
                            reply.failure("invalid_font", "Font data is invalid")
                        } finally {
                            file.delete()
                        }
                    }
                } else {
                    holoFontBuffers[id] = Triple(buffer.first, index + 1, total)
                    reply.success(null)
                }
            }
        }
    }
    channel.handle("show") { args, reply ->
        val map = args as? Map<*, *>
        fun date(key: String): Calendar? {
            val parts = (map?.get(key) as? String)?.split("-") ?: return null
            if (parts.size != 3) return null
            return try {
                GregorianCalendar().apply {
                    isLenient = false
                    clear()
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    timeInMillis
                }
            } catch (_: Exception) {
                null
            }
        }
        val minimum = date("minimumDate")
        val maximum = date("maximumDate")
        val initial = date("initialDate")
        val title = map?.get("title") as? String
        val confirm = map?.get("confirmText") as? String
        val cancel = map?.get("cancelText") as? String
        if (
            minimum == null ||
                maximum == null ||
                initial == null ||
                title == null ||
                confirm == null ||
                cancel == null ||
                initial.before(minimum) ||
                initial.after(maximum)
        ) {
            reply.failure("invalid_arguments", "Expected valid date picker options")
        } else if (activeHoloDatePicker != null || activity.isFinishing || activity.isDestroyed) {
            reply.failure(
                "picker_unavailable",
                "A picker is already open or the activity is unavailable",
            )
        } else {
            var finished = false
            fun finish(value: String?) {
                if (!finished) {
                    finished = true
                    activeHoloDatePicker = null
                    reply.success(value)
                }
            }
            val density = activity.resources.displayMetrics.density
            fun dp(value: Float) = (value * density).roundToInt()
            fun color(key: String, fallback: Int) = (map?.get(key) as? Number)?.toInt() ?: fallback
            fun size(key: String, fallback: Float) =
                (map?.get(key) as? Number)?.toFloat() ?: fallback
            fun font(key: String, fallback: Typeface): Typeface =
                holoFonts[map?.get(key + "Id") as? String] ?: fallback
            val face = font("fontData", Typeface.DEFAULT)
            val titleFace = font("titleFontData", Typeface.DEFAULT_BOLD)
            val actionFace =
                font("actionFontData", Typeface.create("sans-serif-medium", Typeface.NORMAL))
            val textColor = color("textColor", Color.BLACK)
            val itemColor = color("itemColor", textColor)
            val looping = map?.get("looping") as? Boolean ?: true
            val fontSize = size("fontSize", 16f)
            val titleSize = size("titleFontSize", 24f)
            val actionSize = size("actionFontSize", 14f)
            val day = HoloWheel(activity)
            val month = HoloWheel(activity)
            val year = HoloWheel(activity)
            var selectedYear = initial.get(Calendar.YEAR)
            var selectedMonth = initial.get(Calendar.MONTH) + 1
            var selectedDay = initial.get(Calendar.DAY_OF_MONTH)
            fun updateRanges() {
                val firstMonth =
                    if (selectedYear == minimum.get(Calendar.YEAR)) minimum.get(Calendar.MONTH) + 1
                    else 1
                val lastMonth =
                    if (selectedYear == maximum.get(Calendar.YEAR)) maximum.get(Calendar.MONTH) + 1
                    else 12
                selectedMonth = selectedMonth.coerceIn(firstMonth, lastMonth)
                month.configure(
                    firstMonth,
                    lastMonth,
                    selectedMonth,
                    looping,
                    false,
                    face,
                    fontSize,
                    itemColor,
                )
                val date = GregorianCalendar(selectedYear, selectedMonth - 1, 1)
                val firstDay =
                    if (
                        selectedYear == minimum.get(Calendar.YEAR) &&
                            selectedMonth == minimum.get(Calendar.MONTH) + 1
                    )
                        minimum.get(Calendar.DAY_OF_MONTH)
                    else 1
                val lastDay =
                    if (
                        selectedYear == maximum.get(Calendar.YEAR) &&
                            selectedMonth == maximum.get(Calendar.MONTH) + 1
                    )
                        maximum.get(Calendar.DAY_OF_MONTH)
                    else date.getActualMaximum(Calendar.DAY_OF_MONTH)
                selectedDay = selectedDay.coerceIn(firstDay, lastDay)
                day.configure(
                    firstDay,
                    lastDay,
                    selectedDay,
                    looping,
                    false,
                    face,
                    fontSize,
                    itemColor,
                )
            }
            year.configure(
                minimum.get(Calendar.YEAR),
                maximum.get(Calendar.YEAR),
                selectedYear,
                looping,
                true,
                face,
                fontSize,
                itemColor,
            )
            updateRanges()
            year.changed = {
                selectedYear = it
                updateRanges()
            }
            month.changed = {
                selectedMonth = it
                val date = GregorianCalendar(selectedYear, selectedMonth - 1, 1)
                val first =
                    if (
                        selectedYear == minimum.get(Calendar.YEAR) &&
                            selectedMonth == minimum.get(Calendar.MONTH) + 1
                    )
                        minimum.get(Calendar.DAY_OF_MONTH)
                    else 1
                val last =
                    if (
                        selectedYear == maximum.get(Calendar.YEAR) &&
                            selectedMonth == maximum.get(Calendar.MONTH) + 1
                    )
                        maximum.get(Calendar.DAY_OF_MONTH)
                    else date.getActualMaximum(Calendar.DAY_OF_MONTH)
                selectedDay = selectedDay.coerceIn(first, last)
                day.configure(first, last, selectedDay, looping, false, face, fontSize, itemColor)
            }
            day.changed = { selectedDay = it }
            day.contentDescription = "Day"
            month.contentDescription = "Month"
            year.contentDescription = "Year"
            val panelWidth = min(328f, activity.resources.displayMetrics.widthPixels / density - 80)
            val titleHeight = ceil(titleSize * 4 / 3)
            val panelHeight = 24 + titleHeight + 160 + 72
            val panel =
                FrameLayout(activity).apply {
                    background =
                        GradientDrawable().apply {
                            setColor(color("backgroundColor", Color.rgb(246, 246, 246)))
                            cornerRadius = dp(28f).toFloat()
                        }
                    clipToOutline = true
                }
            fun label(value: String, face: Typeface, size: Float) =
                TextView(activity).apply {
                    text = value
                    typeface = face
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
                    setTextColor(textColor)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }
            panel.addView(
                label(title, titleFace, titleSize),
                FrameLayout.LayoutParams(dp(panelWidth - 48), dp(titleHeight)).apply {
                    leftMargin = dp(24f)
                    topMargin = dp(24f)
                },
            )
            val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(day, month, year).forEach {
                columns.addView(it, LinearLayout.LayoutParams(0, dp(160f), 1f))
            }
            panel.addView(
                columns,
                FrameLayout.LayoutParams(dp(panelWidth - 28), dp(160f)).apply {
                    leftMargin = dp(14f)
                    topMargin = dp(24 + titleHeight)
                },
            )
            val confirmButton =
                label(confirm, actionFace, actionSize).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                }
            val cancelButton =
                label(cancel, actionFace, actionSize).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                }
            val confirmWidth = max(64f, confirmButton.paint.measureText(confirm) / density + 24)
            val cancelWidth = max(64f, cancelButton.paint.measureText(cancel) / density + 24)
            panel.addView(
                confirmButton,
                FrameLayout.LayoutParams(dp(confirmWidth), dp(48f)).apply {
                    leftMargin = dp(panelWidth - 24 - cancelWidth - 8 - confirmWidth)
                    topMargin = dp(panelHeight - 72)
                },
            )
            panel.addView(
                cancelButton,
                FrameLayout.LayoutParams(dp(cancelWidth), dp(48f)).apply {
                    leftMargin = dp(panelWidth - 24 - cancelWidth)
                    topMargin = dp(panelHeight - 72)
                },
            )
            val dialog =
                Dialog(activity).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setContentView(panel)
                    setCanceledOnTouchOutside(true)
                }
            confirmButton.setOnClickListener {
                finish(
                    String.format(
                        java.util.Locale.ROOT,
                        "%04d-%02d-%02d",
                        selectedYear,
                        selectedMonth,
                        selectedDay,
                    )
                )
                dialog.dismiss()
            }
            cancelButton.setOnClickListener {
                finish(null)
                dialog.dismiss()
            }
            dialog.setOnCancelListener { finish(null) }
            dialog.setOnDismissListener { finish(null) }
            activeHoloDatePicker = dialog
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(dp(panelWidth), dp(panelHeight))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes =
                    attributes.apply {
                        dimAmount = 0.54f
                        windowAnimations = 0
                    }
                decorView.alpha = 0f
                decorView
                    .animate()
                    .alpha(1f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }
    channel.handle("cancel") { _, reply ->
        activeHoloDatePicker?.dismiss()
        reply.success(null)
    }
}
