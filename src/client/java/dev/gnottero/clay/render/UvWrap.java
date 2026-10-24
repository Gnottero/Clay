package dev.gnottero.clay.render;

/**
 * Wraps a scrolling texture on the CPU the way the GPU renderer's fragment shader does with {@code fract()}: each triangle
 * is cut along the lines where its UVs cross a whole number, and every piece gets UVs inside 0-1 again. The pieces cover
 * the same pixels as the uncut triangle, because the rasterizer interpolates linearly between vertices either way.
 * <p>
 * Vertices are {@link #V} floats: x y z, normal xyz, u v, color r g b a.
 */
final class UvWrap {
	static final int V = 12;
	private static final int U = 6;
	/** A triangle clipped by four lines has at most 7 corners. */
	private static final int MAX = 8;
	/** Tiles one triangle may span before its UVs are clamped instead: real models repeat a texture a few times at most. */
	private static final int MAX_TILES = 64;
	/** Largest UV cut into tiles; beyond it a float can't hold a position within a tile. */
	private static final float MAX_UV = 1e6f;

	interface Emit {
		/** Called with 3 or 4 vertices (a quad, possibly repeating its last vertex) from {@code poly}, UVs within 0-1. */
		void quad(float[] poly, int a, int b, int c, int d);
	}

	private final float[] poly = new float[MAX * V], tmp = new float[MAX * V], tri = new float[3 * V];

	/** Cuts triangle (a, b, c) of {@code verts} into pieces that each sit in one UV tile, shifted back into 0-1. */
	void triangle(float[] verts, int a, int b, int c, Emit emit) {
		System.arraycopy(verts, a * V, tri, 0, V);
		System.arraycopy(verts, b * V, tri, V, V);
		System.arraycopy(verts, c * V, tri, 2 * V, V);
		float minU = min(tri, U), maxU = max(tri, U), minV = min(tri, U + 1), maxV = max(tri, U + 1);
		// huge UVs also go to the clamp: past about 2^31 the tile numbers below would overflow, and floats lose the fraction anyway
		if (!(Math.max(Math.abs(minU), Math.abs(maxU)) <= MAX_UV && Math.max(Math.abs(minV), Math.abs(maxV)) <= MAX_UV)
			|| (maxU - minU + 1) * (maxV - minV + 1) > MAX_TILES) {
			// absurd UVs (a broken or hostile file): clamp rather than cut into millions of pieces every frame
			for (int i = 0; i < 3; i++) {
				tri[i * V + U] = Float.isFinite(tri[i * V + U]) ? Math.clamp(tri[i * V + U], 0f, 1f) : 0;
				tri[i * V + U + 1] = Float.isFinite(tri[i * V + U + 1]) ? Math.clamp(tri[i * V + U + 1], 0f, 1f) : 0;
			}
			emit.quad(tri, 0, 1, 2, 2);
			return;
		}
		int u0 = (int) Math.floor(minU), u1 = (int) Math.ceil(maxU) - 1;
		int v0 = (int) Math.floor(minV), v1 = (int) Math.ceil(maxV) - 1;
		for (int cu = u0; cu <= Math.max(u0, u1); cu++) {
			for (int cv = v0; cv <= Math.max(v0, v1); cv++) {
				System.arraycopy(tri, 0, poly, 0, 3 * V);
				int n = 3;
				n = clip(poly, n, U, cu, false);
				n = clip(poly, n, U, cu + 1, true);
				n = clip(poly, n, U + 1, cv, false);
				n = clip(poly, n, U + 1, cv + 1, true);
				if (n < 3) continue;
				for (int i = 0; i < n; i++) {
					poly[i * V + U] -= cu;
					poly[i * V + U + 1] -= cv;
				}
				for (int i = 1; i + 1 < n; i += 2) emit.quad(poly, 0, i, i + 1, Math.min(i + 2, n - 1));
			}
		}
	}

	/** Sutherland-Hodgman against {@code value >= limit} (or {@code <=} when {@code below}) on component {@code axis}. */
	private int clip(float[] p, int n, int axis, float limit, boolean below) {
		int out = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			float di = side(p[i * V + axis], limit, below), dj = side(p[j * V + axis], limit, below);
			if (di >= 0) System.arraycopy(p, i * V, tmp, out++ * V, V);
			if (di >= 0 != dj >= 0) {
				float t = di / (di - dj);
				for (int k = 0; k < V; k++) tmp[out * V + k] = p[i * V + k] + (p[j * V + k] - p[i * V + k]) * t;
				tmp[out * V + axis] = limit; // exactly on the line, so the shift lands on 0 or 1
				out++;
			}
		}
		System.arraycopy(tmp, 0, p, 0, out * V);
		return out;
	}

	private static float side(float value, float limit, boolean below) {
		return below ? limit - value : value - limit;
	}

	private static float min(float[] t, int c) {
		return Math.min(t[c], Math.min(t[V + c], t[2 * V + c]));
	}

	private static float max(float[] t, int c) {
		return Math.max(t[c], Math.max(t[V + c], t[2 * V + c]));
	}
}
