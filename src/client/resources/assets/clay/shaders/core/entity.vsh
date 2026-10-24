#version 330
#extension GL_ARB_separate_shader_objects : require

// Clay's entity vertex shader. It writes the same outputs as vanilla's core/entity.vsh, which it is paired with
// core/entity.fsh for, but its vertices come from a mesh kept on the GPU: each one follows up to four entries of a
// per-instance palette of matrices (one per node or skin joint, already posed and camera-relative) instead of arriving
// posed from the CPU. ClaySkin.java is the same math for the CPU renderer; keep them in step.

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec3 Normal;
layout(location = 4) in uvec4 Joints;
layout(location = 5) in vec4 Weights;
// per instance: palette start (in texels), packed light, packed overlay, ARGB tint
layout(location = 6) in ivec4 Instance;

uniform samplerBuffer ClayPalette;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;

mat4 palette(uint entry) {
    int texel = Instance.x + int(entry) * 4;
    return mat4(texelFetch(ClayPalette, texel), texelFetch(ClayPalette, texel + 1), texelFetch(ClayPalette, texel + 2), texelFetch(ClayPalette, texel + 3));
}

ivec2 unpack(int packed) {
    return ivec2(packed & 0xFFFF, (packed >> 16) & 0xFFFF);
}

void main() {
    vec4 weights = Weights / dot(Weights, vec4(1.0));
    mat4 skin = palette(Joints.x) * weights.x;
    if (weights.y > 0.0) skin += palette(Joints.y) * weights.y;
    if (weights.z > 0.0) skin += palette(Joints.z) * weights.z;
    if (weights.w > 0.0) skin += palette(Joints.w) * weights.w;

    vec3 position = (skin * vec4(Position, 1.0)).xyz;
    vec3 normal = normalize(mat3(skin) * Normal);
    vec4 tint = vec4((Instance.w >> 16) & 0xFF, (Instance.w >> 8) & 0xFF, Instance.w & 0xFF, (Instance.w >> 24) & 0xFF) / 255.0;
    vec4 color = Color * tint;

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color);
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    lightMapColor = sample_lightmap(Sampler2, unpack(Instance.y));
#endif

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, unpack(Instance.z), 0);
#endif

    texCoord0 = UV0;
}
