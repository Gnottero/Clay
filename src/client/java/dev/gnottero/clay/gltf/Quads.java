package dev.gnottero.clay.gltf;

import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns glTF triangles into Minecraft-style quads. Blender exports each quad face as the triangle
 * pair (a,b,c),(a,c,d); coplanar convex pairs are merged back into one quad, the rest become
 * degenerate quads (4th vertex = 3rd). This cuts vertex count by ~1/3 on quad-heavy meshes.
 */
public final class Quads {
	/** Floats per vertex: x y z nx ny nz u v r g b a. */
	public static final int STRIDE = 12;

	/** {@code v} holds 4 vertices of {@link #STRIDE} floats; {@code source} their indices in the primitive (for skin weights). */
	public record Quad(float[] v, int material, int[] source) {
		public Quad(float[] v, int material) {
			this(v, material, new int[4]);
		}

		public float x(int i) { return v[i * STRIDE]; }
		public float y(int i) { return v[i * STRIDE + 1]; }
		public float z(int i) { return v[i * STRIDE + 2]; }
	}

	private Quads() {}

	public static List<Quad> build(Glb glb, Glb.Primitive p, Matrix4fc transform) {
		Matrix3f normalMatrix = transform.normal(new Matrix3f());
		boolean flip = transform.determinant() < 0; // mirrored transforms reverse winding
		float[] color = glb.material(p.material()).baseColor();

		int n = p.vertexCount();
		float[] verts = new float[n * STRIDE];
		Vector3f tmp = new Vector3f();
		for (int i = 0; i < n; i++) {
			int o = i * STRIDE;
			transform.transformPosition(tmp.set(p.positions()[i * 3], p.positions()[i * 3 + 1], p.positions()[i * 3 + 2]));
			verts[o] = tmp.x; verts[o + 1] = tmp.y; verts[o + 2] = tmp.z;
			if (p.normals() != null) {
				normalMatrix.transform(tmp.set(p.normals()[i * 3], p.normals()[i * 3 + 1], p.normals()[i * 3 + 2])).normalize();
				verts[o + 3] = tmp.x; verts[o + 4] = tmp.y; verts[o + 5] = tmp.z;
			}
			if (p.uvs() != null) {
				verts[o + 6] = p.uvs()[i * 2];
				verts[o + 7] = p.uvs()[i * 2 + 1];
			}
			for (int c = 0; c < 4; c++) verts[o + 8 + c] = color[c] * (p.colors() != null ? p.colors()[i * 4 + c] : 1);
		}

		int[] idx = p.indices().clone();
		if (flip) for (int t = 0; t + 2 < idx.length; t += 3) { int b = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = b; }

		List<Quad> out = new ArrayList<>(idx.length / 6 + 1);
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = idx[t], b = idx[t + 1], c = idx[t + 2];
			int[] quad = t + 5 < idx.length ? merge(verts, a, b, c, idx[t + 3], idx[t + 4], idx[t + 5]) : null;
			if (quad != null) t += 3;
			else quad = new int[] {a, b, c, c};

			float[] v = new float[4 * STRIDE];
			for (int i = 0; i < 4; i++) System.arraycopy(verts, quad[i] * STRIDE, v, i * STRIDE, STRIDE);
			if (p.normals() == null) flatNormals(v);
			out.add(new Quad(v, p.material(), quad));
		}
		return out;
	}

	/** Returns the merged quad's 4 vertex indices if triangle (d,e,f) completes (a,b,c) into a flat convex quad. */
	public static int[] merge(float[] verts, int a, int b, int c, int d, int e, int f) {
		int[] t1 = {a, b, c}, t2 = {d, e, f};
		for (int r = 0; r < 3; r++) {
			// rotate t1 so the candidate shared edge is (s1, s2) and p is the opposite vertex
			int p = t1[r], s1 = t1[(r + 1) % 3], s2 = t1[(r + 2) % 3];
			for (int k = 0; k < 3; k++) {
				// consistently wound neighbours traverse the shared edge in reverse: s2 -> s1
				if (t2[k] == s2 && t2[(k + 1) % 3] == s1) {
					int q = t2[(k + 2) % 3];
					int[] quad = {p, s1, q, s2};
					return isFlatConvex(verts, quad) ? quad : null;
				}
			}
		}
		return null;
	}

	private static boolean isFlatConvex(float[] verts, int[] quad) {
		Vector3f n0 = faceNormal(verts, quad[0], quad[1], quad[3]);
		Vector3f n1 = faceNormal(verts, quad[1], quad[2], quad[3]);
		if (n0.lengthSquared() == 0 || n1.lengthSquared() == 0 || n0.dot(n1) < 0.999f) return false;
		for (int i = 0; i < 4; i++) {
			Vector3f corner = faceNormal(verts, quad[i], quad[(i + 1) % 4], quad[(i + 2) % 4]);
			if (corner.dot(n0) <= 0) return false;
		}
		return true;
	}

	private static Vector3f faceNormal(float[] verts, int a, int b, int c) {
		Vector3f pa = pos(verts, a), ab = pos(verts, b).sub(pa), ac = pos(verts, c).sub(pa);
		Vector3f n = ab.cross(ac);
		return n.lengthSquared() > 1e-20f ? n.normalize() : n.zero();
	}

	private static Vector3f pos(float[] verts, int i) {
		return new Vector3f(verts[i * STRIDE], verts[i * STRIDE + 1], verts[i * STRIDE + 2]);
	}

	private static void flatNormals(float[] v) {
		Vector3f n = faceNormal(v, 0, 1, 2);
		for (int i = 0; i < 4; i++) {
			v[i * STRIDE + 3] = n.x; v[i * STRIDE + 4] = n.y; v[i * STRIDE + 5] = n.z;
		}
	}

	/** Shifts UVs that tile past 0..1 back into the unit square when the whole quad sits in one tile. */
	public static boolean wrapUvs(float[] v) {
		float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			minU = Math.min(minU, v[i * STRIDE + 6]);
			minV = Math.min(minV, v[i * STRIDE + 7]);
		}
		float du = (float) Math.floor(minU), dv = (float) Math.floor(minV);
		boolean fits = true;
		for (int i = 0; i < 4; i++) {
			float u = v[i * STRIDE + 6] -= du, vv = v[i * STRIDE + 7] -= dv;
			if (u > 1.0001f || vv > 1.0001f) fits = false;
		}
		return fits;
	}
}
