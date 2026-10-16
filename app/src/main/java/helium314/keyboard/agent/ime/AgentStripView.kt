package helium314.keyboard.agent.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * What the agent shows in place of the suggestion strip. One line, like the strip:
 * sparkle | text (typed request, status, streamed answer, or draft) | action buttons.
 * Direction follows the text, so Hebrew drafts read right-to-left.
 */
@SuppressLint("ViewConstructor")
class AgentStripView(context: Context) : LinearLayout(context) {

    sealed interface Button { val label: Int; val onClick: () -> Unit }
    data class Action(override val label: Int, val primary: Boolean = false, override val onClick: () -> Unit) : Button
    /** Press and hold (push-to-talk). */
    data class Hold(override val label: Int, val onDown: () -> Unit, val onUp: () -> Unit) : Button {
        override val onClick: () -> Unit = {}
    }

    private val colors = Settings.getValues().mColors
    private val textColor = colors.get(ColorType.KEY_TEXT)
    private val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
    private val hintColor = colors.get(ColorType.SUGGESTION_TYPED_WORD)

    private val sparkle = ImageView(context).apply {
        setImageResource(R.drawable.ic_agent)
        setColorFilter(accent)
        val p = dp(8)
        setPadding(p, p, p, p)
    }
    private val text = TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        ellipsize = null
        gravity = Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(sparkle, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** Show [value]; when blank, show [hint] greyed out. Keeps the end of long text visible. */
    fun setText(value: CharSequence, hint: CharSequence? = null, italic: Boolean = false) {
        if (value.isEmpty() && hint != null) {
            text.text = hint; text.setTextColor(hintColor)
        } else {
            text.text = value; text.setTextColor(textColor)
        }
        text.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        text.ellipsize = if (italic) TextUtils.TruncateAt.END else null
        val rtl = value.firstOrNull { Character.getDirectionality(it).let { d ->
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT } }
            ?.let { Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT } ?: false
        scroll.post { scroll.fullScroll(if (rtl) View.FOCUS_LEFT else View.FOCUS_RIGHT) }
    }

    /** Sparkle pulses with mic level / activity (0..1). */
    fun setActivity(level: Float) {
        val s = 1f + level.coerceIn(0f, 1f) * 0.35f
        sparkle.scaleX = s; sparkle.scaleY = s
        sparkle.alpha = 0.6f + level.coerceIn(0f, 1f) * 0.4f
    }

    fun setButtons(vararg b: Button) {
        buttons.removeAllViews()
        b.forEach { buttons.addView(makeButton(it)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeButton(b: Button): View = TextView(context).apply {
        setText(b.label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val h = dp(12); setPadding(h, 0, h, 0)
        val primary = b is Action && b.primary || b is Hold
        setTextColor(if (primary) colors.get(ColorType.ACTION_KEY_ICON) else textColor)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (primary) setColor(accent) else setStroke(dp(1), hintColor)
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(4); marginEnd = dp(4) }
        when (b) {
            is Action -> setOnClickListener { b.onClick() }
            is Hold -> setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; b.onDown() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; b.onUp() }
                }
                true
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
