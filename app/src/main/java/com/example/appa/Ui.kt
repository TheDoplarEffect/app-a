package com.example.appa

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small UI helpers shared by the screens. All layout is programmatic (no XML/R). */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Rounded-corner background drawable (density-aware). */
fun Context.pillBg(color: Int, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

object Ui {
    const val ACCENT = 0xFF1F6FEB.toInt()
    const val TEXT = 0xFF111111.toInt()
    const val DIM = 0xFF666666.toInt()
    const val GREEN = 0xFF1A7F37.toInt()
    const val AMBER = 0xFF9A6700.toInt()
    const val BLUE = 0xFF0969DA.toInt()
    const val RED = 0xFFCF222E.toInt()
    const val CARD_BG = 0xFFF6F8FA.toInt()
    const val BANNER_BG = 0xFFFFF3CD.toInt()
    const val BANNER_TEXT = 0xFF7A5B00.toInt()

    fun textView(context: Context, text: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    fun padded(container: ViewGroup, padDp: Int) {
        val p = container.context.dp(padDp)
        container.setPadding(p, p, p, p)
    }

    fun hRow(context: Context): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    fun vRow(context: Context): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun divider(context: Context): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1))
            setBackgroundColor(0xFFD0D7DE.toInt())
        }

    fun pill(context: Context, text: String, bg: Int): TextView =
        textView(context, text, 11f, Color.WHITE, bold = true).apply {
            background = GradientDrawable().apply {
                cornerRadius = context.dp(12).toFloat()
                setColor(bg)
            }
            val px = context.dp(8); val py = context.dp(3)
            setPadding(px, py, px, py)
        }

    fun statusColor(status: RequestStatus?): Int? = when (status) {
        RequestStatus.AVAILABLE -> GREEN
        RequestStatus.PENDING -> AMBER
        RequestStatus.PROCESSING -> BLUE
        null -> null
    }

    fun statusLabel(media: Media, status: RequestStatus?): String? {
        if (media.available) return "Available"
        return when (status) {
            RequestStatus.PENDING -> "Pending"
            RequestStatus.PROCESSING -> "Processing"
            RequestStatus.AVAILABLE -> "Available"
            null -> null
        }
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
