package com.personal.circlesearch

import android.content.Context
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpInt(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun WindowManager.screenBounds(): Rect = currentWindowMetrics.bounds

fun WindowManager.systemBarInsets(): android.graphics.Insets =
    currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
