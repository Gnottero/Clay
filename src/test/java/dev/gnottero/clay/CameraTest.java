package dev.gnottero.clay;

import dev.gnottero.clay.ClayCamera.Clip;
import dev.gnottero.clay.ClayCamera.Easing;
import dev.gnottero.clay.ClayCamera.Shot;
import dev.gnottero.clay.ClayCamera.Transition;
import dev.gnottero.clay.gltf.Glb;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CameraTest {
	private static final Vec3 A = new Vec3(0, 70, 0), B = new Vec3(10, 72, 0), C = new Vec3(10, 74, 10), D = new Vec3(0, 76, 10);

	@Test
	void easingsRunFromZeroToOne() {
		for (Easing e : Easing.values()) {
			assertEquals(0, e.apply(0), 1e-5, e.name());
			assertEquals(1, e.apply(1), 1e-5, e.name());
			if (!e.name().contains("BACK")) for (float t = 0; t < 1; t += 0.05f) assertTrue(e.apply(t + 0.05f) >= e.apply(t) - 1e-6, e.name());
		}
		assertEquals(0.5, Easing.IN_OUT_CUBIC.apply(0.5f), 1e-6);
		assertTrue(Easing.IN_QUAD.apply(0.3f) < 0.3f && Easing.OUT_QUAD.apply(0.3f) > 0.3f);
	}

	private static ClayCamera.KeyframeBuilder square() {
		return ClayCamera.keyframes().key(0, new Shot(A, 0, 0)).key(20, new Shot(B, 90, 10)).key(40, new Shot(C, 180, 0, 50, 10)).key(60, new Shot(D, 270, -10));
	}

	@Test
	void splinesPassThroughTheirKeys() {
		for (Clip clip : List.of(square().linear(), square().catmullRom(), square().bezier())) {
			assertEquals(60, clip.length());
			assertEquals(B, clip.at(20).pos());
			assertEquals(C, clip.at(40).pos());
			assertEquals(D, clip.at(99).pos(), "held after the last key");
		}
		assertEquals(new Vec3(5, 71, 0), square().linear().at(10).pos());
		// Catmull-Rom bulges outwards between B and C, a straight line doesn't
		assertTrue(square().catmullRom().at(30).pos().x > 10);
		// Bezier without handles is Catmull-Rom
		for (float t = 0; t <= 60; t += 3.7f) {
			Shot cr = square().catmullRom().at(t), bz = square().bezier().at(t);
			assertTrue(cr.pos().distanceTo(bz.pos()) < 1e-9);
			assertEquals(cr.yRot(), bz.yRot(), 1e-4);
		}
		// handles bend the curve
		var bent = ClayCamera.keyframes().key(0, new Shot(A, 0, 0)).key(new ClayCamera.Key(20, new Shot(B, 0, 0), Easing.LINEAR, new Vec3(5, 90, 0), null)).bezier();
		assertTrue(bent.at(10).pos().y > 75);
	}

	@Test
	void anglesTurnTheShortWay() {
		var clip = ClayCamera.keyframes().key(0, new Shot(A, 350, 0)).key(10, new Shot(A, 10, 0)).linear();
		assertEquals(0, Mth.wrapDegrees(clip.at(5).yRot()), 1e-4);
		var spline = ClayCamera.keyframes().key(0, new Shot(A, 350, 0)).key(10, new Shot(A, 10, 0)).key(20, new Shot(A, 30, 0)).catmullRom();
		assertEquals(0, Mth.wrapDegrees(spline.at(5).yRot()), 5, "near 0, not the long way round through 180");
	}

	@Test
	void easingShapesTheSegmentArrivingAtAKey() {
		var eased = ClayCamera.keyframes().key(0, new Shot(A, 0, 0)).key(20, new Shot(B, 0, 0), Easing.IN_QUAD).linear();
		assertEquals(10 * 0.25, eased.at(10).pos().x, 1e-6);
	}

	@Test
	void fovOfZeroMeansThePlayersOwn() {
		var clip = ClayCamera.keyframes().key(0, new Shot(A, 0, 0)).key(10, new Shot(B, 0, 0, 30, 0)).key(20, new Shot(C, 0, 0, 60, 0)).linear();
		assertEquals(0, clip.at(5).fov(), "doesn't blend with the player's field of view");
		assertEquals(45, clip.at(15).fov(), 1e-4);
	}

	@Test
	void sequencesCutBlendAndFade() {
		Clip first = new ClayCamera.Timed(t -> new Shot(A, 0, 0), 20), second = new ClayCamera.Timed(t -> new Shot(B, 90, 0), 20);
		var cut = ClayCamera.sequence().then(first).then(second).build();
		assertEquals(40, cut.length());
		assertEquals(A, cut.at(19.9f).pos());
		assertEquals(B, cut.at(20).pos());
		var blend = ClayCamera.sequence().then(first).then(second, Transition.BLEND, 10).build();
		assertEquals(A, blend.at(20).pos(), "starts where the previous clip ended");
		assertEquals(5, blend.at(25).pos().x, 1e-6);
		assertEquals(B, blend.at(30).pos());
		var fade = ClayCamera.sequence().then(first).then(second, Transition.FADE, 10).build();
		assertEquals(255, fade.overlay(20).color() >>> 24, "black at the cut");
		assertEquals(0, fade.overlay(14).color() >>> 24);
		assertEquals(0, fade.overlay(26).color() >>> 24);
		assertTrue((fade.overlay(17.5f).color() >>> 24) > 100);
	}

	@Test
	void effectsShapeTheOverlayAndShake() {
		Clip clip = ClayCamera.keyframes().key(0, new Shot(A, 0, 0)).key(40, new Shot(A, 0, 0)).linear();
		Clip styled = clip.fade(10, 10, 0xFFFFFF).letterbox(0.1f, 10, 10).shake(2, 3);
		assertEquals(0xFFFFFFFF, styled.overlay(0).color());
		assertEquals(0, styled.overlay(20).color() >>> 24);
		assertEquals(0.1f, styled.overlay(20).letterbox(), 1e-6);
		assertEquals(0, styled.overlay(0).letterbox(), 1e-6);
		float most = 0;
		for (float t = 0; t < 40; t += 0.5f) most = Math.max(most, Math.abs(styled.at(t).yRot()));
		assertTrue(most > 0.5f && most <= 2, "shake " + most);
		assertEquals(styled.at(12.3f), styled.at(12.3f), "the same every time");
	}

	@Test
	void callbacksRunAsTimePasses() {
		List<String> log = new ArrayList<>();
		Clip a = new ClayCamera.Timed(t -> new Shot(A, 0, 0), 10).onStart(() -> log.add("a start")).onEnd(() -> log.add("a end"));
		Clip b = new ClayCamera.Timed(t -> new Shot(A, 0, 0), 10).onStart(() -> log.add("b start")).onEnd(() -> log.add("b end"));
		Clip seq = ClayCamera.sequence().then(a).then(b).build();
		seq.fire(-1, 0);
		assertEquals(List.of("a start"), log);
		seq.fire(0, 9);
		seq.fire(9, 12);
		assertEquals(List.of("a start", "a end", "b start"), log);
		seq.interrupt(14);
		assertEquals(List.of("a start", "a end", "b start", "b end"), log);
	}

	@Test
	void sendableClipsSurviveTheNetwork() {
		List<Clip> clips = List.of(
			square().catmullRom().letterbox(0.12f, 5, 5).fade(10, 0, 0x102030),
			ClayCamera.keyframes().key(new ClayCamera.Key(0, new Shot(A, 1, 2, 3, 4), Easing.OUT_BACK, B, null)).bezier(),
			new ClayCamera.Timed(ClayCamera.orbit(A, 12, 30, 20, 0.25f), 60).shake(1, 4),
			ClayCamera.sequence().then(ClayCamera.camera(Identifier.fromNamespaceAndPath("a", "b"), "intro").at(B, 90).length(30))
				.then(square().linear(), Transition.FADE, 12).build());
		for (Clip clip : clips) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			ClayCamera.write(buf, clip);
			assertEquals(clip, ClayCamera.read(buf, 0));
			assertEquals(0, buf.readableBytes());
		}
		assertThrows(IllegalArgumentException.class, () -> ClayCamera.write(new FriendlyByteBuf(Unpooled.buffer()), clips.get(2).onEnd(() -> {})));
		FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
		bad.writeByte(3).writeVarInt(1_000_000);
		assertThrows(IllegalArgumentException.class, () -> ClayCamera.read(bad, 0));
		// a huge orbit is shrunk to something the wall check can afford, a NaN is refused
		FriendlyByteBuf far = new FriendlyByteBuf(Unpooled.buffer());
		ClayCamera.write(far, new ClayCamera.Timed(ClayCamera.orbit(A, 1e30f, 0, 0, 0), 20));
		assertEquals(256, ((ClayCamera.Orbit) ((ClayCamera.Timed) ClayCamera.read(far, 0)).path()).distance());
		// sequences of sequences: fine per level, too many parts in all
		Clip wide = ClayCamera.sequence().then(square().linear()).build();
		for (int level = 0; level < 3; level++) {
			var builder = ClayCamera.sequence();
			for (int i = 0; i < 20; i++) builder.then(wide);
			wide = builder.build();
		}
		FriendlyByteBuf nested = new FriendlyByteBuf(Unpooled.buffer());
		ClayCamera.write(nested, wide);
		assertThrows(IllegalArgumentException.class, () -> ClayCamera.read(nested, 0));
		FriendlyByteBuf opposite = new FriendlyByteBuf(Unpooled.buffer());
		ClayCamera.write(opposite, new ClayCamera.Timed(ClayCamera.orbit(new Vec3(1e308, -1e308, 0), 8, 0, 0, 0), 20));
		assertThrows(IllegalArgumentException.class, () -> ClayCamera.read(opposite, 0));
		FriendlyByteBuf nan = new FriendlyByteBuf(Unpooled.buffer());
		ClayCamera.write(nan, new ClayCamera.Timed(ClayCamera.orbit(A, Float.NaN, 0, 0, 0), 20));
		assertThrows(IllegalArgumentException.class, () -> ClayCamera.read(nan, 0));
	}

	/** A glTF camera's orientation turned into yaw, pitch and roll must rebuild the same view the way Camera.setRotation does. */
	@Test
	void gltfCamerasKeepTheirRoll() {
		Random random = new Random(7);
		for (int i = 0; i < 200; i++) {
			Matrix4f camera = new Matrix4f().translation(random.nextFloat(), random.nextFloat(), random.nextFloat())
				.rotate(new Quaternionf().rotationXYZ(random.nextFloat() * 2.4f - 1.2f, random.nextFloat() * 6.28f, random.nextFloat() * 1.6f - 0.8f));
			Shot shot = ClayCamera.cameraShot(camera, 1, Vec3.ZERO, 0);
			Quaternionf mc = new Quaternionf().rotationYXZ((float) Math.PI - shot.yRot() * Mth.DEG_TO_RAD, -shot.xRot() * Mth.DEG_TO_RAD, shot.roll() * Mth.DEG_TO_RAD);
			Vector3f forward = camera.transformDirection(new Vector3f(0, 0, -1)), up = camera.transformDirection(new Vector3f(0, 1, 0));
			assertTrue(mc.transform(new Vector3f(0, 0, -1)).distance(forward) < 1e-4, "forward");
			assertTrue(mc.transform(new Vector3f(0, 1, 0)).distance(up) < 1e-4, "up");
		}
	}

	@Test
	void playsBlenderCameras() throws Exception {
		Glb glb = Glb.read(Files.readAllBytes(Path.of("src/gametest/resources/assets/clay_test/clay/stage.glb")));
		Identifier stage = Identifier.fromNamespaceAndPath("clay_test", "stage");
		ClayCamera.models = id -> id.equals(stage) ? glb : null;
		try {
			var intro = ClayCamera.camera(stage, "intro");
			assertEquals(40, intro.length(), "2 seconds of animation");
			Shot first = intro.at(0), last = intro.at(40);
			assertEquals(new Vec3(0, 2, 6), first.pos().scale(1e4).align(java.util.EnumSet.allOf(net.minecraft.core.Direction.Axis.class)).scale(1e-4));
			assertEquals(180, Mth.wrapDegrees(first.yRot() - 180) + 180, 1e-3, "Blender -Y looks north");
			assertEquals(Math.toDegrees(0.5631968779732008), first.fov(), 1e-3);
			assertEquals(0, first.roll(), 1e-3);
			assertEquals(20, last.roll(), 0.1);
			assertEquals(6, last.pos().x, 1e-4);
			// placed and turned: the camera's offset from the origin turns with the scene
			Shot moved = intro.at(new Vec3(100, 64, 100), 90).at(0);
			assertEquals(-6, moved.pos().x - 100, 1e-4);
			assertEquals(64 + 2, moved.pos().y, 1e-4);
			assertEquals(Mth.wrapDegrees(first.yRot() + 90), Mth.wrapDegrees(moved.yRot()), 1e-3);
			assertEquals(0, ClayCamera.camera(stage, "still").length(), "doesn't move, so it needs a length");
			assertEquals(30, ClayCamera.camera(stage, "still").length(30).length());
		} finally {
			ClayCamera.models = id -> null;
		}
	}
}
