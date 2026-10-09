package com.dotnative.plugins

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import java.util.Calendar
import java.util.GregorianCalendar

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
            val dialog =
                DatePickerDialog(
                    activity,
                    android.R.style.Theme_Holo_Light_Dialog,
                    { _, year, month, day ->
                        finish(
                            String.format(
                                java.util.Locale.ROOT,
                                "%04d-%02d-%02d",
                                year,
                                month + 1,
                                day,
                            )
                        )
                    },
                    initial.get(Calendar.YEAR),
                    initial.get(Calendar.MONTH),
                    initial.get(Calendar.DAY_OF_MONTH),
                )
            dialog.datePicker.calendarViewShown = false
            dialog.datePicker.spinnersShown = true
            dialog.setTitle(title)
            dialog.datePicker.minDate = minimum.timeInMillis
            dialog.datePicker.maxDate = maximum.timeInMillis
            dialog.setButton(Dialog.BUTTON_POSITIVE, confirm, dialog)
            dialog.setButton(Dialog.BUTTON_NEGATIVE, cancel) { _, _ -> finish(null) }
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
