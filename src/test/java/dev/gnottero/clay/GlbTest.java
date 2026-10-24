package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Quads;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlbTest {
	private static Glb sample(String name) throws Exception {
		return Glb.read(Files.readAllBytes(Path.of("src/test/resources", name)));
	}

	private static Glb.Primitive core(Glb glb) {
		return glb.nodes.stream().filter(n -> n.name().equals("core")).findFirst().orElseThrow().primitives().getFirst();
	}

	@Test
	void parsesBlenderExport() throws Exception {
		Glb glb = sample("null_greatsword.glb");
		assertEquals(8, glb.images.size()); // the exporter drops the unused wood and starfield materials
		assertTrue(glb.images.stream().allMatch(png -> png != null && png[1] == 'P' && png[2] == 'N' && png[3] == 'G'));

		Glb.Primitive core = core(glb);
		assertNotNull(core.normals());
		assertNotNull(core.uvs());
		Glb.Material voidCore = glb.material(core.material());
		assertEquals(Glb.AlphaMode.OPAQUE, voidCore.alphaMode());
		assertTrue(voidCore.doubleSided());
		assertTrue(voidCore.emissive());
	}

	@Test
	void mergesTrianglePairsIntoQuads() throws Exception {
		// the greatsword's core is one Blender quad, exported as a triangle pair
		Glb glb = sample("null_greatsword.glb");
		Glb.Primitive core = core(glb);
		assertEquals(2, core.indices().length / 3);
		assertEquals(1, Quads.build(glb, core, new Matrix4f()).size());
	}

	@Test
	void rejectsNonPlanarMerge() {
		// square in the XZ plane split as (0,1,2),(0,2,3) -> one quad
		float[] flat = verts(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
		assertArrayEquals(new int[] {1, 2, 3, 0}, Quads.merge(flat, 0, 1, 2, 0, 2, 3));
		// lift one corner: no longer coplanar
		float[] bent = verts(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0.5f, 1);
		assertNull(Quads.merge(bent, 0, 1, 2, 0, 2, 3));
	}

	@Test
	void quadsOnBlockFacesGetACullFace() {
		// floor square facing down -> DOWN; the same square lifted inside the block -> no cull face
		assertEquals(Direction.DOWN, ClayGeometry.cullFace(new Quads.Quad(verts(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1), 0)));
		assertNull(ClayGeometry.cullFace(new Quads.Quad(verts(0, 0.5f, 0, 1, 0.5f, 0, 1, 0.5f, 1, 0, 0.5f, 1), 0)));
	}

	private static float[] verts(float... xyz) {
		float[] v = new float[xyz.length / 3 * Quads.STRIDE];
		for (int i = 0; i < xyz.length / 3; i++) System.arraycopy(xyz, i * 3, v, i * Quads.STRIDE, 3);
		return v;
	}

	/** A minimal .glb around the given JSON, with an empty BIN chunk. */
	static byte[] glb(String json) {
		byte[] j = (json + "    ".substring(json.length() % 4 == 0 ? 4 : json.length() % 4)).getBytes();
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(12 + 8 + j.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x46546C67).putInt(2).putInt(b.capacity()).putInt(j.length).putInt(0x4E4F534A).put(j);
		return b.array();
	}

	/** Appends a zeroed BIN chunk of the given size. */
	private static byte[] withBin(byte[] glb, int size) {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(glb.length + 8 + size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.put(glb).putInt(size).putInt(0x004E4942);
		return b.array();
	}

	@Test
	void rejectsHostileFiles() {
		String cycle = "{\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"children\":[0]}]}";
		String huge = "{\"buffers\":[{}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":0}],"
			+ "\"accessors\":[{\"bufferView\":0,\"count\":2000000000,\"type\":\"VEC3\",\"componentType\":5126}],"
			+ "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}]}";
		assertThrows(java.io.IOException.class, () -> Glb.read(glb(cycle)));
		assertThrows(java.io.IOException.class, () -> Glb.read(glb(huge)));
		// one 4 MB accessor shared by 20 primitives: each fits on its own, only the per-file budget stops the total
		int verts = 350_000;
		String reused = "{\"buffers\":[{}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + verts * 12 + "}],"
			+ "\"accessors\":[{\"bufferView\":0,\"count\":" + verts + ",\"type\":\"VEC3\",\"componentType\":5126}],"
			+ "\"meshes\":[{\"primitives\":[" + String.join(",", java.util.Collections.nCopies(20, "{\"attributes\":{\"POSITION\":0}}"))
			+ "]}],\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}]}";
		assertThrows(java.io.IOException.class, () -> Glb.read(withBin(glb(reused), verts * 12)));
		// one 2 MB bufferView copied out as 100 images
		int png = 2 << 20;
		String images = "{\"buffers\":[{}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + png + "}],\"images\":["
			+ String.join(",", java.util.Collections.nCopies(100, "{\"bufferView\":0}")) + "]}";
		assertThrows(java.io.IOException.class, () -> Glb.read(withBin(glb(images), png)));
		// 3 MB of byte indices decode to 12 MB but would bake into 192 MB of quads
		int indices = 3_000_000;
		String triangles = "{\"buffers\":[{}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":12},{\"buffer\":0,\"byteOffset\":12,\"byteLength\":" + indices + "}],"
			+ "\"accessors\":[{\"bufferView\":0,\"count\":1,\"type\":\"VEC3\",\"componentType\":5126},{\"bufferView\":1,\"count\":" + indices + ",\"type\":\"SCALAR\",\"componentType\":5121}],"
			+ "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}]}";
		assertThrows(java.io.IOException.class, () -> Glb.read(withBin(glb(triangles), 12 + indices)));
		// fixed-size arrays that are too short would crash at bake time
		assertThrows(java.io.IOException.class, () -> Glb.read(glb("{\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1]}}]}")));
		assertThrows(java.io.IOException.class, () -> Glb.read(glb("{\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"rotation\":[0]}]}")));
		byte[] badChunk = glb("{}");
		java.nio.ByteBuffer.wrap(badChunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(12, -8);
		assertThrows(java.io.IOException.class, () -> Glb.read(badChunk));
	}
}
