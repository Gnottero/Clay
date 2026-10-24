package dev.gnottero.clay.render;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Quads;
import dev.gnottero.clay.gltf.Rig;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A .glb baked for entity rendering, shared by both renderers. Every quad of every node sits in one vertex array, sorted
 * into one {@link Part} per material kind. Each vertex follows up to four entries of a {@link Rig}'s palette by weight:
 * its node, or the joints of its skin. Its position and normal are in the space those entries map from. Quads keep 4 consecutive vertices so the CPU renderer
 * can stream them as quads; the GPU renderer draws them through {@link #indices} as triangles.
 */
public final class ClayMesh {
	/** How a part is drawn: blended or cutout, culled or double-sided, lit or full-bright. */
	public record Kind(boolean translucent, boolean cull, boolean emissive) {}

	/** A run of quads drawn the same way: vertices {@code [firstVertex, +vertexCount)}, triangles {@code [firstIndex, +indexCount)}. */
	public record Part(ClayMesh mesh, Kind kind, int firstVertex, int vertexCount, int firstIndex, int indexCount) {}

	public final Identifier id;
	public final int vertexCount, paletteSize;
	/** Per vertex: xyz, normal xyz, atlas uv, ARGB color. */
	public final float[] positions, normals, uvs;
	public final int[] colors;
	/** Per vertex: 4 palette indices and their weights (0-255, normalized when used, like the vertex shader's UNORM input). */
	public final int[] joints;
	public final byte[] weights;
	public final int[] indices;
	public final List<Part> parts = new ArrayList<>();
	public final AABB bounds;
	/** The GPU renderer's buffers, uploaded on first draw. */
	GpuRenderer.@Nullable Buffers gpu;

	/** Quads of one primitive, before they are sorted into parts; {@code skinOffset} >= 0 when its vertices follow a skin. */
	private record Piece(Kind kind, int node, int material, List<Quads.Quad> quads, Glb.Primitive primitive, int skinOffset) {}

	/**
	 * @param uvRect for a material index (-1 = default), the {u0, v0, u1, v1} atlas rectangle its 0-1 UVs map into
	 */
	public ClayMesh(Identifier id, Glb glb, IntFunction<float[]> uvRect) {
		this.id = id;
		Rig rig = new Rig(glb);
		this.paletteSize = rig.paletteSize;
		Map<Kind, List<Piece>> byKind = new LinkedHashMap<>();
		int vertices = 0, triangles = 0;
		for (int n = 0; n < glb.nodes.size(); n++) {
			Glb.Node node = glb.nodes.get(n);
			for (Glb.Primitive primitive : node.primitives()) {
				Glb.Material mat = glb.material(primitive.material());
				Kind kind = new Kind(mat.alphaMode() == Glb.AlphaMode.BLEND, !mat.doubleSided(), mat.emissive());
				List<Quads.Quad> quads = Quads.build(glb, primitive, new Matrix4f());
				int skinOffset = node.skin() >= 0 && primitive.joints() != null ? rig.skinOffset[node.skin()] : -1;
				byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(new Piece(kind, n, primitive.material(), quads, primitive, skinOffset));
				for (Quads.Quad quad : quads) {
					vertices += 4;
					triangles += degenerate(quad.v()) ? 1 : 2;
				}
			}
		}

		vertexCount = vertices;
		positions = new float[vertices * 3];
		normals = new float[vertices * 3];
		uvs = new float[vertices * 2];
		colors = new int[vertices];
		joints = new int[vertices * 4];
		weights = new byte[vertices * 4];
		indices = new int[triangles * 3];
		int v = 0, i = 0;
		for (List<Piece> pieces : byKind.values()) {
			int firstVertex = v, firstIndex = i;
			for (Piece piece : pieces) {
				float[] rect = uvRect.apply(piece.material);
				for (Quads.Quad quad : piece.quads) {
					Quads.wrapUvs(quad.v());
					for (int k = 0; k < 4; k++) {
						put(v + k, quad.v(), k * Quads.STRIDE, rect, piece.node);
						if (piece.skinOffset >= 0) skin(v + k, piece.primitive, quad.source()[k], piece.skinOffset);
					}
					indices[i++] = v; indices[i++] = v + 1; indices[i++] = v + 2;
					if (!degenerate(quad.v())) { indices[i++] = v; indices[i++] = v + 2; indices[i++] = v + 3; }
					v += 4;
				}
			}
			parts.add(new Part(this, pieces.getFirst().kind, firstVertex, v - firstVertex, firstIndex, i - firstIndex));
		}
		bounds = restBounds(rig);
	}

	/** Bounds of every vertex placed by the rest-pose palette, the same way the renderers place them. */
	private AABB restBounds(Rig rig) {
		if (vertexCount == 0) return new AABB(0, 0, 0, 0, 0, 0);
		float[] palette = new float[paletteSize * 16], skin = new float[16], p = new float[3];
		ClaySkin.pose(new Matrix4f(), rig.build(null), paletteSize, palette);
		Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), at = new Vector3f();
		for (int v = 0; v < vertexCount; v++) {
			ClaySkin.blend(palette, joints, weights, v, skin);
			ClaySkin.transformPosition(skin, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2], p, 0);
			min.min(at.set(p));
			max.max(at);
		}
		return new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
	}

	/** Ties vertex {@code v} to up to four joints of a skin, with its glTF weights quantized to bytes as the GPU reads them. */
	private void skin(int v, Glb.Primitive primitive, int source, int skinOffset) {
		int total = 0;
		for (int k = 0; k < 4; k++) {
			int w = Math.round(Math.clamp(primitive.weights()[source * 4 + k], 0f, 1f) * 255);
			joints[v * 4 + k] = skinOffset + primitive.joints()[source * 4 + k];
			weights[v * 4 + k] = (byte) w;
			total += w;
		}
		if (total == 0) { // unweighted: follow the first joint rather than collapse to the origin
			weights[v * 4] = (byte) 255;
		}
	}

	private void put(int v, float[] q, int o, float[] rect, int node) {
		System.arraycopy(q, o, positions, v * 3, 3);
		System.arraycopy(q, o + 3, normals, v * 3, 3);
		uvs[v * 2] = rect[0] + (rect[2] - rect[0]) * Math.clamp(q[o + 6], 0f, 1f);
		uvs[v * 2 + 1] = rect[1] + (rect[3] - rect[1]) * Math.clamp(q[o + 7], 0f, 1f);
		colors[v] = argb(q[o + 11], q[o + 8], q[o + 9], q[o + 10]);
		joints[v * 4] = node;
		weights[v * 4] = (byte) 255;
	}

	/** Lone triangles are stored as quads whose last vertex repeats the third. */
	private static boolean degenerate(float[] q) {
		for (int c = 0; c < 3; c++) if (q[2 * Quads.STRIDE + c] != q[3 * Quads.STRIDE + c]) return false;
		return true;
	}

	private static int argb(float a, float r, float g, float b) {
		return Math.round(Math.clamp(a, 0f, 1f) * 255) << 24 | Math.round(Math.clamp(r, 0f, 1f) * 255) << 16
			| Math.round(Math.clamp(g, 0f, 1f) * 255) << 8 | Math.round(Math.clamp(b, 0f, 1f) * 255);
	}

	public boolean isOnGpu() {
		return gpu != null;
	}

	public int quadCount() {
		return vertexCount / 4;
	}

	/** Frees the GPU copy; the mesh is unusable for the GPU renderer afterwards. */
	public void close() {
		if (gpu != null) gpu.close();
		gpu = null;
	}
}
