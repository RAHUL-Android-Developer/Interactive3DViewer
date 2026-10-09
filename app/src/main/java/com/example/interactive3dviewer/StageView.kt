package com.example.interactive3dviewer

import android.app.ActivityManager
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * The single canvas. Child 0 is ONE SurfaceView that Filament renders into; every model is a ModelPanel
 * on top of it. Each frame, every panel's Filament View is drawn into its own viewport rectangle of that
 * one surface (child order == z-order).
 *
 *  - on-demand rendering: frames are only produced when something changed (drag, rotate, zoom, load...)
 *  - low-RAM devices render at 75 % resolution (hardware-scaled by the compositor)
 */

class StageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    private val gl: FilamentContext? = if (isInEditMode()) null else FilamentContext()
    private val surfaceView: SurfaceView? = if (isInEditMode()) null else SurfaceView(context)
    private var swapChain: SwapChain? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private val renderScale: Float
    private val choreographer = if (isInEditMode()) null else Choreographer.getInstance()
    private var scheduled = false
    private var dirty = true
    private var resumed = false
    private var released = false

    private val pending = ArrayList<ModelScene>()
    private val ioExecutor = if (isInEditMode()) null else Executors.newSingleThreadExecutor()
    private var spawnCount = 0

    init {
        if (isInEditMode()) {
            renderScale = 1f
            val tv = android.widget.TextView(context).apply {
                text = "3D Stage View"
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
            }
            addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            renderScale = if (am.isLowRamDevice || am.memoryClass <= 128) 0.75f else 1f
            val sv = surfaceView!!
            addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            sv.holder.addCallback(this)
        }
    }

    //public API
    /** "Add model" -> read + parse on a worker thread, then build the Filament objects on the UI thread. */
    fun addModel(assetName: String) {
        if (isInEditMode()) return
        ioExecutor?.execute {
            try {
                val bytes = context.assets.open("models/$assetName").use { it.readBytes() }
                val defs = GlbLabelParser.parse(bytes)
                Log.d("sdfsdsfds","$defs")
                post { if (!released) createPanel(assetName, bytes, defs) }
            } catch (e: Exception) {
                post {
                    Toast.makeText(
                        context, "Failed to load $assetName: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun requestRender() {
        if (isInEditMode()) return
        dirty = true
        schedule()
    }

    fun bringPanelToFront(p: ModelPanel) {
        if (isInEditMode()) return
        if (getChildAt(childCount - 1) !== p) {
            p.bringToFront()
            requestRender()
        }
    }

    /** Close button: remove the view and free every native resource of the model. */
    fun removePanel(p: ModelPanel) {
        if (isInEditMode()) return
        removeView(p)
        val m = p.model
        m.closed = true
        if (m.loaded) m.destroy() else if (m !in pending) pending += m   // still decoding -> destroyed once load completes
        requestRender()
    }

    fun onResume() {
        if (isInEditMode()) return
        resumed = true; requestRender()
    }

    fun onPause() {
        if (isInEditMode()) return
        resumed = false
        choreographer?.removeFrameCallback(frameCb)
        scheduled = false
    }

    fun release() {
        if (isInEditMode()) return
        released = true
        choreographer?.removeFrameCallback(frameCb)
        ioExecutor?.shutdown()
        if (pending.isNotEmpty()) gl?.resourceLoader?.asyncCancelLoad()
        for (i in childCount - 1 downTo 0) (getChildAt(i) as? ModelPanel)?.let { removeView(it); it.model.destroy() }
        pending.filter { it.closed }.forEach { it.destroy() }
        pending.clear()
        swapChain?.let { gl?.engine?.destroySwapChain(it) }
        swapChain = null
        gl?.engine?.flushAndWait()
        gl?.destroy()
    }

    //panels
    private fun createPanel(assetName: String, bytes: ByteArray, defs: List<LabelDef>) {
        val model = ModelScene.create(gl!!, bytes, defs)
        if (model == null) {
            Toast.makeText(context, "Could not parse $assetName", Toast.LENGTH_LONG).show()
            return
        }
        val title = assetName.substringBeforeLast('.').replace('_', ' ')
        val panel = ModelPanel(context, this, model, title)
        val size = (min(width, height) * 0.5f).coerceAtLeast(context.dp(160f))
        val off = context.dp(24f) * (spawnCount++ % 6) + context.dp(8f)
        panel.setRect(off, off, size, size)
        addView(panel, LayoutParams(size.toInt(), size.toInt()))
        pending += model
        requestRender()
    }

    // render loop
    private fun schedule() {
        if (!scheduled && resumed && !released) {
            scheduled = true
            choreographer?.postFrameCallback(frameCb)
        }
    }

    private val frameCb = Choreographer.FrameCallback { frameTimeNanos ->
        scheduled = false
        if (released || !resumed) return@FrameCallback

        if (pending.isNotEmpty()) {
            gl!!.resourceLoader.asyncUpdateLoad()
            if (gl.resourceLoader.asyncGetLoadProgress() >= 1f) finishLoads()
        }
        if (dirty) {
            dirty = false
            renderFrame(frameTimeNanos)
        }
        if (pending.isNotEmpty()) schedule()
    }

    private fun finishLoads() {
        val done = ArrayList(pending)
        pending.clear()
        for (m in done) if (m.closed) m.destroy() else m.onLoaded()
        dirty = true
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        if (surfaceW == 0 || width == 0 || height == 0) return
        val sx = surfaceW.toFloat() / width
        val sy = surfaceH.toFloat() / height

        val ready = ArrayList<ModelPanel>(childCount)
        for (i in 0 until childCount) {
            val p = getChildAt(i) as? ModelPanel ?: continue
            if (!p.model.loaded) continue
            p.prepareForRender(sx, sy, surfaceH)
            ready += p
        }
        if (gl!!.renderer.beginFrame(sc, frameTimeNanos)) {
            gl.backgroundView.viewport = Viewport(0, 0, surfaceW, surfaceH)
            gl.renderer.render(gl.backgroundView)
            for (p in ready) gl.renderer.render(p.model.view)
            gl.renderer.endFrame()
        }
        for (p in ready) if (p.labelsVisible) p.invalidate()
    }

    //surface / size
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        surfaceView?.holder?.setFixedSize(
            (w * renderScale).toInt().coerceAtLeast(1), (h * renderScale).toInt().coerceAtLeast(1)
        )
        for (i in 0 until childCount) (getChildAt(i) as? ModelPanel)?.let {
            it.setRect(
                it.panelX, it.panelY, min(it.panelW, w.toFloat()), min(it.panelH, h.toFloat())
            )
        }
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        swapChain = gl!!.engine.createSwapChain(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        swapChain?.let {
            if (!released) {
                gl?.engine?.destroySwapChain(it); gl?.engine?.flushAndWait()
            }
        }
        swapChain = null
    }
}
