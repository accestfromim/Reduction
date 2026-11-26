package com.mindtravel.reduction.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.feature.chat.data.CardRepository
import kotlin.math.abs

/**
 * One card, floating over whatever else is on the screen, so it can be read without
 * opening the app. It rests as a small pill with the card's name; a tap opens the text,
 * another closes it; it can be dragged anywhere; "×" puts it away. It follows the card:
 * when the card changes, so does what floats.
 *
 * Android lets an app draw over others only after the user has switched that on in the
 * system settings; until then [pin] remembers the card and shows nothing. While the app
 * itself is in front, the card is hidden — the app shows it better.
 */
class CardOverlay(private val context: Context, private val cards: CardRepository) {

    private val main = Handler(Looper.getMainLooper())
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var generation = 0
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("card_overlay", Context.MODE_PRIVATE)

    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var body: TextView? = null
    private var scroll: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var open = false
    private var appInFront = false

    /** The card that floats, or blank. */
    fun pinned(): String = prefs.getString(KEY_CARD, "").orEmpty()

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    /** Floats [name]. Returns false when the system has not been told to allow it yet. */
    fun pin(name: String): Boolean {
        prefs.edit().putString(KEY_CARD, name.trim()).apply()
        main.post { sync() }
        return allowed()
    }

    fun unpin() {
        prefs.edit().remove(KEY_CARD).apply()
        main.post { sync() }
    }

    /** The app came to the front or left it. */
    fun setAppInFront(front: Boolean) {
        main.post {
            appInFront = front
            sync()
        }
    }

    /** A card was written or deleted. */
    fun onCardChanged(name: String) {
        if (name == pinned()) main.post { sync() }
    }

    /** Brings what is on screen in line with what should be. Main thread only. */
    private fun sync() {
        val name = pinned()
        val attempt = ++generation
        if (name.isEmpty() || appInFront || !allowed()) {
            hide()
            return
        }
        if (!open) { display(name, ""); return }
        reader.execute {
            val text = runCatching { cards.read(name) }.getOrElse { "未能读取最新卡片：${it.message}" }
            main.post { if (generation == attempt) { if (text == null) hide() else display(name, text) } }
        }
    }

    private fun display(name: String, text: String) {
        try {
            if (root == null) show()
            title?.text = name
            body?.text = text.trim().ifEmpty { "（空）" }
            scroll?.visibility = if (open) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            Logs.add("Card overlay failed: ${t.message}")
            hide()
        }
    }

    private fun hide() {
        val view = root ?: return
        runCatching { windows.removeView(view) }
        root = null
        title = null
        body = null
        scroll = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val screen = context.resources.displayMetrics
        val width = (screen.widthPixels * 0.82f).toInt()

        val name = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8), dp(5), dp(14), dp(7))
            setOnClickListener { unpin() }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name)
            addView(close)
        }
        val text = TextView(context).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(14), 0, dp(14), dp(12))
        }
        val maxBody = (screen.heightPixels * 0.45f).toInt()
        val scroller = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.AT_MOST))
            }
        }.apply { addView(text) }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 16, 16, 16))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
            addView(head)
            addView(scroller)
        }

        val layout = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (screen.widthPixels - width) / 2)
            y = prefs.getInt(KEY_Y, dp(96))
        }

        // The head is both the handle and the switch: a drag moves the card, a tap opens or closes it.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        name.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = layout.x; startY = layout.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        layout.x = (startX + dx).toInt().coerceIn(-width / 2, screen.widthPixels - width / 2)
                        layout.y = (startY + dy).toInt().coerceIn(0, screen.heightPixels - dp(60))
                        runCatching { windows.updateViewLayout(view, layout) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs.edit().putInt(KEY_X, layout.x).putInt(KEY_Y, layout.y).apply()
                    } else {
                        open = !open
                        sync()
                    }
                }
            }
            true
        }

        windows.addView(view, layout)
        root = view
        title = name
        body = text
        scroll = scroller
        params = layout
        Logs.add("Card overlay shown")
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_CARD = "card"
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
