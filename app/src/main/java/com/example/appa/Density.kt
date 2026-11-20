package com.example.appa

import android.content.Context
import android.util.TypedValue
import android.view.View

/** Density helpers — the only place raw dp/sp conversion happens. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpF(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
