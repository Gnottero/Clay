package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.render.ClayMesh;
import dev.gnottero.clay.render.ClaySkin;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RenderTest {
	private static final float[] UNIT = {0, 0, 1, 1};

	static ClayMesh showcase() throws Exception {
		Glb glb = Glb.read(Files.readAllBytes(Path.of("src/gametest/resources/assets/clay_test/clay/showcase.glb")));
		return new ClayMesh(Identifier.fromNamespaceAndPath("clay_test", "showcase"), glb, m -> UNIT);
	}

	@Test
	void sortsQuadsIntoOnePartPerMaterialKind() throws Exception {
		ClayMesh mesh = showcase();
		// textured + painted (opaque), leaf (double-sided cutout), glass (translucent), lamp (emissive)
		assertEquals(4, mesh.parts.size());
		assertEquals(4 * 6 + 1, mesh.quadCount());
		int vertices = 0;
		for (ClayMesh.Part part : mesh.parts) {
			assertEquals(vertices, part.firstVertex(), "parts are contiguous");
			vertices += part.vertexCount();
			for (int i = part.firstIndex(); i < part.firstIndex() + part.indexCount(); i++) {
				int v = mesh.indices[i];
				assertTrue(v >= part.firstVertex() && v < part.firstVertex() + part.vertexCount(), "a part's triangles use its own vertices");
			}
		}
		assertEquals(mesh.vertexCount, vertices);
		assertTrue(mesh.parts.stream().anyMatch(p -> p.kind().translucent() && p.kind().cull()));
		assertTrue(mesh.parts.stream().anyMatch(p -> !p.kind().cull() && !p.kind().translucent()));
		assertTrue(mesh.parts.stream().anyMatch(p -> p.kind().emissive()));
	}

	@Test
	void rigidVerticesFollowTheirNode() throws Exception {
		ClayMesh mesh = showcase();
		assertEquals(5, mesh.paletteSize);
		for (int v = 0; v < mesh.vertexCount; v++) {
			assertTrue(mesh.joints[v * 4] >= 0 && mesh.joints[v * 4] < mesh.paletteSize);
			assertEquals(255, mesh.weights[v * 4] & 0xFF);
			assertEquals(0, mesh.weights[v * 4 + 1]);
		}
		// at rest, the nodes' global transforms put the row's cubes from x = -2 to 2
		assertEquals(-2.4, mesh.bounds.minX, 1e-4);
		assertEquals(2.4, mesh.bounds.maxX, 1e-4);
	}

	@Test
	void blendsPaletteEntriesByNormalizedWeight() {
		Matrix4f a = new Matrix4f().translation(1, 0, 0), b = new Matrix4f().translation(0, 0, 3).rotateY(1);
		float[] palette = new float[32];
		ClaySkin.pose(new Matrix4f(), new Matrix4fc[] {a, b}, 2, palette);
		int[] joints = {0, 1, 0, 0};
		// 3:1, stored as bytes that don't sum to 255
		byte[] weights = {(byte) 150, 50, 0, 0};
		float[] skin = new float[16], out = new float[3];
		ClaySkin.blend(palette, joints, weights, 0, skin);
		ClaySkin.transformPosition(skin, 1, 2, 3, out, 0);
		Vector3f expected = a.transformPosition(new Vector3f(1, 2, 3)).mul(0.75f).add(b.transformPosition(new Vector3f(1, 2, 3)).mul(0.25f));
		assertEquals(expected.x, out[0], 1e-5);
		assertEquals(expected.y, out[1], 1e-5);
		assertEquals(expected.z, out[2], 1e-5);
	}

	@Test
	void posesThePaletteIntoCameraSpace() {
		Matrix4f pose = new Matrix4f().translation(-10, 64, 5).scale(2);
		Matrix4f node = new Matrix4f().rotationZ(0.5f);
		float[] palette = new float[16], skin = new float[16], out = new float[6];
		ClaySkin.pose(pose, new Matrix4fc[] {node}, 1, palette);
		ClaySkin.blend(palette, new int[4], new byte[] {(byte) 255, 0, 0, 0}, 0, skin);
		ClaySkin.transformPosition(skin, 1, 0, 0, out, 0);
		ClaySkin.transformNormal(skin, 0, 1, 0, out, 3);
		Vector3f p = new Matrix4f(pose).mul(node).transformPosition(new Vector3f(1, 0, 0));
		assertArrayEquals(new float[] {p.x, p.y, p.z}, new float[] {out[0], out[1], out[2]}, 1e-5f);
		Vector3f n = node.transformDirection(new Vector3f(0, 1, 0)).normalize();
		assertArrayEquals(new float[] {n.x, n.y, n.z}, new float[] {out[3], out[4], out[5]}, 1e-5f);
	}
}
