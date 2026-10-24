package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Keyframes;
import dev.gnottero.clay.gltf.Rig;
import dev.gnottero.clay.render.ClayMesh;
import dev.gnottero.clay.render.ClaySkin;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnimationTest {
	static Glb skinned() throws Exception {
		return Glb.read(Files.readAllBytes(Path.of("src/gametest/resources/assets/clay_test/clay/skinned.glb")));
	}

	private static int node(Glb glb, String name) {
		for (int i = 0; i < glb.nodes.size(); i++) if (glb.nodes.get(i).name().equals(name)) return i;
		throw new AssertionError(name);
	}

	@Test
	void parsesSkinsAndClipsFromBlender() throws Exception {
		Glb glb = skinned();
		assertEquals(1, glb.skins.size());
		Glb.Skin skin = glb.skins.getFirst();
		assertEquals(2, skin.joints().length);
		assertEquals(node(glb, "lower"), skin.joints()[0]);
		assertEquals(0, glb.nodes.get(node(glb, "column")).skin());
		Glb.Primitive column = glb.nodes.get(node(glb, "column")).primitives().getFirst();
		assertNotNull(column.joints());
		for (int v = 0; v < column.vertexCount(); v++) {
			float sum = 0;
			for (int k = 0; k < 4; k++) sum += column.weights()[v * 4 + k];
			assertEquals(1, sum, 1e-3);
		}
		Map<String, Glb.Interpolation> expected = Map.of("bend", Glb.Interpolation.LINEAR, "hop", Glb.Interpolation.STEP,
			"sway", Glb.Interpolation.CUBICSPLINE, "spin", Glb.Interpolation.LINEAR);
		assertEquals(expected.size(), glb.animations.size());
		for (Glb.Animation clip : glb.animations) {
			assertEquals(expected.get(clip.name()), clip.channels().getFirst().interpolation(), clip.name());
			assertEquals(1, clip.duration(), 1e-4, "20 frames at 20 fps");
		}
	}

	private static Glb.Channel channel(Glb.Interpolation interpolation, Glb.Path path, float[] times, float... values) {
		return new Glb.Channel(0, path, interpolation, times, values);
	}

	@Test
	void samplesEachInterpolation() {
		float[] out = new float[4], times = {0, 1, 2};
		Glb.Channel linear = channel(Glb.Interpolation.LINEAR, Glb.Path.TRANSLATION, times, 0, 0, 0, 2, 0, 0, 2, 4, 0);
		Keyframes.sample(linear, 0.25f, out, 0);
		assertArrayEquals(new float[] {0.5f, 0, 0}, new float[] {out[0], out[1], out[2]}, 1e-6f);
		Keyframes.sample(linear, 1.5f, out, 0);
		assertArrayEquals(new float[] {2, 2, 0}, new float[] {out[0], out[1], out[2]}, 1e-6f);
		Keyframes.sample(linear, 9, out, 0); // held at the last key
		assertEquals(4, out[1], 1e-6);

		Glb.Channel step = channel(Glb.Interpolation.STEP, Glb.Path.TRANSLATION, times, 0, 0, 0, 2, 0, 0, 2, 4, 0);
		Keyframes.sample(step, 0.99f, out, 0);
		assertEquals(0, out[0]);
		Keyframes.sample(step, 1.01f, out, 0);
		assertEquals(2, out[0]);

		// one component easing 0 -> 1 with flat tangents: hermite gives smoothstep, 0.5 at the middle, 0.15625 at a quarter
		Glb.Channel cubic = channel(Glb.Interpolation.CUBICSPLINE, Glb.Path.TRANSLATION, new float[] {0, 2},
			0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 1, 0, 0, 0, 0, 0);
		Keyframes.sample(cubic, 1, out, 0);
		assertEquals(0.5f, out[0], 1e-6);
		Keyframes.sample(cubic, 0.5f, out, 0);
		assertEquals(0.15625f, out[0], 1e-6);
		// with tangents, the curve leaves at the out-tangent's slope (tangents are per second, scaled by the key gap)
		Glb.Channel sloped = channel(Glb.Interpolation.CUBICSPLINE, Glb.Path.TRANSLATION, new float[] {0, 1},
			0, 0, 0, 0, 0, 0, 1, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0);
		Keyframes.sample(sloped, 0.001f, out, 0);
		assertEquals(0.001f, out[0], 1e-5);

		// rotations slerp: halfway between identity and 90° about Y is 45°
		float s = (float) Math.sin(Math.PI / 4), c = (float) Math.cos(Math.PI / 4);
		Glb.Channel rotation = channel(Glb.Interpolation.LINEAR, Glb.Path.ROTATION, new float[] {0, 1}, 0, 0, 0, 1, 0, s, 0, c);
		Keyframes.sample(rotation, 0.5f, out, 0);
		assertEquals(Math.sin(Math.PI / 8), out[1], 1e-5);
		assertEquals(Math.cos(Math.PI / 8), out[3], 1e-5);
	}

	@Test
	void restPoseLeavesSkinnedVerticesInPlace() throws Exception {
		Glb glb = skinned();
		Rig rig = new Rig(glb);
		Matrix4f[] palette = rig.build(null);
		// a joint's global times its inverse bind matrix is the identity in the bind pose
		for (int j = 0; j < 2; j++) assertTrue(palette[rig.skinOffset[0] + j].equals(new Matrix4f(), 1e-5f), "joint " + j);
	}

	@Test
	void bendMovesOnlyTheTopOfTheColumn() throws Exception {
		Glb glb = skinned();
		Rig rig = new Rig(glb);
		ClayMesh mesh = new ClayMesh(Identifier.fromNamespaceAndPath("clay_test", "skinned"), glb, m -> new float[] {0, 0, 1, 1});
		rig.sample(rig.clip("bend"), 0.5f, true);
		float[] posed = new float[mesh.paletteSize * 16], skin = new float[16], p = new float[3];
		ClaySkin.pose(new Matrix4f(), rig.build(null), mesh.paletteSize, posed);
		boolean topMoved = false;
		for (int v = 0; v < mesh.vertexCount; v++) {
			ClaySkin.blend(posed, mesh.joints, mesh.weights, v, skin);
			ClaySkin.transformPosition(skin, mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], p, 0);
			if (mesh.joints[v * 4] < glb.nodes.size()) continue; // the spinner
			float dist = new Vector3f(p).distance(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]);
			if (mesh.positions[v * 3 + 1] < 0.45f) assertEquals(0, dist, 1e-5, "below the blend zone the lower bone holds still");
			if (mesh.positions[v * 3 + 1] > 1.1f && dist > 0.1f) topMoved = true;
		}
		assertTrue(topMoved);
	}

	/** The CPU renderer's skinning (ClaySkin, mirrored by entity.vsh) against glTF's definition, computed independently with JOML. */
	@Test
	void skinningMatchesTheGltfDefinition() throws Exception {
		Glb glb = skinned();
		Rig rig = new Rig(glb);
		ClayMesh mesh = new ClayMesh(Identifier.fromNamespaceAndPath("clay_test", "skinned"), glb, m -> new float[] {0, 0, 1, 1});
		rig.sampleBlend(rig.clip("bend"), 0.3f, true, rig.clip("sway"), 0.7f, true, 0.4f);
		Matrix4f[] palette = rig.build(null);
		Matrix4f pose = new Matrix4f().translation(3, -2, 7).rotateX(0.3f);
		float[] posed = new float[mesh.paletteSize * 16], skin = new float[16], p = new float[3];
		ClaySkin.pose(pose, palette, mesh.paletteSize, posed);
		Glb.Skin gltfSkin = glb.skins.getFirst();
		for (int v = 0; v < mesh.vertexCount; v++) {
			ClaySkin.blend(posed, mesh.joints, mesh.weights, v, skin);
			ClaySkin.transformPosition(skin, mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], p, 0);
			Vector3f expected = new Vector3f(), local = new Vector3f(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]);
			float total = 0;
			for (int k = 0; k < 4; k++) total += mesh.weights[v * 4 + k] & 0xFF;
			for (int k = 0; k < 4; k++) {
				float w = (mesh.weights[v * 4 + k] & 0xFF) / total;
				if (w == 0) continue;
				int entry = mesh.joints[v * 4 + k];
				Matrix4f joint = entry < glb.nodes.size() ? new Matrix4f(palette[entry])
					: new Matrix4f(palette[gltfSkin.joints()[entry - rig.skinOffset[0]]]).mul(gltfSkin.inverseBind()[entry - rig.skinOffset[0]]);
				expected.add(new Matrix4f(pose).mul(joint).transformPosition(local, new Vector3f()).mul(w));
			}
			assertEquals(expected.x, p[0], 1e-4);
			assertEquals(expected.y, p[1], 1e-4);
			assertEquals(expected.z, p[2], 1e-4);
		}
	}

	@Test
	void animatorPlaysLoopsAndCrossfades() {
		ClayAnimator animator = new ClayAnimator(ClayModel.of(Identifier.fromNamespaceAndPath("clay_test", "unloaded")));
		animator.update(10);
		animator.play("walk");
		animator.update(10.5f);
		assertEquals(0.5f, animator.time(), 1e-6);
		animator.play("walk"); // already playing: keeps going
		assertEquals(0.5f, animator.time(), 1e-6);
		animator.speed(2).play("attack", false, 0.2f);
		assertTrue(animator.fading());
		assertEquals("walk", animator.previous());
		animator.update(10.55f);
		assertEquals(0.25f, animator.weight(), 1e-5);
		assertEquals(0.1f, animator.time(), 1e-5);
		animator.update(10.8f);
		assertFalse(animator.fading());
		assertEquals(1, animator.weight());
		animator.stop(0.5f);
		assertNull(animator.clip());
		assertEquals("attack", animator.previous());
	}

	/** Advancing an animator and posing a skinned rig every frame mustn't create garbage. */
	@Test
	void posingDoesNotAllocate() throws Exception {
		Glb glb = skinned();
		Rig rig = new Rig(glb);
		ClayAnimator animator = new ClayAnimator(ClayModel.of(Identifier.fromNamespaceAndPath("clay_test", "unloaded")));
		animator.override("upper", new Matrix4f().rotateZ(0.2f));
		Glb.Animation bend = rig.clip("bend"), sway = rig.clip("sway");
		var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		Runnable frame = new Runnable() {
			float t;

			@Override
			public void run() {
				animator.update(t += 0.016f);
				animator.play("bend", true, 0.3f);
				animator.override("upper", ROTATION);
				rig.sampleBlend(sway, animator.previousTime(), true, bend, animator.time(), true, animator.weight());
				rig.build(animator.overrides());
			}
		};
		for (int i = 0; i < 20_000; i++) frame.run(); // warm up, so the JIT has compiled what it will
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 10_000; i++) frame.run();
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(allocated < 1024, "allocated " + allocated + " bytes in 10000 frames");
	}

	private static final Matrix4f ROTATION = new Matrix4f().rotateZ(0.3f);
}
