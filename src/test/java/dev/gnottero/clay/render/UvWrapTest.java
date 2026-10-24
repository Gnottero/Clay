package dev.gnottero.clay.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UvWrapTest {
	/** A triangle whose u runs from 0.5 to 2 (positions follow uv, so area and uv are easy to check). */
	@Test
	void cutsAtTileEdgesAndKeepsTheArea() {
		float[] verts = new float[3 * UvWrap.V];
		float[][] uv = {{0.5f, 0}, {2, 0}, {0.5f, 1}};
		for (int i = 0; i < 3; i++) {
			verts[i * UvWrap.V] = uv[i][0];
			verts[i * UvWrap.V + 1] = uv[i][1];
			verts[i * UvWrap.V + 6] = uv[i][0];
			verts[i * UvWrap.V + 7] = uv[i][1];
		}
		double[] area = {0};
		int[] pieces = {0};
		new UvWrap().triangle(verts, 0, 1, 2, (poly, a, b, c, d) -> {
			pieces[0]++;
			for (int i : new int[] {a, b, c, d}) {
				float u = poly[i * UvWrap.V + 6], v = poly[i * UvWrap.V + 7];
				assertTrue(u >= -1e-5 && u <= 1 + 1e-5 && v >= -1e-5 && v <= 1 + 1e-5, "uv " + u + ", " + v);
				// within a piece, uv is the position shifted by a whole tile
				assertEquals(0, (poly[i * UvWrap.V] - u) % 1, 1e-5);
			}
			area[0] += triangle(poly, a, b, c) + triangle(poly, a, c, d);
		});
		assertEquals(0.75, area[0], 1e-5, "the pieces cover the triangle exactly");
		assertTrue(pieces[0] >= 2);
	}

	private static double triangle(float[] p, int a, int b, int c) {
		Vector3f pa = new Vector3f(p[a * UvWrap.V], p[a * UvWrap.V + 1], 0);
		Vector3f ab = new Vector3f(p[b * UvWrap.V], p[b * UvWrap.V + 1], 0).sub(pa), ac = new Vector3f(p[c * UvWrap.V], p[c * UvWrap.V + 1], 0).sub(pa);
		return ab.cross(ac).length() / 2;
	}

	@Test
	void clampsAbsurdUvsInsteadOfCuttingForever() {
		float[] verts = new float[3 * UvWrap.V];
		verts[UvWrap.V + 6] = 1e9f;
		verts[2 * UvWrap.V + 7] = Float.POSITIVE_INFINITY;
		int[] pieces = {0};
		new UvWrap().triangle(verts, 0, 1, 2, (poly, a, b, c, d) -> pieces[0]++);
		assertEquals(1, pieces[0]);
	}

	@Test
	void clampsUvsTooLargeToTile() {
		float[] verts = new float[3 * UvWrap.V];
		for (int i = 0; i < 3; i++) verts[i * UvWrap.V + 6] = 2.2e9f; // far past an int, with no span at all
		int[] pieces = {0};
		new UvWrap().triangle(verts, 0, 1, 2, (poly, a, b, c, d) -> pieces[0]++);
		assertEquals(1, pieces[0]);
	}
}
