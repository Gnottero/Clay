package dev.gnottero.clay.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The vertex shader's palette math ({@code assets/clay/shaders/core/entity.vsh}) in Java, for the CPU renderer: the same
 * blend in the same order, so both renderers place every vertex in the same spot. Matrices are column-major, 16 floats each.
 */
public final class ClaySkin {
	private static final Matrix4f SCRATCH = new Matrix4f(); // render thread only

	private ClaySkin() {}

	/** Writes {@code pose * palette[i]} for every entry into {@code out}, the camera-relative palette both renderers draw from. */
	public static void pose(Matrix4fc pose, Matrix4fc[] palette, int size, float[] out) {
		for (int i = 0; i < size; i++) pose.mul(palette[i], SCRATCH).get(out, i * 16);
	}

	/** Blends vertex {@code v}'s palette entries by its weights into {@code out}: {@code Σ wᵢ/Σw · palette[jointᵢ]}. */
	public static void blend(float[] palette, int[] joints, byte[] weights, int v, float[] out) {
		float w0 = weights[v * 4] & 0xFF, w1 = weights[v * 4 + 1] & 0xFF, w2 = weights[v * 4 + 2] & 0xFF, w3 = weights[v * 4 + 3] & 0xFF;
		float sum = w0 + w1 + w2 + w3;
		w0 /= sum; w1 /= sum; w2 /= sum; w3 /= sum;
		int j0 = joints[v * 4] * 16;
		for (int k = 0; k < 16; k++) out[k] = palette[j0 + k] * w0;
		if (w1 > 0) add(palette, joints[v * 4 + 1] * 16, w1, out);
		if (w2 > 0) add(palette, joints[v * 4 + 2] * 16, w2, out);
		if (w3 > 0) add(palette, joints[v * 4 + 3] * 16, w3, out);
	}

	private static void add(float[] palette, int j, float w, float[] out) {
		for (int k = 0; k < 16; k++) out[k] += palette[j + k] * w;
	}

	/** {@code m * (x, y, z, 1)}, xyz into {@code out[o..o+2]}. */
	public static void transformPosition(float[] m, float x, float y, float z, float[] out, int o) {
		out[o] = m[0] * x + m[4] * y + m[8] * z + m[12];
		out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
		out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
	}

	/** {@code normalize(mat3(m) * (x, y, z))}, into {@code out[o..o+2]}. */
	public static void transformNormal(float[] m, float x, float y, float z, float[] out, int o) {
		float nx = m[0] * x + m[4] * y + m[8] * z, ny = m[1] * x + m[5] * y + m[9] * z, nz = m[2] * x + m[6] * y + m[10] * z;
		float inv = 1 / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		out[o] = nx * inv; out[o + 1] = ny * inv; out[o + 2] = nz * inv;
	}
}
