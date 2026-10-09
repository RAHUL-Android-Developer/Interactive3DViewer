package com.example.interactive3dviewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The draggable / resizable container of ONE model.
 *
 * It is a transparent Android view laid over the shared SurfaceView. Its logical rectangle (panelX/Y/W/H) is the
 * single source of truth: the Filament viewport is derived from it, so the 3D never lags a layout pass behind.
 *
 *   Gesture table (the two modes never mix):
 *   normal mode                          interaction mode
 *   1-finger drag move container         rotate model
 *   2-finger pinch resize container      zoom model
 */


@SuppressLint("ViewConstructor")
class ModelPanel(
    context: Context,
    private val stage: StageView,
    val model: ModelScene,
    title: String,
) : FrameLayout(context) {

    var panelX = 0f; private set
    var panelY = 0f; private set
    var panelW = 0f; private set
    var panelH = 0f; private set

    var interactionMode = false; private set
    var labelsVisible = false; private set

    private val anchors = FloatArray(model.labels.size * 3)

    // ---- paints (allocated once)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f); color = 0x66FFFFFF }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f); color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCA28.toInt() }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }

    private val loadingText = TextView(context).apply {
        text = "Loading…"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    }

    private lateinit var interactionBtn: ImageButton
    private lateinit var labelBtn: ImageButton

    // ---- gesture state
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (interactionMode) model.zoomBy(d.scaleFactor) else resizeBy(d.scaleFactor)
            stage.requestRender()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        setWillNotDraw(false)
        addView(loadingText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(buildBar(title), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        model.onReady = { loadingText.visibility = GONE; stage.requestRender() }
        if (model.loaded) loadingText.visibility = GONE
    }

    private fun buildBar(title: String): LinearLayout {
        val pad = context.dp(4f).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad, pad)
            setBackgroundColor(0x55000000)
            addView(TextView(context).apply {
                text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            // 1. interaction toggle
            interactionBtn = iconButton(R.drawable.ic_rotate, "Interaction mode") {
                interactionMode = !interactionMode
                interactionBtn.isSelected = interactionMode
                dragging = false
                this@ModelPanel.invalidate()
            }
            // 2. label toggle (hidden on load)
            labelBtn = iconButton(R.drawable.ic_label, "Toggle labels") {
                labelsVisible = !labelsVisible
                labelBtn.isSelected = labelsVisible
                this@ModelPanel.invalidate()
                stage.requestRender()          // labels are projected during the render step
            }
            // 3. close
            val closeBtn = iconButton(R.drawable.ic_close, "Close") { stage.removePanel(this@ModelPanel) }

            addView(interactionBtn); addView(labelBtn); addView(closeBtn)
        }
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit): ImageButton {
        val size = context.dp(34f).toInt()
        val p = context.dp(7f).toInt()
        return ImageButton(context).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.btn_bg)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(p, p, p, p)
            contentDescription = desc
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = context.dp(4f).toInt() }
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------ geometry

    /** Moves/resizes the container, clamped to the stage (= the full screen). */
    fun setRect(x: Float, y: Float, w: Float, h: Float) {
        val sw = stage.width.toFloat()
        val sh = stage.height.toFloat()
        panelW = w; panelH = h
        panelX = if (sw > 0f) x.coerceIn(0f, max(0f, sw - w)) else x
        panelY = if (sh > 0f) y.coerceIn(0f, max(0f, sh - h)) else y
        translationX = panelX
        translationY = panelY
        val lp = layoutParams
        if (lp != null && (lp.width != w.roundToInt() || lp.height != h.roundToInt())) {
            lp.width = w.roundToInt(); lp.height = h.roundToInt()
            layoutParams = lp
        }
    }

    private fun moveBy(dx: Float, dy: Float) = setRect(panelX + dx, panelY + dy, panelW, panelH)

    private fun resizeBy(factor: Float) {
        val minSide = context.dp(150f)
        val minF = minSide / min(panelW, panelH)
        val maxF = min(stage.width / panelW, stage.height / panelH)
        val f = factor.coerceIn(minF.coerceAtMost(1f), max(maxF, minF.coerceAtMost(1f)))
        val nw = panelW * f
        val nh = panelH * f
        setRect(panelX + (panelW - nw) / 2f, panelY + (panelH - nh) / 2f, nw, nh)   // resize around the centre
    }

    /** Called by the stage right before rendering: updates the Filament viewport and projects labels. */
    fun prepareForRender(sx: Float, sy: Float, surfaceH: Int) {
        val w = (panelW * sx).roundToInt().coerceAtLeast(1)
        val h = (panelH * sy).roundToInt().coerceAtLeast(1)
        val left = (panelX * sx).roundToInt()
        val top = (panelY * sy).roundToInt()
        model.setViewport(left, surfaceH - (top + h), w, h)
        if (labelsVisible) model.projectLabels(panelW, panelH, anchors)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stage.bringPanelToFront(this)
                lastX = e.rawX; lastY = e.rawY
                dragging = true
            }
            // a second finger = pinch. Dragging stays off until ALL fingers are lifted (no jumps, no mixing).
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                val dx = e.rawX - lastX
                val dy = e.rawY - lastY
                lastX = e.rawX; lastY = e.rawY
                if (interactionMode) model.rotateBy(dx * 0.4f, dy * 0.4f) else moveBy(dx, dy)
                stage.requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    // ------------------------------------------------------------------ 2D overlay (border + labels)
    override fun onDraw(c: Canvas) {
        borderPaint.color = if (interactionMode) 0xFF29B6F6.toInt() else 0x66FFFFFF
        c.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)
        if (labelsVisible && model.loaded) drawLabels(c)
    }

    private fun drawLabels(c: Canvas) {
        val pad = context.dp(4f)
        val fm = textPaint.fontMetrics
        val boxH = fm.descent - fm.ascent + pad * 2
        val barH = context.dp(44f)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in model.labels.indices) {
            if (anchors[i * 3 + 2] == 0f) continue
            val ax = anchors[i * 3]
            val ay = anchors[i * 3 + 1]
            val text = model.labels[i].text
            val boxW = textPaint.measureText(text) + pad * 2
            val dir = if (ax >= w / 2f) 1f else -1f

            var left = if (dir > 0) ax + context.dp(36f) else ax - context.dp(36f) - boxW
            left = left.coerceIn(context.dp(4f), max(context.dp(4f), w - boxW - context.dp(4f)))
            val top = (ay - context.dp(28f) - boxH / 2f).coerceIn(barH, max(barH, h - boxH - context.dp(4f)))
            val midY = top + boxH / 2f
            val edgeX = if (left + boxW / 2f > ax) left else left + boxW

            c.drawLine(ax, ay, edgeX, midY, linePaint)          // connector
            c.drawCircle(ax, ay, context.dp(3f), dotPaint)      // anchor dot on the part
            c.drawRoundRect(left, top, left + boxW, top + boxH, context.dp(4f), context.dp(4f), boxPaint)
            c.drawText(text, left + pad, top + pad - fm.ascent, textPaint)
        }
    }
}
