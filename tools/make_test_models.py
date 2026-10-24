"""Builds the models Clay's client game test draws, exported by Blender's own glTF exporter like a user's would be.

    blender -b -P tools/make_test_models.py

Writes to src/gametest/resources/assets/clay_test/clay/:
  showcase   a row of cubes, one per material feature: textured, vertex colors, cutout (double-sided leaf),
             translucent glass and an emissive lamp
"""
import os

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


def export(name):
    os.makedirs(OUT, exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=os.path.join(OUT, name + ".glb"), export_format="GLB", export_yup=True,
                              export_apply=True, export_image_format="AUTO", export_vertex_color="ACTIVE")


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


showcase()
