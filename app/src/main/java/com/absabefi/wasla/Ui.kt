package com.absabefi.wasla

import android.content.Context
import android.graphics.drawable.GradientDrawable

const val GREEN = 0xFF1FAA59.toInt()
const val DARK = 0xFF075E54.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 14): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}
