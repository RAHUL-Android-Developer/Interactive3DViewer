package com.example.interactive3dviewer

import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer
import kotlin.math.sin
import kotlin.math.sqrt
import com.google.android.filament.View as FilamentView

/**
 * Everything Filament needs for ONE model: its own Scene + View + Camera (+ a light, a skybox colour and
 * a tiny SH-only indirect light — no textures). All of it is destroyed in [destroy] when the model is closed.
 * The View renders into a viewport rectangle of the single shared SurfaceView.
 */
class ModelScene private constructor(
    private val gl: FilamentContext,
    private val asset: FilamentAsset,
    labelDefs: List<LabelDef>,
) {
    class Label(val text: String, val entity: Int)

    private val engine = gl.engine
    private val em = EntityManager.get()
    private val scene = engine.createScene()
    val view: FilamentView = engine.createView()
    private val cameraEntity = em.create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val sunEntity = em.create()
    private val ibl: IndirectLight
    private val skybox: Skybox
    val labels: List<Label>
    // model normalisation: fit the bounding sphere into radius 1 around the origin
    private val cx: Float
    private val cy: Float
    private val cz: Float
    private val baseScale: Float
    // interaction state
    private var yaw = 0f
    private var pitch = 0f
    private var zoom = 1f

    var loaded = false
        private set
    var closed = false
    var onReady: (() -> Unit)? = null
    private var vpL = -1;
    private var vpB = -1;
    private var vpW = -1;
    private var vpH = -1
    private val viewM = FloatArray(16)
    private val projM = DoubleArray(16)
    private val worldM = FloatArray(16)

    init {

        //normalisation
        val box = asset.boundingBox
        val c = box.center
        val h = box.halfExtent
        cx = c[0]; cy = c[1]; cz = c[2]
        val radius = sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]).coerceAtLeast(1e-4f)
        baseScale = 1f / radius

        // --- cheap lighting: 1 directional light (no shadows) + constant ambient via SH (no IBL texture)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(100_000f)
            .direction(0.3f, -0.5f, -1f)
            .castShadows(false)
            .build(engine, sunEntity)
        ibl = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.9f, 0.9f, 0.9f))
            .intensity(30_000f)
            .build(engine)
        skybox = Skybox.Builder().color(0.16f, 0.18f, 0.23f, 1f).build(engine)

        scene.addEntity(sunEntity)
        scene.indirectLight = ibl
        scene.skybox = skybox

        view.scene = scene
        view.camera = camera
        view.antiAliasing = FilamentView.AntiAliasing.NONE
        view.dithering = FilamentView.Dithering.NONE
        view.renderQuality = FilamentView.RenderQuality().apply { hdrColorBuffer = FilamentView.QualityLevel.LOW }

        val resolved = ArrayList<Label>()
        for (d in labelDefs) {
            val ents = asset.getEntitiesByName(d.nodeName)
            val e = when {
                ents.isEmpty() -> 0
                ents.size == d.sameNameCount -> ents.getOrElse(d.occurrence) { ents[0] }
                else -> ents[0]
            }
            if (e != 0) resolved += Label(d.text, e)
        }
        labels = resolved

        applyModelTransform()

    }

    companion object {
        fun create(gl: FilamentContext, bytes: ByteArray, defs: List<LabelDef>): ModelScene? {
            val asset = gl.assetLoader.createAsset(ByteBuffer.wrap(bytes)) ?: return null
            if (!gl.resourceLoader.asyncBeginLoad(asset)) {   // decode textures/buffers off the render path
                gl.assetLoader.destroyAsset(asset)
                return null
            }
            return ModelScene(gl, asset, defs)
        }
    }

    /** Called when the async resource load finished. */
    fun onLoaded() {
        scene.addEntities(asset.entities)
        asset.releaseSourceData()          // free the CPU-side copy of the glb
        loaded = true
        applyModelTransform()
        onReady?.invoke()
    }

    // ---------------------------------------------------------------- interaction mode

    fun rotateBy(dxDeg: Float, dyDeg: Float) {
        yaw += dxDeg
        pitch = (pitch + dyDeg).coerceIn(-90f, 90f)
        applyModelTransform()
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.4f, 4f)
        updateCamera()
    }

    private fun applyModelTransform() {
        val tm = engine.transformManager
        val inst = tm.getInstance(asset.root)
        if (inst == 0) return
        val m = M4.mul(
            M4.rotX(Math.toRadians(pitch.toDouble()).toFloat()),
            M4.mul(
                M4.rotY(Math.toRadians(yaw.toDouble()).toFloat()),
                M4.mul(M4.scale(baseScale), M4.translation(-cx, -cy, -cz))
            )
        )
        tm.setTransform(inst, m)
    }

    // ---------------------------------------------------------------- viewport / camera

    /** Viewport in SURFACE pixels, origin bottom-left. Content automatically re-fits when the container is resized. */
    fun setViewport(left: Int, bottom: Int, w: Int, h: Int) {
        if (left == vpL && bottom == vpB && w == vpW && h == vpH) return
        val sizeChanged = w != vpW || h != vpH
        vpL = left; vpB = bottom; vpW = w; vpH = h
        vpL = left; vpB = bottom; vpW = w; vpH = h
        view.viewport = Viewport(left, bottom, w, h)
        if (sizeChanged) updateCamera()
    }

    private fun updateCamera() {
        if (vpW <= 0 || vpH <= 0) return
        val aspect = vpW.toDouble() / vpH
        val fov = 35.0
        val dir = if (aspect >= 1.0) Camera.Fov.VERTICAL else Camera.Fov.HORIZONTAL
        camera.setProjection(fov, aspect, 0.02, 50.0, dir)
        val dist = 1.15 / sin(Math.toRadians(fov / 2)) / zoom
        camera.lookAt(0.0, 0.0, dist, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    // ---------------------------------------------------------------- labels

    /**
     * Projects every labelled node's WORLD position through the camera.
     * out[i*3], out[i*3+1] = x/y in container pixels (origin top-left), out[i*3+2] = 1 if visible.
     */
    fun projectLabels(w: Float, h: Float, out: FloatArray) {
        if (labels.isEmpty()) return
        camera.getViewMatrix(viewM)
        camera.getProjectionMatrix(projM)
        val tm = engine.transformManager
        for (i in labels.indices) {
            out[i * 3 + 2] = 0f
            val inst = tm.getInstance(labels[i].entity)
            if (inst == 0) continue
            tm.getWorldTransform(inst, worldM)
            val x = worldM[12].toDouble(); val y = worldM[13].toDouble(); val z = worldM[14].toDouble()
            val ex = viewM[0] * x + viewM[4] * y + viewM[8] * z + viewM[12]
            val ey = viewM[1] * x + viewM[5] * y + viewM[9] * z + viewM[13]
            val ez = viewM[2] * x + viewM[6] * y + viewM[10] * z + viewM[14]
            val clipX = projM[0] * ex + projM[4] * ey + projM[8] * ez + projM[12]
            val clipY = projM[1] * ex + projM[5] * ey + projM[9] * ez + projM[13]
            val clipW = projM[3] * ex + projM[7] * ey + projM[11] * ez + projM[15]
            if (clipW <= 1e-6) continue
            out[i * 3] = (((clipX / clipW) * 0.5 + 0.5) * w).toFloat()
            out[i * 3 + 1] = ((1.0 - ((clipY / clipW) * 0.5 + 0.5)) * h).toFloat()
            out[i * 3 + 2] = 1f
        }
    }

    // ---------------------------------------------------------------- cleanup

    /** Frees every native object of this model. Must be called on the render/UI thread, never twice. */
    fun destroy() {
        if (loaded) scene.removeEntities(asset.entities)
        gl.assetLoader.destroyAsset(asset)          // renderables, vertex/index buffers, textures, material instances
        scene.removeEntity(sunEntity)
        engine.destroyEntity(sunEntity); em.destroy(sunEntity)
        view.scene = null
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity); em.destroy(cameraEntity)
        engine.destroyIndirectLight(ibl)
        engine.destroySkybox(skybox)
        onReady = null
    }
}
