# Clay rendering design

Findings from reading the Minecraft 26.4-snapshot-4 and Fabric API 0.164 sources, and the design Clay follows because of them.

## What 26.4 gives us

### The GPU API is `com.mojang.renderpearl`, not `com.mojang.blaze3d`

Since the Vulkan backend landed, the low-level API lives in `com.mojang.renderpearl.api`. `blaze3d` keeps the higher level pieces (`RenderPipeline`, `VertexFormat`, `PoseStack`, `RenderSystem`).

| Need | 26.4 class | Notes |
|---|---|---|
| Device | `RenderSystem.getDevice()` → `GpuDevice` | `createBuffer(label, usage, ByteBuffer)` uploads immutable data in one call. |
| Buffers | `GpuBuffer`, `GpuBufferSlice` | Usage flags: `MAP_READ/WRITE`, `COPY_SRC/DST`, `VERTEX`, `INDEX`, `UNIFORM`, `UNIFORM_TEXEL_BUFFER`, `INDIRECT_PARAMETERS`. **There is no storage buffer usage.** |
| Per-frame data | `CommandEncoder.transientMemory().uploadGpu(...)` | Ring-allocated memory that lives for the frame. The GL backend maps and copies, Vulkan stages. |
| Drawing | `RenderPass` | `setPipeline`, `setUniform(name, slice / texture+sampler)`, `setVertexBuffer(slot, slice)`, `setIndexBuffer`, `drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance)`. |
| Bindings | `BindGroupLayout` / `UniformType` | Only `COMBINED_IMAGE_SAMPLER`, `UNIFORM_BUFFER` and `TEXEL_BUFFER` (with a `GpuFormat`). |
| Pipelines | `RenderPipeline.builder(snippets...)` | Shaders are GLSL 330 compiled to SPIR-V by shaderc at reload. Uniforms are matched by block/sampler name through SPIR-V reflection. A vertex format with a step rate of 1 is an **instanced** binding; up to 8 vertex buffers and 16 attributes. |
| Shader files | `assets/<ns>/shaders/core/<name>.vsh/.fsh`, includes `#include <minecraft:fog.glsl>` | Same resource reload as vanilla shaders, so resource packs can override them. |

### Pipeline registration and failure

`RenderPipelines.register` adds a *required* pipeline: if it fails to compile, the shader reload throws and the game refuses to load the resource pack. `RenderPipelines.registerOptional` compiles at the same time but only logs a warning on failure. `RenderSystem.getCompiledPipelineNullable(pipeline)` returns null for a failed pipeline (and retries the compile on every call, so the answer has to be cached).

**Decision:** Clay registers its pipelines as optional and asks once per resource reload whether they compiled. That is the "does the device support it" test: if the driver can't build the pipeline (or creating the GPU buffers throws), Clay falls back to the CPU renderer instead of crashing.

### Entity rendering is a submit → prepare → execute pipeline

Entity and block entity renderers don't draw. They call `SubmitNodeCollector` methods, which put `SubmitNode`s into *render phases* (`solid`, `translucentCustomGeometry`, `oitTranslucent`, ...). Each frame `FeatureRenderDispatcher`:

1. sorts every phase into groups of consecutive nodes with the same `FeatureRendererType`,
2. calls `FeatureRenderer.prepareGroup` for each group (outside any render pass, on the render thread) and `finishPrepare` once,
3. opens the level render pass and calls `executeGroup(context, oitStage, renderPass, groupIndex, submits, strictlyOrdered)` phase by phase,
4. calls `finishExecute` at the end of the frame.

Fabric adds the hook Clay needs: `collector.submitCustom(SubmitRenderPhase, node)` and `FeatureRendererRegistry.register(type, factory)`. A registered feature renderer gets a real `RenderPass` in `executeGroup`, in the same pass and the same phase as vanilla entities, so depth, draw order and render targets come for free. Every `FeatureRenderDispatcher` (level, GUI) gets its own renderer instance from the factory.

`SimpleFeatureRenderPhase` buckets nodes that implement `BatchableSubmit` by `batchKey()`, so all instances of one mesh arrive next to each other: that is where instancing comes from.

### Lighting, overlay, fog and translucency

Vanilla's entity shaders (`core/entity.vsh/.fsh`) read:

- `Projection`, `Fog`, `Globals`, `Lighting` uniform blocks, bound by `RenderSystem.bindDefaultUniforms(pass)`,
- `DynamicTransforms` (model-view matrix, color modulator), written per draw by `RenderSystem.getDynamicUniforms().writeTransform(...)` during prepare,
- `Sampler0` (the texture), `Sampler1` (overlay, via `gameRenderer.overlayTexture()`), `Sampler2` (lightmap, `FeatureFrameContext.lightmap()`).

Vertex positions are camera-relative world space (the `PoseStack` already contains the entity transform); `ModelViewMat` adds the view rotation. Fog distance and directional lighting are computed from that position and the posed normal.

**Translucency has two modes.** With *Improved Transparency* off, translucent custom geometry goes to `translucentCustomGeometry` and draws with blending, like vanilla's `entityTranslucent`. With it on, every translucent phase is aliased to `oitTranslucent`, which is executed three times with an `OitStage` (`DEPTH_BOUNDS`, `TRANSMITTANCE`, `ACCUMULATE`) and needs an `OitPipelineSet` instead of a blended pipeline.

**Decision:** Clay's vertex shader is its own (`clay:core/entity.vsh`) but writes exactly the outputs vanilla's `core/entity.fsh` expects, and the pipelines use vanilla's fragment shader, defines (`ALPHA_CUTOUT`, `PER_FACE_LIGHTING`) and bind groups. Lighting, overlay (hurt flash), lightmap, fog, cutout and blending are therefore vanilla's own code. Translucent materials get an `OitPipelineSet` built the same way as `RenderPipelines.OIT_ENTITY`, and Clay picks the pipeline for the stage it is handed.

## Design

### Two renderers behind one interface

`ClayModel` bakes a `.glb` into a renderer-neutral mesh (`ClayMesh`): per vertex a position, normal, color, atlas UV and up to four palette indices + weights; per *part* (one material class: cutout/translucent × culled/double-sided) an index range. Then one of two renderers draws it:

- **GPU** (`GpuRenderer`): the mesh is uploaded once per resource reload into an immutable vertex buffer and index buffer. Each frame only the *palette* (one matrix per node or joint, camera-relative) and a few bytes per instance are uploaded.
- **CPU** (`CpuRenderer`): the original path. Vertices are skinned/transformed on the CPU and streamed through `submitCustomGeometry` into vanilla's entity render types every frame.

Both use the same palette, computed by the same Java code (node hierarchy, animation, skin joints). A vertex's final transform is `Σ weightᵢ · palette[jointᵢ]`, in the vertex shader or in `ClaySkin` on the CPU. Rigid nodes are a palette entry with one weight of 1, so there is only one code path, with or without skins.

### The GPU path in detail

- **Vertex buffer** (40 bytes per vertex): `Position` RGB32F, `Color` RGBA8, `UV0` RG32F, `Normal` RGBA8_SNORM (w unused), `Joints` RGBA16_UINT, `Weights` RGBA8_UNORM. Quads are drawn as two triangles from a 32-bit index buffer.
- **Palette**: a `TEXEL_BUFFER` uniform (`ClayPalette`, RGBA32_FLOAT, four texels per matrix). Renderpearl has no storage buffers, and a uniform buffer is capped at 64 KB per binding; a texel buffer is the closest thing to a storage buffer the API offers and has no practical size limit.
- **Instances**: a second vertex buffer with step rate 1, one `RGBA32_SINT` per instance: where its palette starts, packed light, packed overlay and tint. Emissive parts are a part of their own and get full-bright light in their instance data, like the CPU path. One `drawIndexed(..., instanceCount, ...)` draws every instance of a mesh part in the group; the instance range is selected by offsetting the vertex buffer slice, because `firstInstance` isn't guaranteed (`DeviceFeatures.nonZeroFirstInstance`).
- **Upload**: `prepareGroup` collects instances; `finishPrepare` uploads the frame's palette and instance data with `transientMemory().uploadGpu`, before the render pass opens. `executeGroup` binds and draws.
- **Normals**: transformed by the upper 3×3 of the blended matrix and normalized. That is exact for rotations and uniform scale; a non-uniformly scaled node gets slightly tilted normals (both renderers do the same thing, so they still match).
- **Breaking cracks** (`crumbling`) stay on the CPU path in both renderers: they only draw while a player mines the block, and they need vanilla's decal render types.

### Choosing a renderer

`-Dclay.renderer=auto|gpu|cpu`, default `auto`. In `auto` Clay uses the GPU renderer unless

- a shader-pack mod is loaded (`iris`, `oculus`, `optifabric`): they replace vanilla's entity shaders and wouldn't apply to Clay's pipeline,
- Clay's pipelines failed to compile on this device, or creating the GPU buffers threw.

The decision is made once per resource reload (and logged at info level), with one warning the first time it falls back. `gpu` forces the GPU renderer unless it can't work at all (then it still falls back, with an error). If a pipeline lookup or a buffer upload fails mid-frame, Clay switches to the CPU renderer until the next reload. `/clay` (client command) prints the active renderer, the loaded models with quad counts, and the GPU memory Clay holds; `/clay renderer auto|gpu|cpu` switches at runtime.

Meshes are uploaded on their first draw after a reload rather than during the reload itself: the atlas UVs are only known once the block atlas is stitched, and a model nobody draws then costs no GPU memory.

### Verification

`./gradlew runClientGameTest` starts a client with a test mod (`src/gametest`) that draws `showcase.glb` (textured, vertex colored, cutout, translucent and emissive parts) and `skinned.glb` (a skinned column frozen in each clip: linear, step and cubic-spline keys, an object animation, a crossfade and an override), both made by `tools/make_test_models.py` in Blender. It screenshots them with each renderer, with Improved Transparency off and on, and fails if the screenshots differ in more than 0.2% of pixels. On the development machine they are identical. `AnimationTest` checks the skinning math itself against glTF's definition.

### Constraints that shape later phases

- **Skins and animation** (phase 2): glTF ignores the transform of a skinned mesh's own node; a joint's palette entry is `nodeGlobal(joint) · inverseBindMatrix`. `Rig` appends joint matrices after the node matrices, so `Joints` indexes one palette for rigid and skinned vertices alike. `ClayAnimator` only holds which clips play and their times; `Rig` samples them into preallocated arrays (rest pose, two working poses for crossfades), so posing doesn't allocate (`AnimationTest.posingDoesNotAllocate` measures it). The same animator can drive a LOD model, which samples its own clips of the same name. Morph targets aren't supported: renderpearl has no storage buffers to hold per-vertex deltas cheaply, and Blender character rigs rarely need them for Minecraft-scale models.
- **Per-instance materials** (phase 3): textures live in the block atlas, so a texture swap is a different atlas rectangle and a tint is the instance `Tint` attribute. Vertices keep their UVs within the material's image plus a material slot (`Material` R32_UINT, so a vertex is 44 bytes); each instance appends a material table (two texels per slot: atlas rectangle, then scroll offset and a wrap flag) to its palette in the texel buffer, and its instance record (now 20 bytes) says where. Non-scrolling materials clamp their UVs per vertex, exactly as before.
- **Scrolling** needs to wrap within the image, not into the atlas neighbours, so Clay has its own fragment shader: vanilla's `core/entity.fsh` with the texture lookup replaced by `rect.xy + fract(uv) * size`, sampled with `textureGrad` on the unwrapped coordinate so the mip level doesn't jump at the seam. The CPU renderer gets the same picture by cutting each triangle of a scrolling material along its UV tile edges (`UvWrap`) before streaming it; the pieces cover the same pixels because rasterization interpolates linearly either way. The trade-off: a resource pack that changes vanilla's entity fragment shader no longer changes Clay's.
- **Animated textures** aren't Clay's job at all: `ClayAssets` hands the atlas a sprite with `AnimationMetadataSection` metadata when a material has `clay_frametime`, and vanilla animates it, for blocks, items and entities alike.
- **Collision shapes** (phase 3): `VoxelShape`s are needed on the server too, so `ClayShapes` and the `.glb` reader (`gltf` package) live in the common source set and read the `.glb` from the mod jar, not from the client resource manager.
- **Block budget** (phase 3): `ClayGeometry` warns past 220 quads and, with `"simplify"`, clusters vertices on a grid that coarsens until the model fits. It's a vertex-clustering decimator, chosen because it is short and never adds geometry; a quadric decimator would keep more detail.
- **Server-started shots** (phase 4): a server can't send a Java lambda, so clips that can be played remotely are data: a sealed `Clip` hierarchy of records (keyframes, orbits, model cameras, sequences, effects) with a hand-written `StreamCodec` that bounds nesting and list sizes. `ClayCamera.playFor` has to be callable from common code, so `ClayCamera` moved to the common source set. All it needs from the client is the current level and the loaded `.glb` files, which `ClayClient` hands it as two functions; everything else (timing, sampling, effects) is plain common code, and the mixins only read `shot()` and `overlay()`.
- **Camera roll and FOV** (phase 4): 26.4's `Camera.setRotation` builds its quaternion with `rotationYXZ(yaw, pitch, 0)`; a `@ModifyArg` supplies the roll while a shot sets the rotation, and `calculateFov`'s result is replaced when the shot has a field of view. A glTF camera's matrix is turned into yaw, pitch and roll by rebuilding vanilla's quaternion and measuring the up vector against it (`CameraTest.gltfCamerasKeepTheirRoll` checks 200 random orientations). Fade and letterbox are drawn in the HUD hook that already hides the HUD during a shot.
