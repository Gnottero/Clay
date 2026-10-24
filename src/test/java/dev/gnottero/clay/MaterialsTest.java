package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Quads;
import dev.gnottero.clay.gltf.Rig;
import dev.gnottero.clay.render.ClayMesh;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialsTest {
	static Glb glb(String name) throws Exception {
		return Glb.read(Files.readAllBytes(Path.of("src/gametest/resources/assets/clay_test/clay/" + name + ".glb")));
	}

	private static ClayMesh mesh(Glb glb) {
		// each material's image at its own spot in a pretend atlas
		return new ClayMesh(Identifier.fromNamespaceAndPath("clay_test", "m"), glb, m -> new float[] {m + 1, 0, m + 1.5f, 0.5f});
	}

	@Test
	void readsScrollAndAnimatedTextureProperties() throws Exception {
		Glb glb = glb("materials");
		Glb.Material scroll = glb.materials.stream().filter(m -> m.name().equals("scroll")).findFirst().orElseThrow();
		assertArrayEquals(new float[] {0.5f, 0}, scroll.scroll());
		Glb.Material screen = glb.materials.stream().filter(m -> m.name().equals("screen")).findFirst().orElseThrow();
		assertEquals(5, screen.frametime());
		assertNull(glb.materials.getFirst().scroll());
	}

	@Test
	void materialTableScrollsAndWrapsOnlyScrollingMaterials() throws Exception {
		ClayMesh mesh = mesh(glb("materials"));
		float[] table = new float[mesh.slots * ClayMesh.MATERIAL_FLOATS];
		mesh.defaultMaterials(2.5, table);
		int scroll = mesh.slot("scroll") * ClayMesh.MATERIAL_FLOATS, paint = mesh.slot("paint") * ClayMesh.MATERIAL_FLOATS;
		assertEquals(0.25f, table[scroll + 4], 1e-6, "0.5 widths/s for 2.5 s, wrapped");
		assertEquals(1, table[scroll + 6]);
		assertEquals(0, table[paint + 6]);
		assertEquals(mesh.rects[mesh.slot("paint") * 4], table[paint]);
	}

	@Test
	void collisionObjectsAreNotDrawn() throws Exception {
		Glb glb = glb("materials");
		ClayMesh mesh = mesh(glb);
		// pedestal and screen cubes and the panel: the collision cube is left out
		assertEquals(6 + 6 + 1, mesh.quadCount());
		for (int v = 0; v < mesh.vertexCount; v++) assertFalse(glb.nodes.get(mesh.joints[v * 4]).name().startsWith("collision"));
	}

	@Test
	void socketsAreNodes() throws Exception {
		Rig rig = new Rig(glb("materials"));
		int socket = rig.node("socket_top");
		assertTrue(socket >= 0);
		Vector3f at = rig.build(null)[socket].getTranslation(new Vector3f());
		assertEquals(0.9f, at.y, 1e-5, "Blender z is glTF y");
	}

	@Test
	void collisionObjectBecomesABox() throws Exception {
		List<double[]> boxes = ClayShapes.collisionBoxes(glb("materials"));
		assertEquals(1, boxes.size());
		assertArrayEquals(new double[] {0.15, 0, 0.15, 0.85, 0.7, 0.85}, boxes.getFirst(), 1e-5);
	}

	@Test
	void voxelizesASolidSphere() throws Exception {
		List<double[]> boxes = ClayShapes.voxels(glb("orb"), 16);
		double volume = 0;
		for (double[] b : boxes) volume += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
		double sphere = 4 / 3.0 * Math.PI * Math.pow(0.45, 3);
		// surface cells round outwards, so a bit more than the sphere, and well short of its bounding cube (0.73)
		assertTrue(volume > sphere && volume < sphere * 1.5, "volume " + volume);
		double[] bounds = ClayShapes.bounds(glb("orb"), false);
		assertEquals(0.05, bounds[0], 1e-3);
		assertEquals(0.95, bounds[4], 1e-3);
	}

	@Test
	void simplifiesUnderTheBudget() throws Exception {
		Glb glb = glb("orb");
		List<Quads.Quad> quads = new ArrayList<>();
		for (Glb.Node node : glb.nodes) for (Glb.Primitive p : node.primitives()) quads.addAll(Quads.build(glb, p, node.global()));
		assertTrue(quads.size() > 220, "the test orb is over budget: " + quads.size());
		List<Quads.Quad> simple = Quads.simplify(quads, 220);
		assertTrue(simple.size() <= 220 && simple.size() > 40, "simplified to " + simple.size());
		// clustering moves vertices inwards a little at most: the silhouette stays
		float maxRadius = 0;
		for (Quads.Quad q : simple) for (int i = 0; i < 4; i++) maxRadius = Math.max(maxRadius, new Vector3f(q.x(i), q.y(i) - 0.5f, q.z(i)).length());
		assertEquals(0.45, maxRadius, 0.06);
	}

	@Test
	void hostileSkinsAreBounded() {
		String joints = "[" + "0,".repeat(70_000) + "0]";
		String json = "{\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{}],\"skins\":[{\"joints\":" + joints + "}]}";
		assertThrows(java.io.IOException.class, () -> Glb.read(GlbTest.glb(json)));
	}
}
