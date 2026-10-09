# Interactive 3D Viewer (Android, Kotlin)

A single-activity Android app that loads several `.glb` models onto one screen at the same time. Each model sits in its own container that can be dragged and resized, and each model can be rotated and zoomed independently. Part labels are read from the GLB file and drawn on top of the 3D view.

- Language: Kotlin
- Min SDK: 24
- UI: Android Views (XML layout and custom views)
- 3D library: Google Filament (with its `gltfio` module)

## How to run
1. Put the five `.glb` files in `app/src/main/assets/models/`.
2. Open the project in Android Studio and sync Gradle.
3. Run on a device, or build a release APK with `Build > Generate Signed App Bundle / APK`.

## Features
- **Add model button** lists every `.glb` in `assets/models` and loads the one you tap.
- **Multiple models** can be on screen together. Adding more does not remove the existing ones.
- **Drag** one finger to move a container anywhere on the screen.
- **Pinch** to resize a container. The 3D content refits automatically.
- **Three buttons on every model:**
  - *Interaction toggle*: one finger rotates the model, pinch zooms it, and the container stays fixed.
  - *Label toggle*: shows or hides part labels (hidden on load).
  - *Close*: removes the model and frees its resources.
- **Modes never mix.** Quick-scale (one-finger zoom) is disabled, and after a pinch the container will not drag until all fingers are lifted.
- **Labels** come from `extras.prop` of the GLB nodes. The node's world position is projected through the camera every frame, and a connector line links each label to its part.

## Which 3D library and why
I used **Filament directly**, not a wrapper such as SceneView. Filament is small and fast, supports old OpenGL ES 3.0 GPUs, and `gltfio` loads GLB files. Wrapper libraries usually create one surface and engine per model, which is expensive on low-RAM devices. With Filament I could use a single engine and a single surface for all models.

## Architecture
One Filament `Engine` and `Renderer`, and **one SurfaceView**. Each model has its own `Scene`, `View` and `Camera`, and is rendered into its own rectangle (viewport) of that one surface. Moving or resizing a container only changes that rectangle. Android views (buttons, border, 2D labels) are drawn on top.

| File | Role |
|---|---|
| `MainActivity` | The only Activity; Add model menu |
| `StageView` | The single canvas; owns the SurfaceView and the render loop |
| `ModelPanel` | The container: gestures, 3 buttons, border, label drawing |
| `ModelScene` | Per-model Filament objects, rotate/zoom, label projection, cleanup |
| `GlbLabelParser` | Reads only the GLB JSON chunk to find `extras.prop` |
| `FilamentContext` | Shared engine, renderer, loaders and the background view |

## Performance optimisations
- One engine, one GL context and one surface for all models.
- **Render on demand:** a frame is only drawn when something changes, so an idle app uses almost no GPU.
- **Lower resolution on low-RAM devices** via `SurfaceHolder.setFixedSize`.
- Anti-aliasing, dithering and shadows are turned off.
- Lighting uses one directional light plus a constant ambient colour (no environment texture kept in memory).
- **Async loading:** the file is read and parsed on a worker thread, and textures decode through `ResourceLoader.asyncBeginLoad`, so the UI does not freeze while a model loads. The CPU copy of the GLB is released after loading.
- Label projection only runs while labels are visible and reuses its buffers.
- **Full cleanup on Close:** entities, asset, view, scene, camera, light, skybox and indirect light are destroyed.
- A permanent background view clears the whole surface every frame, so no image is left behind after the last model is closed.
- `.glb` files are stored uncompressed in the APK, and only `arm64-v8a` and `armeabi-v7a` are built.

## Profiling
I profiled the app with `adb shell dumpsys gfxinfo` (counters reset, then 30 seconds of dragging, rotating and zooming with 5 models loaded) and `adb shell dumpsys meminfo`.

**Baseline (5 models loaded):**

| Measurement | Value |
|---|---|
| Frames rendered in 30 s | 238 |
| Median frame time | 150 ms |
| Total memory (PSS) | about 335 MB |
| Graphics memory | about 214 MB |
| Native heap | about 70 MB |

`gfxinfo` measures the Android UI layer, so these numbers include the UI thread waiting on the GPU, not only Filament's own frame time.

**What the profile showed:** the render loop spends its time waiting on the GPU, and graphics memory is the largest part of the footprint. This tells me where the remaining optimisation work should go (see below).

## Trade-offs
- Every model is re-rendered whenever any one container moves, because the surface content changes. This keeps the design simple and works well at small model counts.
- No anti-aliasing, so edges are slightly jagged. I chose speed over quality.
- Containers are rectangles (viewport clipping), not rounded.
- Resizing keeps the container's aspect ratio.
- Rendering runs on the UI thread for simplicity.

## Next optimisation steps
Based on the profile, these are the changes I would make next, in order of expected impact:
1. Turn off post-processing on each model view to save render targets and passes.
2. Cap rendering at about 30 fps for steadier frame pacing, and lower the render scale.
3. Downscale large textures before bundling (for example `npx @gltf-transform/cli resize in.glb out.glb --width 1024 --height 1024`).
4. Run Filament rendering on a dedicated thread instead of the UI thread.
5. Render only the models that changed into cached textures and composite them, so dragging costs very little.
6. Add level-of-detail for small containers.

## Other possible improvements
- Hide labels that are behind the model, and avoid overlap between label boxes.
- Unit tests for the label parser and instrumented frame-time tests.

## Notes and limitations
- Labels are matched to parts by glTF node **name**. Nodes without a name cannot be matched, and duplicate names are matched by order.
- The label anchor is the node's own position, as specified, not the centre of its mesh.
- Textures inside a GLB are decoded at full size, so very large textures increase memory use. Resizing them before bundling helps.
- Filament 1.77.1 is used. It is newer than the version the code was first written against, so a few API types were updated (for example `clearColor` is a `DoubleArray`).

## AI assistance disclosure
I used an AI assistant for part of this project. It helped me with the initial code structure, the Filament setup and fixing some build errors. I then integrated the code into my project, built it, tested it and fixed issues I found (for example the Kotlin version conflict, the Filament API type changes and the leftover image after closing a model). I understand the main design decisions described above and can explain them in the video walkthrough.
