package com.example.interactive3dviewer

import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.View as FilamentView

/** One engine, one renderer and one set of loaders shared by every model. */
class FilamentContext {
    companion object {
        init { Filament.init(); Gltfio.init() }
    }

    val engine: Engine = Engine.create()
    val renderer: Renderer = engine.createRenderer()
    private val materialProvider = UbershaderProvider(engine)
    val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    val resourceLoader = ResourceLoader(engine)

    // Always-rendered empty background view: guarantees the screen is cleared,
    // even when there are no models (e.g. after the last one is closed).
    private val bgScene = engine.createScene()
    private val bgSkybox = Skybox.Builder().color(0.07f, 0.07f, 0.085f, 1f).build(engine)
    private val bgCameraEntity = EntityManager.get().create()
    private val bgCamera: Camera = engine.createCamera(bgCameraEntity)
    val backgroundView: FilamentView = engine.createView()

    init {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.07, 0.07, 0.085, 1.0)
        }
        bgScene.skybox = bgSkybox
        bgCamera.setProjection(45.0, 1.0, 0.1, 10.0, Camera.Fov.VERTICAL)
        backgroundView.scene = bgScene
        backgroundView.camera = bgCamera
        backgroundView.antiAliasing = FilamentView.AntiAliasing.NONE
        backgroundView.dithering = FilamentView.Dithering.NONE
    }

    fun destroy() {
        engine.destroyView(backgroundView)
        engine.destroyScene(bgScene)
        engine.destroySkybox(bgSkybox)
        engine.destroyCameraComponent(bgCameraEntity)
        EntityManager.get().destroy(bgCameraEntity)

        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoader.destroy()
        engine.destroyRenderer(renderer)
        engine.destroy()
    }
}