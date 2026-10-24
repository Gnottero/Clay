# Clay

Clay is a small Fabric library that lets you use models made in Blender directly in Minecraft. You export a `.glb` file, drop it into your mod's assets, and use it as a block, an item or an entity. It handles everything from simple low-poly blocks to detailed entity models with several thousand faces.

It also has a cinematic camera for cutscenes and dramatic ability shots, since that's the kind of thing you tend to want once your models look good.

**Supported versions:** Minecraft 26.4 snapshots, Fabric Loader 0.20.0 or newer, Fabric API 0.164 or newer, Java 25.

## What you get

- glTF binary (`.glb`) models as **blocks and items**, through ordinary model json files. Blockstate rotations, display transforms and parents work as you'd expect.
- **Entity and block entity** rendering with `ClayModel`, drawn from meshes kept on the GPU, with an optional low-detail model for distant entities.
- **Blender animations and armatures**: play, loop and crossfade the actions you made in Blender, with skinned (bone-deformed) meshes, and move individual parts from code on top.
- Textures, vertex colors, cutout and translucent materials, double-sided faces and emissive (glowing) materials, all read from the Blender material. Animated textures everywhere, and scrolling textures on entities.
- **Per-instance looks**: tint a copy of a model or swap its textures without loading it twice.
- **Sockets**: empties in Blender become attachment points for held items, other models and particles, and follow the animation.
- **Block shapes** made from the model, for collision and outlines, on the server too.
- A **cinematic camera** (`ClayCamera`) that takes over the player's view for a scripted shot: keyframed spline paths with easing, field of view and roll, shake, fades and letterbox bars, sequences of shots, cameras animated in Blender, and shots started by the server.

## Adding Clay to your mod

Clay isn't on a public Maven repository yet, so for now you build it once and publish it to your local Maven:

```sh
git clone https://github.com/Gnottero/Clay.git
cd Clay
./gradlew publishToMavenLocal
```

Then add it to your mod's `build.gradle`:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation "dev.gnottero:clay:0.1.0"
    include "dev.gnottero:clay:0.1.0"   // bundles Clay inside your jar
}
```

and list it as a dependency in your `fabric.mod.json`:

```json
"depends": {
    "clay": "*"
}
```

### Working against a local checkout

If you're changing Clay while working on your mod, you can skip the publishing step. Put both projects next to each other and add this to your mod's `settings.gradle`:

```groovy
includeBuild '../Clay'
```

Keep the same dependency lines as above. Clay's client classes live in a separate source set that only ends up in the jar, so you also need to tell Gradle to compile against the jar:

```groovy
configurations.compileClasspath.attributes {
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.JAR))
}
```

## Exporting from Blender

Use **File > Export > glTF 2.0** with these settings:

| Setting | Value |
|---|---|
| Format | glTF Binary (`.glb`), with textures embedded as PNG |
| Include > Data | on (applying modifiers is fine) |
| Draco and Meshopt compression | off |
| +Y Up | on (the default) |

One Blender unit is one block. Where you put the origin depends on what the model is for:

- **Blocks and items:** the origin is the bottom center of the block. A full cube covers -0.5 to 0.5 on X and Y, and 0 to 1 on Z.
- **Entities:** the origin is at the entity's feet.

Save the file as `assets/<modid>/clay/<name>.glb`. From then on you refer to it as `<modid>:<name>`.

### Materials

Clay reads a handful of things from each material and maps them to what vanilla rendering can do:

| In Blender | In game |
|---|---|
| Base Color image and color | The texture (stitched into the block atlas), multiplied by the color |
| Vertex colors (`COLOR_0`) | Multiplied in |
| Alpha through a **Math: Round** node (glTF "mask") | Cutout |
| Alpha connected directly (glTF "blend") | Translucent |
| Backface Culling turned off | Drawn from both sides |
| Emission color with strength above 0 | Fullbright, so it glows in the dark |
| Custom property `clay_ao = false` on the material | No ambient occlusion |
| Custom property `clay_frametime = 4` on the material | Animated texture: the image is a vertical strip of square frames, each shown for 4 ticks, like a vanilla `.mcmeta` |
| Custom property `clay_scroll = [0.5, 0]` on the material | The texture scrolls by half its width per second (entities only, see below) |

Normal, roughness, metallic and occlusion maps are ignored because vanilla shaders have no use for them. Clay logs a warning when it skips one so you know it happened.

Custom properties need **Include > Custom Properties** ticked in the exporter. Objects named `collision` and empties named `socket_...` have special meanings, described in [Block shapes](#block-shapes) and [Sockets](#sockets).

Keep UVs inside the 0 to 1 range. Textures share an atlas, so tiling can't work: a face whose UVs fit inside one tile gets wrapped back into place, and anything larger gets clamped. Scrolling textures are the exception: they wrap around within their own image.

## Blocks and items

Point a model json at your `.glb` with the `clay:gltf` type. For example, `assets/<modid>/models/block/statue.json`:

```json
{
  "fabric:type": "clay:gltf",
  "model": "<modid>:statue",
  "parent": "minecraft:block/block"
}
```

Everything else in the file works like a vanilla model: `parent`, `display`, `textures.particle` (which defaults to the model's first image), `ambientocclusion` and `gui_light`. Blockstates reference the model as usual, so rotations like `"y": 90` just work.

For the item form, add `assets/<modid>/items/statue.json`:

```json
{ "model": { "type": "minecraft:model", "model": "<modid>:block/statue" } }
```

### Tools and weapons

Models that only exist as items go in `models/item/` with `"parent": "minecraft:item/handheld"`. Lay the model out diagonally in Blender's XZ plane, with the handle at the bottom left and the tip at the top right, the same way `diamond_sword.png` is drawn, and keep it thin along Y. The vanilla handheld poses will then hold it correctly.

Guns and other crossbow-like items use `"parent": "minecraft:item/crossbow"` instead and follow `crossbow_standby.png`: muzzle at the top left, stock at the bottom right, and the top of the gun facing -Y.

If your item is bigger than a block, model it at its real size and place the grip where the vanilla grip would be. That's roughly (-0.28, 0.22) in Blender's XZ for a sword, or (0.18, 0.32) for a crossbow. The hand poses then hold it at full size. To make it fit in the inventory, shrink it with the `gui` and `fixed` display transforms, and set `ground` to the parent's third-person scale so it doesn't look oversized when dropped.

### Display transforms

The `display` block controls how the model sits in each slot. It's the same thing Blockbench's Display tab edits:

```json
{
  "fabric:type": "clay:gltf",
  "model": "mymod:sword",
  "parent": "minecraft:item/handheld",
  "display": {
    "gui":                   { "rotation": [0, 0, 0],    "translation": [0, 0, 0],          "scale": [1.1, 1.1, 1.1] },
    "firstperson_righthand": { "rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68] },
    "fixed":                 { "rotation": [0, 180, 0],  "scale": [1.2, 1.2, 1.2] }
  }
}
```

The slots are `gui`, `ground`, `fixed` (item frames), `head`, `on_shelf`, `thirdperson_righthand`, `thirdperson_lefthand`, `firstperson_righthand` and `firstperson_lefthand`. If you leave out a left-hand slot, it mirrors the right-hand one. Rotation is in degrees and applied X, then Y, then Z. Translation is in pixels (1/16 of a block) and limited to ±80, and scale is limited to 4.

Each slot you define replaces the one from `parent`, and the ones you leave out are inherited. `minecraft:item/handheld` gives you the vanilla sword poses and `minecraft:block/block` the vanilla block poses.

The easiest way to tune these is in Blockbench. Import the `.glb` with **File > Import > glTF**, adjust each slot in the Display tab, export with **File > Export > Export Block/Item Model**, and copy just the `display` object into your json. Keep your own `fabric:type` and `model` lines, since the exported elements aren't used. In game, F3+T reloads your changes.

## Entities and block entities

For anything that moves, load the model with `ClayModel` and draw it from your renderer:

```java
static final ClayModel MODEL = ClayModel.of(Identifier.fromNamespaceAndPath("mymod", "golem"))
    .lod(Identifier.fromNamespaceAndPath("mymod", "golem_low"), 32);   // optional: switch models past 32 blocks

@Override
public void submit(MyRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
    MODEL.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.distanceToCameraSq,
        Map.of("head", new Matrix4f().rotateY(state.headYaw)));
    super.submit(state, pose, collector, camera);
}
```

Every object in your Blender scene becomes a node, named after the object. The map you pass to `submit` lets you transform individual nodes by name, on top of their own transform, which is how you animate heads, arms, wheels and so on.

`MODEL.bounds()` gives you the bounding box of the model at rest, which is handy for sizing the entity's culling box.

For block entities there's one more overload of `submit` that takes the render state's `breakProgress`. Pass it along and the block breaking cracks show up on your model while the player mines it.

### Animation

Animations made in Blender come along in the `.glb`: object animations (moving, turning and scaling objects) and armatures with skinned meshes. Each Blender action becomes a clip with the action's name. Export with **Animation** enabled and **Animation mode: Actions**, and push each action you want to keep to its own NLA track, or Blender only exports the active one. Linear, constant (step) and Bezier interpolation all come across; untick **Always Sample Animations** to keep them as they are instead of baking every frame.

Give each entity its own `ClayAnimator`, advance it with your clock, tell it what to play, and draw with it:

```java
// in your entity (client side)
final ClayAnimator animator = MyRenderer.MODEL.animator();

// in the renderer
ClayAnimator anim = state.animator;
anim.update((state.ageInTicks) / 20f);                    // seconds on any clock you like
if (state.attacking) anim.play("attack", false, 0.1f);    // play once, fading in over 0.1 s
else anim.play(state.walking ? "walk" : "idle", true, 0.25f);
anim.override("head", new Matrix4f().rotateY(state.headYaw));
MODEL.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.distanceToCameraSq, anim);
```

- `play(clip)` loops, `play(clip, false)` plays once and holds the last frame; `finished()` tells you when it got there. Asking for the clip that's already playing does nothing, so you can call it every frame.
- The third argument crossfades from whatever was showing, in seconds. `stop(seconds)` fades back to the rest pose.
- `speed(2)` plays twice as fast, a negative speed plays backwards.
- `override(node, matrix)` moves a node on top of the animation, in its parent's space, the same way the map in the plain `submit` does. Its children and the skin follow.
- Time only moves when you call `update`, so pausing the game or freezing the entity freezes the animation too.
- Posing an animated model doesn't create garbage, so many animated entities don't stress the garbage collector.

Skinning takes up to four bones per vertex, as glTF allows. The GPU renderer deforms the mesh in its vertex shader, the CPU renderer does the same math in Java, and both give the same picture.

`MODEL.animations()` lists the clips and `MODEL.duration(clip)` gives a clip's length in seconds.

### Tints, texture swaps and scrolling

A `ClayInstance` is one copy of a model with its own look, and its own `ClayAnimator`. Many instances share one loaded model, so a hundred differently colored golems cost the same as a hundred identical ones.

```java
final ClayInstance look = MODEL.instance()
    .tint(0xFFFF8080)                                                  // ARGB, multiplied into every color
    .texture("cloth", Identifier.withDefaultNamespace("block/red_wool")) // material name -> any block atlas sprite
    .scroll("water", 0, 0.25f);                                          // a quarter of the image per second

look.animator().play("walk");
MODEL.submit(pose, collector, light, overlay, distanceSq, look);
```

Materials are named as in Blender. A swapped texture can be any sprite in the block atlas: vanilla block textures, your own block textures, or another Clay model's images (`ClayAssets.spriteId(model, imageIndex)`). Scrolling replaces the material's own `clay_scroll` speed, and `scroll(material, 0, 0)` stops it. Scrolling follows game time, so it pauses with the game.

Block and item models can't scroll (they're baked into the chunk once), so Clay warns if one has `clay_scroll`. Animated textures (`clay_frametime`) work everywhere, because the block atlas animates them like any vanilla texture.

### Sockets

Name an empty `socket_hand` in Blender (any name after `socket_`) and Clay gives you its position in the current pose, moving with the animation:

```java
static final ClayModel.Socket HAND = MODEL.socket("hand");

pose.pushPose();
HAND.apply(pose, animator);              // pose is now at the socket
pose.scale(0.5f, 0.5f, 0.5f);
itemRenderer.submit(...);                 // or another ClayModel, a light, ...
pose.popPose();

Vector3f tip = HAND.position(animator, new Vector3f());  // model space, e.g. for particles
```

`transform(animator, dest)` gives you the whole matrix, and `exists()` tells you whether the model has the socket. Pass `null` for the rest pose.

### Renderers

Clay has two ways of drawing a `ClayModel`, and picks one by itself:

- The **GPU renderer** uploads each model once after a resource reload and keeps it on the graphics card. Each frame it only sends the position of every part, and draws all copies of a model in one go. It uses vanilla's entity shader for the lighting, the hurt flash, fog and transparency (including Improved Transparency), so models look the same as before.
- The **CPU renderer** sends every vertex through vanilla's entity render types each frame, as Clay always did: opaque and cutout materials with `entityCutoutCull`, translucent ones with `entityTranslucentCull`, double-sided materials with the no-cull variants.

The GPU renderer is used unless a shader-pack mod (Iris, Oculus, OptiFabric) is installed or the graphics driver can't compile Clay's shader. Then Clay logs one warning and uses the CPU renderer. You can force either with the JVM argument `-Dclay.renderer=gpu` or `-Dclay.renderer=cpu` (`auto` is the default).

In game, `/clay` shows which renderer is active and why, how much GPU memory Clay uses, and every entity model drawn since the last reload with its quad count. `/clay renderer auto|gpu|cpu` switches renderers on the fly, which is handy for comparing them.

## Block shapes

`ClayShapes` turns a model into a `VoxelShape` for your block's `getShape` and `getCollisionShape`:

```java
static final VoxelShape SHAPE = ClayShapes.fromModel(Identifier.fromNamespaceAndPath("mymod", "statue"));

@Override
protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return SHAPE;
}
```

- If the model has objects named `collision` (or `collision.001` and so on), each one becomes a box around it. Make them simple boxes in Blender and hide them; they're never drawn.
- Otherwise Clay voxelizes the model at 2 pixels per voxel: every voxel the surface passes through, and everything it encloses.
- `ClayShapes.bounds(model)` is a single box around the model, and `ClayShapes.voxelize(model, cellsPerBlock)` voxelizes at another resolution.

Shapes face the way the model does in Blender (+Y in Blender is north in game). For other facings, turn them with vanilla's `Shapes.rotateHorizontal(shape)`. They're read from your mod's jar, so they work on dedicated servers, but resource packs can't change them.

## Cinematic camera

`ClayCamera` takes control of the local player's camera for a scripted shot, like a cutscene or a big ability going off. While a shot plays the camera follows your path, the player model is visible in the shot, the HUD and hand are hidden, the player can't walk around, and effects that change how the world looks (blindness, darkness, nausea and night vision) are hidden so they don't spoil the shot.

The quickest way to get something good looking is an orbit around the action:

```java
// 12 blocks out, starting at yaw 30, looking 20° down, turning 0.25° per tick, for 60 ticks
ClayCamera.play(ClayCamera.orbit(target, 12, 30, 20, 0.25f), 60);
```

For anything else, write your own path. It's a function from the time since the shot started (in ticks, with fractions between ticks so motion stays smooth) to a camera position and rotation. `Shot.lookAt` saves you the trigonometry:

```java
// a 2-second dolly from start to end, keeping the target in frame
ClayCamera.play(t -> ClayCamera.Shot.lookAt(start.lerp(end, t / 40), target), 40);
```

A shot ends when its time is up, when you call `ClayCamera.stop()`, or when the player switches worlds. It pauses along with the game, and starting a new shot replaces the current one. You can check `ClayCamera.playing()` if your own code needs to behave differently during a shot.

`orbit` pulls the camera in front of any wall between it and the target. Custom paths don't get that for free, so keep it in mind if your shot can end up inside terrain.

A `Shot` can also set the field of view (vertical, in degrees; 0 keeps the player's) and roll the picture: `Shot.lookAt(pos, target).withFov(50).withRoll(10)`.

### Keyframes

The examples below import `ClayCamera`'s nested types (`Shot`, `Clip`, `Easing`, `Transition`, ...). Rather than writing the maths yourself, give the camera a few keyframes and let a spline join them:

```java
ClayCamera.play(ClayCamera.keyframes()
    .key(0,  Shot.lookAt(a, target))
    .key(40, Shot.lookAt(b, target).withFov(50), Easing.IN_OUT_SINE)   // easing shapes the way into this key
    .key(80, Shot.lookAt(c, target).withRoll(15), Easing.OUT_CUBIC)
    .catmullRom());                                                     // or .linear(), or .bezier()
```

Catmull-Rom runs smoothly through every key. Bezier uses handles you give a key with `key(new Key(tick, shot, easing, handleIn, handleOut))`, and keys without handles behave like Catmull-Rom. Angles always turn the short way round, so going from yaw 350 to 10 doesn't spin the camera the long way. The easings are the usual set: `LINEAR`, and `IN_`, `OUT_` and `IN_OUT_` versions of `SINE`, `QUAD`, `CUBIC`, `EXPO` and `BACK`.

### Effects and sequences

Every playable thing is a `Clip`, and clips take effects:

```java
Clip intro = keys
    .letterbox(0.12f, 10, 10)    // bars 12% of the screen high, sliding in and out over 10 ticks
    .fade(20, 20, 0x000000)      // from black over the first 20 ticks, to black over the last 20
    .shake(1.5f, 6)              // up to about 1.5°, 6 times a second
    .onStart(() -> playSound())  // callbacks run on the client
    .onEnd(() -> openDoor());    // also when the shot is stopped or replaced
```

Chain clips into a sequence. Each clip after the first picks how it takes over: a hard `CUT`, a `BLEND` where the camera glides from where the last clip ended, or a `FADE` through black:

```java
ClayCamera.play(ClayCamera.sequence()
    .then(establishing)
    .then(closeUp, Transition.BLEND, 20)
    .then(ARENA.camera("intro"), Transition.FADE, 30)   // any Clip, including other sequences
    .build());
```

A `Path` you wrote yourself becomes a clip with `new ClayCamera.Timed(path, ticks)`.

### Cameras from Blender

Animate a camera in Blender and export it along with the scene (tick **Include > Cameras**, and keep the camera's action like any other animation). Then play it:

```java
static final ClayModel ARENA = ClayModel.of(Identifier.fromNamespaceAndPath("mymod", "arena"));

ClayCamera.play(ARENA.camera("intro").at(arenaCenter, 90));   // the Blender origin at arenaCenter, turned 90°
```

The camera's position, rotation (roll included) and lens come across, moving as the first animation that moves the camera or its parents. A camera that doesn't move needs a length: `ARENA.camera("still").length(60)`. The `.glb` doesn't need any meshes; a file with just cameras works.

### Starting a shot from the server

Clips made from data, which is everything above except callbacks and paths you wrote in code, can be sent to a player:

```java
ClayCamera.playFor(serverPlayer, ARENA_INTRO);   // a Clip built with keyframes(), orbit(), camera() or sequence()
ClayCamera.stopFor(serverPlayer);
```

This works on dedicated servers. Players without Clay are skipped. A server-side `ClayCamera.camera(modelId, name)` works as long as the client has the model.

The camera only moves the local player's view. If your scene also needs gameplay changes, like freezing mobs or making the player invulnerable while it plays, that part is up to your mod.

## Performance notes

Entity models drawn by the GPU renderer cost one small upload per model per frame, however many vertices they have, and every copy of the same model is a single draw call. The CPU renderer's cost grows with the vertex count of every copy, so it's the one to keep in mind if you see Clay in a profiler and `/clay` says it's in use.

Block and item models are baked once per resource reload into Fabric Renderer meshes, so they cost nothing extra per frame. Clay also merges Blender's triangle pairs back into quads and marks faces that lie on a block boundary so neighbouring blocks can cull them. Give your block a matching `VoxelShape` so it hides its neighbours properly too.

The one limit worth knowing about is the polygon budget for blocks. **Keep block models under about 220 quads.** Every placed block copies its quads into the mesh of its chunk section, and each section uploads through a fixed 98 MB buffer. At 112 bytes per quad, a section completely filled with a 220-quad block just fits. Go much heavier and the game crashes with `Cannot fit allocation ... into staging buffer` once enough copies end up in one section (a 9,000-quad block fails at around 90 copies). If you need a high-poly block, give it a simple block model and draw the detailed version with a block entity and `ClayModel`, using `.lod()` for distance.

Clay logs a warning with the model's name and quad count when a block model goes over the budget. Add `"simplify": true` to the model json and Clay merges nearby vertices until it fits (or give a number, `"simplify": 150`, for your own limit). It keeps the silhouette but loses detail, so check how it looks; a proper low-poly version from Blender always looks better.

## Known limitations

- Shape keys (morph targets) aren't supported. Clay skips them with a warning in the log.
- A Blender camera's lens is read once, so animating its focal length doesn't zoom the shot. Animate `withFov` keyframes instead.
- Only embedded PNG textures are supported, and textures can't tile because they live in an atlas (scrolling textures on entities are the exception).

## Building from source

You'll need JDK 25 and git.

```sh
./gradlew build    # builds build/libs/clay-0.1.0.jar and runs the tests
./gradlew test     # just the unit tests: the .glb reader, meshes, skinning, animation, materials, shapes and camera maths
./gradlew runClientGameTest   # opens the game: draws the test models with both renderers and compares screenshots,
                              # then checks shapes, animated textures and the camera (including a server-started shot)
```

The test models in `src/gametest` are made by `blender -b -P tools/make_test_models.py`.

## License

Clay is released under the [MIT License](LICENSE).
