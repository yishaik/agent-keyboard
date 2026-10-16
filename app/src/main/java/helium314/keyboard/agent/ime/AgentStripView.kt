package helium314.keyboard.agent.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Conversation panel above the keys: screen-seeing status, transcript, composer, actions.
 * Replaces the one-line suggestion strip while the agent is on.
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
    private val stripBg = colors.get(ColorType.STRIP_BACKGROUND)

    private val sparkle = ImageView(context).apply {
        setImageResource(R.drawable.ic_agent)
        setColorFilter(accent)
        val p = dp(6)
        setPadding(p, p, p, p)
    }
    private val status = TextView(context).apply {
        setTextColor(hintColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    private val transcript = TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(3).toFloat(), 1f)
        gravity = Gravity.TOP or Gravity.START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        val p = dp(8)
        setPadding(p, dp(4), p, dp(4))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(transcript, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val composer = TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        val p = dp(8)
        setPadding(p, 0, p, 0)
    }
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(stripBg)
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sparkle, LayoutParams(dp(32), dp(32)))
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        val composerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(composer, LayoutParams(0, dp(40), 1f))
            addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hairline(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hairline(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(composerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setStatus(value: CharSequence, seeing: Boolean) {
        status.text = value
        status.setTextColor(if (seeing) accent else hintColor)
    }

    fun setTranscript(turns: List<ChatTurn>, youLabel: String, agentLabel: String) {
        val body = SpannableStringBuilder()
        turns.filter { it.text.isNotBlank() }.forEachIndexed { i, turn ->
            if (i > 0) body.append('\n')
            val label = if (turn.fromUser) youLabel else agentLabel
            val start = body.length
            body.append(label).append(": ")
            body.setSpan(StyleSpan(Typeface.BOLD), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.append(turn.text)
        }
        transcript.text = body
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Composer line: typed request, status, or a draft to insert. */
    fun setComposer(value: CharSequence, hint: CharSequence? = null, italic: Boolean = false) {
        if (value.isEmpty() && hint != null) {
            composer.text = hint
            composer.setTextColor(hintColor)
        } else {
            composer.text = value
            composer.setTextColor(textColor)
        }
        composer.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }

    /** Kept for call sites that still mean "the line the user is looking at". */
    fun setText(value: CharSequence, hint: CharSequence? = null, italic: Boolean = false) =
        setComposer(value, hint, italic)

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

    private fun hairline(): View = View(context).apply { setBackgroundColor(hintColor); alpha = 0.35f }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
