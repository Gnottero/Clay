"""Builds the models Clay's client game test draws, exported by Blender's own glTF exporter like a user's would be.

    blender -b -P tools/make_test_models.py

Writes to src/gametest/resources/assets/clay_test/clay/:
  showcase   a row of cubes, one per material feature: textured, vertex colors, cutout (double-sided leaf),
             translucent glass and an emissive lamp
  skinned    a column skinned to a two-bone armature, with a rigid cube spinning beside it. Clips: "bend" (the upper
             bone bends, linear), "hop" (the armature jumps, step), "sway" (the upper bone sways, bezier, exported as
             cubic spline) and "spin" (the cube turns, an object animation)
  materials  a pedestal ("paint" material, for tints and texture swaps) with a "socket_top" empty above it, a panel whose
             texture scrolls (clay_scroll), a screen with a 4-frame animated texture (clay_frametime) and a hidden
             "collision" box around the pedestal
  orb        a 528-quad sphere block model: over the chunk budget, for the warning, simplification and voxelization
"""
import math
import os

import bmesh
import bpy

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "gametest", "resources", "assets", "clay_test", "clay")


def image(name, size, pixel):
    img = bpy.data.images.new(name, size, size, alpha=True)
    px = []
    for y in range(size):
        for x in range(size):
            px.extend(pixel(x, y))
    img.pixels = px
    img.pack()
    return img


def material(name, img, alpha=None, emissive=0.0, double_sided=False, vertex_colors=False):
    """alpha: None (opaque), "mask" (texture alpha through Math: Round) or "blend" (texture alpha connected directly)."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    mat.use_backface_culling = not double_sided
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = img
    tex.interpolation = "Closest"
    color = tex.outputs["Color"]
    if vertex_colors:
        attr = nodes.new("ShaderNodeVertexColor")
        mix = nodes.new("ShaderNodeMix")
        mix.data_type = "RGBA"
        mix.blend_type = "MULTIPLY"
        mix.inputs["Factor"].default_value = 1.0
        links.new(color, mix.inputs["A"])
        links.new(attr.outputs["Color"], mix.inputs["B"])
        color = mix.outputs["Result"]
    links.new(color, bsdf.inputs["Base Color"])
    if alpha == "mask":
        rnd = nodes.new("ShaderNodeMath")
        rnd.operation = "ROUND"
        links.new(tex.outputs["Alpha"], rnd.inputs[0])
        links.new(rnd.outputs[0], bsdf.inputs["Alpha"])
    elif alpha == "blend":
        links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    if emissive:
        links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = emissive
    return mat


def cube(name, mat, at, size=0.8):
    bpy.ops.mesh.primitive_cube_add(size=size, location=(at[0], at[1], at[2] + size / 2))
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(mat)
    return obj


def export(name, **options):
    os.makedirs(OUT, exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=os.path.join(OUT, name + ".glb"), export_format="GLB", export_yup=True,
                              export_apply=True, export_image_format="AUTO", export_vertex_color="ACTIVE", **options)


def showcase():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    checker = image("checker", 8, lambda x, y: (0.9, 0.5, 0.1, 1) if (x // 2 + y // 2) % 2 else (0.2, 0.3, 0.8, 1))
    white = image("white", 4, lambda x, y: (1, 1, 1, 1))
    leaf = image("leaf", 8, lambda x, y: (0.2, 0.7, 0.2, 1) if abs(x - 3.5) + abs(y - 3.5) < 4 else (0, 0, 0, 0))
    glass = image("glass", 4, lambda x, y: (0.6, 0.85, 1.0, 0.4))
    lamp = image("lamp", 4, lambda x, y: (1.0, 0.9, 0.3, 1))

    cube("textured", material("checker", checker), (-2, 0, 0))
    painted = cube("painted", material("painted", white, vertex_colors=True), (-1, 0, 0))
    colors = painted.data.color_attributes.new("Col", "BYTE_COLOR", "CORNER")
    for i, loop in enumerate(painted.data.loops):
        colors.data[i].color = [(1, 0.2, 0.2, 1), (0.2, 1, 0.2, 1), (0.2, 0.2, 1, 1), (1, 1, 0.2, 1)][loop.vertex_index % 4]
    bpy.ops.mesh.primitive_plane_add(size=0.8, location=(0, 0, 0.4), rotation=(1.5708, 0, 0.4))
    plane = bpy.context.object
    plane.name = "leaf"
    plane.data.materials.append(material("leaf", leaf, alpha="mask", double_sided=True))
    cube("glass", material("glass", glass, alpha="blend"), (1, 0, 0))
    cube("lamp", material("lamp", lamp, emissive=1.0), (2, 0, 0))
    export("showcase")


def action(obj, name, keys, interpolation):
    """An action on `obj` from (frame, data_path, index, value) keys, pushed to its own NLA track so it exports as a clip."""
    obj.animation_data_create()
    act = bpy.data.actions.new(name)
    obj.animation_data.action = act
    for frame, path, index, value in keys:
        target = obj.pose.bones[path[0]] if isinstance(path, tuple) else obj
        attr = path[1] if isinstance(path, tuple) else path
        getattr(target, attr)[index] = value
        target.keyframe_insert(attr, index=index, frame=frame)
    for fc in act.fcurves if hasattr(act, "fcurves") else [c for layer in act.layers for strip in layer.strips for bag in strip.channelbags for c in bag.fcurves]:
        for k in fc.keyframe_points:
            k.interpolation = interpolation
    track = obj.animation_data.nla_tracks.new()
    track.name = name
    track.strips.new(name, int(keys[0][0]), act)
    obj.animation_data.action = None


def skinned():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.scene.render.fps = 20
    stone = material("stone", image("stone", 4, lambda x, y: (0.75, 0.7, 0.6, 1) if (x + y) % 2 else (0.55, 0.5, 0.45, 1)))

    arm_data = bpy.data.armatures.new("rig")
    rig = bpy.data.objects.new("rig", arm_data)
    bpy.context.scene.collection.objects.link(rig)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.mode_set(mode="EDIT")
    lower = arm_data.edit_bones.new("lower")
    lower.head, lower.tail = (0, 0, 0), (0, 0, 0.6)
    upper = arm_data.edit_bones.new("upper")
    upper.head, upper.tail = (0, 0, 0.6), (0, 0, 1.2)
    upper.parent = lower
    bpy.ops.object.mode_set(mode="OBJECT")

    mesh = bpy.data.meshes.new("column")
    bm = bmesh.new()
    rings = 8
    for i in range(rings + 1):
        z = 1.2 * i / rings
        for x, y in ((-0.15, -0.15), (0.15, -0.15), (0.15, 0.15), (-0.15, 0.15)):
            bm.verts.new((x, y, z))
    bm.verts.ensure_lookup_table()
    for i in range(rings):
        for k in range(4):
            a, b = i * 4 + k, i * 4 + (k + 1) % 4
            bm.faces.new((bm.verts[a], bm.verts[b], bm.verts[b + 4], bm.verts[a + 4]))
    bm.faces.new([bm.verts[k] for k in (3, 2, 1, 0)])
    bm.faces.new([bm.verts[rings * 4 + k] for k in range(4)])
    bm.to_mesh(mesh)
    for p in mesh.polygons:
        p.use_smooth = True
    column = bpy.data.objects.new("column", mesh)
    bpy.context.scene.collection.objects.link(column)
    column.data.materials.append(stone)
    column.parent = rig
    groups = {name: column.vertex_groups.new(name=name) for name in ("lower", "upper")}
    for v in mesh.vertices:
        w = min(1.0, max(0.0, (v.co.z - 0.45) / 0.3))  # blend across the joint at 0.6
        if w < 1:
            groups["lower"].add([v.index], 1 - w, "REPLACE")
        if w > 0:
            groups["upper"].add([v.index], w, "REPLACE")
    column.modifiers.new("rig", "ARMATURE").object = rig

    spinner = cube("spinner", stone, (0.8, 0, 0), size=0.3)

    action(rig, "bend", [(0, ("upper", "rotation_quaternion"), 1, 0), (10, ("upper", "rotation_quaternion"), 1, math.sin(math.radians(30))),
                         (20, ("upper", "rotation_quaternion"), 1, 0)], "LINEAR")
    action(rig, "hop", [(0, ("lower", "location"), 1, 0), (10, ("lower", "location"), 1, 0.3), (20, ("lower", "location"), 1, 0)], "CONSTANT")
    action(rig, "sway", [(0, ("upper", "rotation_quaternion"), 3, 0), (10, ("upper", "rotation_quaternion"), 3, math.sin(math.radians(20))),
                         (20, ("upper", "rotation_quaternion"), 3, 0)], "BEZIER")
    action(spinner, "spin", [(0, "rotation_euler", 2, 0), (20, "rotation_euler", 2, math.pi)], "LINEAR")
    export("skinned", export_animation_mode="ACTIONS", export_force_sampling=False, export_def_bones=True)


def materials():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    paint = material("paint", image("paint", 4, lambda x, y: (1, 1, 1, 1) if (x + y) % 2 else (0.8, 0.8, 0.8, 1)))
    cube("pedestal", paint, (0, 0, 0), size=0.6)

    arrows = image("arrows", 8, lambda x, y: (0.95, 0.85, 0.2, 1) if abs(x - 4) <= 3 - y % 4 else (0.15, 0.15, 0.3, 1))
    scroll = material("scroll", arrows)
    scroll["clay_scroll"] = [0.5, 0.0]
    bpy.ops.mesh.primitive_plane_add(size=0.8, location=(1, 0, 0.4), rotation=(-math.pi / 2, 0, 0))  # facing +Y: north in game, towards the test camera
    panel = bpy.context.object
    panel.name = "panel"
    panel.data.materials.append(scroll)

    # four frames stacked vertically, like a vanilla animated texture: red, green, blue, white
    frames = [(1, 0.2, 0.2), (0.2, 1, 0.2), (0.2, 0.2, 1), (1, 1, 1)]
    strip = bpy.data.images.new("strip", 4, 16, alpha=True)
    strip.pixels = [c for y in range(16) for x in range(4) for c in (*frames[3 - y // 4], 1)]
    strip.pack()
    screen = material("screen", strip)
    screen["clay_frametime"] = 5
    cube("screen", screen, (-1, 0, 0), size=0.6)

    bpy.ops.object.empty_add(location=(0, 0, 0.9))
    bpy.context.object.name = "socket_top"
    collision = cube("collision", paint, (0, 0, 0), size=0.7)
    collision.hide_set(True)
    collision.hide_render = True
    export("materials", export_extras=True)


def orb():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=24, ring_count=24, radius=0.45, location=(0, 0, 0.5))
    bpy.context.object.name = "orb"
    bpy.context.object.data.materials.append(material("stone", image("stone", 4, lambda x, y: (0.6, 0.6, 0.65, 1))))
    export("orb")


showcase()
skinned()
materials()
orb()
