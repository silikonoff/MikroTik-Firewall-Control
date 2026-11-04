package ru.nikmadz.mikrotikcontrol

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView

internal object UiKit {
    const val BG = 0xFFF1F5FA.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val BORDER = 0xFFCCD6E4.toInt()
    const val WHITE = 0xFF172C49.toInt() // Semantic primary text, retained name for existing screens.
    const val MUTED = 0xFF52637A.toInt()
    const val BLUE = 0xFF1B4093.toInt()
    const val RED = 0xFFB52E44.toInt()
    const val GREEN = 0xFF08764F.toInt()
    const val YELLOW = 0xFF875500.toInt()
    const val PALE_GREEN = 0xFFE5F3ED.toInt()
    const val PALE_RED = 0xFFFBECEF.toInt()
    const val PALE_YELLOW = 0xFFFFF3DB.toInt()

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    fun bg(color: Int, radius: Float = 14f, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != 0) setStroke(2, stroke)
        }

    fun Activity.vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun Activity.label(text: String, size: Float, color: Int = WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun Activity.button(text: String, color: Int = BLUE): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        val foreground = if (color == CARD || color == BORDER) BLUE else Color.WHITE
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, foreground)))
        val radius = dp(14).toFloat()
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), bg(BORDER, radius))
            addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(0x26000000), bg(color, radius), null))
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(56)
    }

    fun Activity.brandHeader(subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this@brandHeader).apply {
            setImageResource(R.drawable.ic_firewall)
            contentDescription = "MikroTik Firewall Control"
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(logo, LinearLayout.LayoutParams(dp(80), dp(88)))
        val copy = vertical().apply {
            setPadding(dp(14), 0, 0, 0)
            addView(label("MIKROTIK", 22f, BLUE, true))
            columnWithMargin(this, label("MFC · Firewall Control", 13f, MUTED), 4)
            columnWithMargin(this, label(subtitle, 13f, MUTED), 4)
        }
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun Activity.input(hint: String, text: String = ""): EditText = EditText(this).apply {
        this.hint = hint
        this.setText(text)
        textSize = 16f
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        setSingleLine(true)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = bg(CARD, dp(10).toFloat(), BORDER)
    }

    fun rowParams(weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    fun Activity.space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    fun Activity.columnWithMargin(parent: LinearLayout, child: View, top: Int = 8, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        })
    }
}
