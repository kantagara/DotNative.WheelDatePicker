package com.dotnative.plugins

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

private var activeWheelDatePicker: Dialog? = null

fun DotNativeWheelDatePickerPlugin(activity: Activity) {
    val channel = NativeChannels.channel("dotnative.wheeldatepicker")
    channel.onReset = { activeWheelDatePicker?.dismiss() }
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
        val localeTag = map?.get("locale") as? String
        val locale =
            try {
                localeTag?.let { Locale.Builder().setLanguageTag(it).build() }
            } catch (_: java.util.IllformedLocaleException) {
                reply.failure("invalid_arguments", "Expected a valid locale tag")
                return@handle
            }
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
        } else if (activeWheelDatePicker != null || activity.isFinishing || activity.isDestroyed) {
            reply.failure(
                "picker_unavailable",
                "A picker is already open or the activity is unavailable",
            )
        } else {
            var finished = false
            fun finish(value: String?) {
                if (!finished) {
                    finished = true
                    activeWheelDatePicker = null
                    reply.success(value)
                }
            }
            val context = ContextThemeWrapper(activity, android.R.style.Theme_Holo_Light_Dialog)
            if (locale != null) {
                context.applyOverrideConfiguration(
                    Configuration(activity.resources.configuration).apply { setLocale(locale) },
                )
            }
            val pickerLocale = locale ?: context.resources.configuration.locales[0]
            val wheels =
                LocalizedDateWheels(
                    context,
                    minimum,
                    maximum,
                    initial,
                    pickerLocale,
                    map?.get("looping") as? Boolean ?: true,
                )
            val dialog =
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(wheels)
                    .setPositiveButton(confirm) { _, _ -> finish(wheels.date()) }
                    .setNegativeButton(cancel) { _, _ -> finish(null) }
                    .create()
            dialog.setOnCancelListener { finish(null) }
            dialog.setOnDismissListener { finish(null) }
            reply.onCancel = { dialog.dismiss() }
            activeWheelDatePicker = dialog
            dialog.show()
        }
    }
    channel.handle("cancel") { _, reply ->
        activeWheelDatePicker?.dismiss()
        reply.success(null)
    }
}

// NumberPicker supplies native touch, fling, accessibility and row snapping.
// The legacy DatePickerDialog spinner reads the process-wide default locale;
// composing its native wheel controls avoids changing the rest of the app.
private class LocalizedDateWheels(
    context: Context,
    private val minimum: Calendar,
    private val maximum: Calendar,
    initial: Calendar,
    private val locale: Locale,
    private val looping: Boolean,
) : LinearLayout(context) {
    private var year = initial.get(Calendar.YEAR)
    private var month = initial.get(Calendar.MONTH) + 1
    private var day = initial.get(Calendar.DAY_OF_MONTH)
    private var updating = false
    private val days = NumberPicker(context)
    private val months = NumberPicker(context)
    private val years = NumberPicker(context)
    private val monthNames = DateFormatSymbols(locale).shortMonths

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        for (part in DateFormat.getDateFormatOrder(context)) {
            val picker =
                when (part) {
                    'd' -> days
                    'M' -> months
                    else -> years
                }
            picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            picker.setFormatter { value ->
                String.format(locale, if (part == 'y') "%04d" else "%02d", value)
            }
            addView(picker, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        days.setOnValueChangedListener { _, _, value ->
            if (!updating) {
                day = value
                refresh()
            }
        }
        months.setOnValueChangedListener { _, _, value ->
            if (!updating) {
                month = value
                refresh()
            }
        }
        years.setOnValueChangedListener { _, _, value ->
            if (!updating) {
                year = value
                refresh()
            }
        }
        refresh()
    }

    private fun refresh() {
        updating = true
        try {
            val firstMonth =
                if (year == minimum.get(Calendar.YEAR)) minimum.get(Calendar.MONTH) + 1 else 1
            val lastMonth =
                if (year == maximum.get(Calendar.YEAR)) maximum.get(Calendar.MONTH) + 1 else 12
            month = month.coerceIn(firstMonth, lastMonth)
            val calendar =
                GregorianCalendar().apply {
                    clear()
                    set(year, month - 1, 1)
                }
            val firstDay =
                if (year == minimum.get(Calendar.YEAR) && month == minimum.get(Calendar.MONTH) + 1)
                    minimum.get(Calendar.DAY_OF_MONTH)
                else 1
            val lastDay =
                if (year == maximum.get(Calendar.YEAR) && month == maximum.get(Calendar.MONTH) + 1)
                    maximum.get(Calendar.DAY_OF_MONTH)
                else calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            day = day.coerceIn(firstDay, lastDay)
            update(years, minimum.get(Calendar.YEAR), maximum.get(Calendar.YEAR), year)
            update(
                months,
                firstMonth,
                lastMonth,
                month,
                (firstMonth..lastMonth).map { monthNames[it - 1] }.toTypedArray(),
            )
            update(days, firstDay, lastDay, day)
        } finally {
            updating = false
        }
    }

    private fun update(
        picker: NumberPicker,
        first: Int,
        last: Int,
        value: Int,
        labels: Array<String>? = null,
    ) {
        if (
            picker.minValue != first ||
                picker.maxValue != last ||
                (labels != null && !labels.contentEquals(picker.displayedValues))
        ) {
            picker.displayedValues = null
            picker.minValue = 0
            picker.maxValue = last
            picker.minValue = first
            picker.displayedValues = labels
        }
        if (picker.value != value) picker.value = value
        if (picker.wrapSelectorWheel != looping) picker.wrapSelectorWheel = looping
    }

    fun date(): String = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
}
