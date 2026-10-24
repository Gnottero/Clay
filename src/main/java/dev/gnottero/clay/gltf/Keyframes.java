package dev.gnottero.clay.gltf;

/** glTF animation sampling (LINEAR, STEP, CUBICSPLINE) on flat float arrays, without allocating. */
public final class Keyframes {
	private Keyframes() {}

	/** Writes {@code channel}'s value at {@code time} into {@code out[o..]}; before the first key or after the last it holds still. */
	public static void sample(Glb.Channel channel, float time, float[] out, int o) {
		float[] times = channel.times(), v = channel.values();
		int n = channel.path().components;
		boolean cubic = channel.interpolation() == Glb.Interpolation.CUBICSPLINE;
		int stride = cubic ? n * 3 : n, value = cubic ? n : 0; // a cubic key is in-tangent, value, out-tangent
		int last = times.length - 1;
		if (time <= times[0] || last == 0) {
			copy(v, value, n, out, o, channel.path());
			return;
		}
		if (time >= times[last]) {
			copy(v, last * stride + value, n, out, o, channel.path());
			return;
		}
		int k = floorKey(times, time);
		float dt = times[k + 1] - times[k], u = dt > 0 ? (time - times[k]) / dt : 0;
		switch (channel.interpolation()) {
			case STEP -> copy(v, k * stride, n, out, o, channel.path());
			case LINEAR -> {
				if (channel.path() == Glb.Path.ROTATION) slerp(v, k * stride, v, (k + 1) * stride, u, out, o);
				else lerp(v, k * stride, v, (k + 1) * stride, u, n, out, o);
			}
			case CUBICSPLINE -> {
				float u2 = u * u, u3 = u2 * u;
				float h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
				int a = k * stride, b = (k + 1) * stride;
				for (int c = 0; c < n; c++) {
					out[o + c] = h00 * v[a + n + c] + h10 * dt * v[a + 2 * n + c] + h01 * v[b + n + c] + h11 * dt * v[b + c];
				}
				if (channel.path() == Glb.Path.ROTATION) normalize(out, o);
			}
		}
	}

	/** The last key at or before {@code time}, given {@code times[0] < time < times[last]}. */
	private static int floorKey(float[] times, float time) {
		int lo = 0, hi = times.length - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (times[mid] <= time) lo = mid;
			else hi = mid;
		}
		return lo;
	}

	private static void copy(float[] v, int from, int n, float[] out, int o, Glb.Path path) {
		System.arraycopy(v, from, out, o, n);
		if (path == Glb.Path.ROTATION) normalize(out, o);
	}

	public static void lerp(float[] a, int ao, float[] b, int bo, float u, int n, float[] out, int o) {
		for (int c = 0; c < n; c++) out[o + c] = a[ao + c] + (b[bo + c] - a[ao + c]) * u;
	}

	/** Spherical interpolation between quaternions (xyzw), along the shorter arc. */
	public static void slerp(float[] a, int ao, float[] b, int bo, float u, float[] out, int o) {
		float dot = a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3];
		float sign = dot < 0 ? -1 : 1;
		dot *= sign;
		float wa, wb;
		if (dot > 0.9995f) { // nearly parallel: lerp is exact enough and avoids dividing by sin(0)
			wa = 1 - u;
			wb = u * sign;
		} else {
			float theta = (float) Math.acos(dot), sin = (float) Math.sin(theta);
			wa = (float) Math.sin((1 - u) * theta) / sin;
			wb = sign * (float) Math.sin(u * theta) / sin;
		}
		for (int c = 0; c < 4; c++) out[o + c] = wa * a[ao + c] + wb * b[bo + c];
		normalize(out, o);
	}

	private static void normalize(float[] q, int o) {
		float len = (float) Math.sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3]);
		if (len == 0) {
			q[o + 3] = 1;
			return;
		}
		for (int c = 0; c < 4; c++) q[o + c] /= len;
	}
}
