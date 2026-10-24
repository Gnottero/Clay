package dev.gnottero.clay.test;

import dev.gnottero.clay.ClayAnimator;
import dev.gnottero.clay.ClayCamera;
import dev.gnottero.clay.render.ClayRenderers;
import dev.gnottero.clay.render.GpuRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Run with ./gradlew runClientGameTest. Draws the test models with the GPU renderer and the CPU renderer, with and without
 * Improved Transparency, and fails if the two renderers' screenshots disagree. Screenshots land in
 * build/run/clientGameTest/screenshots.
 */
public final class ClayGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("tp @a 0.5 -59 -3 0 20");
			ClayTestScene.showcase = new Vec3(0.5, -60, 0.5);
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				// a short view distance finishes loading quickly, so far chunks popping in can't differ between screenshots
				mc.options.renderDistance().set(2);
			});
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);

			for (boolean oit : new boolean[] {false, true}) {
				context.runOnClient(mc -> mc.options.improvedTransparency().set(oit));
				context.waitTicks(5);
				world.getConnection().waitForChunksRender();
				compareRenderers(context, oit ? "showcase_oit" : "showcase");
			}
			context.runOnClient(mc -> mc.options.improvedTransparency().set(false));
			ClayTestScene.showcase = null;

			// each clip frozen part way through, a crossfade, and a node override on top of a clip
			context.runOnClient(mc -> {
				ClayTestScene.POSES.clear();
				ClayTestScene.POSES.add(pose("bend", 0.4f));
				ClayTestScene.POSES.add(pose("hop", 0.6f));
				ClayTestScene.POSES.add(pose("sway", 0.3f));
				ClayTestScene.POSES.add(pose("spin", 0.25f));
				ClayAnimator fade = pose("bend", 0.5f);
				fade.play("sway", true, 0.4f);
				fade.update(0.7f);
				ClayTestScene.POSES.add(fade);
				ClayTestScene.POSES.add(pose("hop", 0.6f).override("upper", new Matrix4f().rotateX(-0.8f)));
			});
			ClayTestScene.skinned = new Vec3(-3.25, -60, 0.5);
			context.waitTicks(2);
			world.getConnection().waitForChunksRender();
			compareRenderers(context, "skinned");
			ClayTestScene.skinned = null;

			// per-instance looks, with the orb block (simplified, shaped by ClayShapes) in front. Time is frozen so scrolling
			// textures hold still between the two screenshots; the animated screen is swapped for a still texture.
			server.runCommand("tick freeze");
			server.runCommand("setblock 0 -60 -1 clay_test:orb");
			// a voxelized ball: solid in the middle, empty in the corners of the block
			var boxes = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(0, -60, -1)).getShape(s.overworld(), new BlockPos(0, -60, -1)).toAabbs());
			if (boxes.stream().noneMatch(b -> b.contains(0.5, 0.5, 0.5)) || boxes.stream().anyMatch(b -> b.contains(0.05, 0.05, 0.05) || b.contains(0.95, 0.95, 0.05))) {
				throw new AssertionError("orb shape " + boxes);
			}
			Identifier gold = Identifier.withDefaultNamespace("block/gold_block"), still = Identifier.withDefaultNamespace("block/diamond_block");
			context.runOnClient(mc -> {
				ClayTestScene.LOOKS.clear();
				ClayTestScene.LOOKS.add(ClayTestScene.MATERIALS.instance().texture("screen", still));
				ClayTestScene.LOOKS.add(ClayTestScene.MATERIALS.instance().texture("screen", still).tint(0xFFFF6060));
				ClayTestScene.LOOKS.add(ClayTestScene.MATERIALS.instance().texture("screen", still).texture("paint", gold));
				ClayTestScene.LOOKS.add(ClayTestScene.MATERIALS.instance().texture("screen", still).scroll("scroll", 0.3f, 0.7f));
			});
			ClayTestScene.materials = new Vec3(-4, -60, 1.5);
			context.waitTicks(2);
			world.getConnection().waitForChunksRender();
			compareRenderers(context, "materials");

			// the animated screen and the scrolling panel move with time
			context.runOnClient(mc -> {
				ClayTestScene.LOOKS.clear();
				ClayTestScene.LOOKS.add(ClayTestScene.MATERIALS.instance());
			});
			ClayTestScene.materials = new Vec3(2.5, -60, 2.5);
			server.runCommand("tick unfreeze");
			Path before = context.takeScreenshot("animated_0");
			context.waitTicks(7);
			Path after = context.takeScreenshot("animated_1");
			if (differing(before, after) < 0.001) throw new AssertionError("animated and scrolling textures didn't move");
			ClayTestScene.materials = null;

			cameras(context, server);
		}
	}

	/** ClayCamera: keyframes with field of view, roll, fade and letterbox; a Blender camera; a server-started shot; callbacks. */
	private static void cameras(ClientGameTestContext context, TestServerContext server) {
		Vec3 pillar = new Vec3(0.5, -60, -1);
		// frozen time holds the shot still, so the camera can be checked against the clip
		server.runCommand("tick freeze");
		ClayCamera.Keyframes keys = ClayCamera.keyframes()
			.key(0, ClayCamera.Shot.lookAt(new Vec3(-6, -57, -6), pillar))
			.key(100, ClayCamera.Shot.lookAt(new Vec3(6, -56, -6), pillar).withFov(40).withRoll(25), ClayCamera.Easing.IN_OUT_SINE)
			.catmullRom();
		// a shot held still part way along the path, so the frame's partial tick can't move it between here and the check
		ClayCamera.Shot held = keys.at(70).withFov(45);
		context.runOnClient(mc -> ClayCamera.play(ClayCamera.keyframes().key(0, held).key(1000, held).linear().letterbox(0.12f, 0, 0)));
		context.waitTicks(3);
		context.runOnClient(mc -> {
			if (!ClayCamera.playing()) throw new AssertionError("shot isn't playing");
			ClayCamera.Shot shot = ClayCamera.shot(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			var camera = mc.gameRenderer.mainCamera();
			if (camera.position().distanceTo(shot.pos()) > 1e-3) throw new AssertionError("camera at " + camera.position() + ", shot at " + shot.pos());
			if (shot.fov() > 0 && Math.abs(camera.getFov() - shot.fov()) > 1e-3) throw new AssertionError("fov " + camera.getFov() + " for " + shot.fov());
			Vector3f up = camera.rotation().transform(new Vector3f(0, 1, 0)), level = new Quaternionf()
				.rotationYXZ((float) Math.PI - shot.yRot() * Mth.DEG_TO_RAD, -shot.xRot() * Mth.DEG_TO_RAD, shot.roll() * Mth.DEG_TO_RAD).transform(new Vector3f(0, 1, 0));
			if (up.distance(level) > 1e-3) throw new AssertionError("camera roll doesn't match the shot");
		});
		// partway along: rolled, zoomed and letterboxed
		context.runOnClient(mc -> ClayCamera.play(ClayCamera.keyframes().key(0, keys.at(70)).key(1000, keys.at(70)).linear().letterbox(0.12f, 0, 0)));
		context.waitTicks(2);
		context.takeScreenshot("camera_keyframes");
		context.runOnClient(mc -> ClayCamera.play(ClayCamera.keyframes().key(0, keys.at(70)).key(1000, keys.at(70)).linear().fade(0, 2000, 0x000000)));
		context.waitTicks(2);
		context.takeScreenshot("camera_fade_half");

		// the camera Blender animated, placed over the pillar
		var intro = ClayTestScene.STAGE.camera("intro").at(new Vec3(0.5, -60, -1), 0);
		context.runOnClient(mc -> ClayCamera.play(intro));
		context.waitTicks(2);
		context.runOnClient(mc -> {
			var camera = mc.gameRenderer.mainCamera();
			if (intro.length() != 40) throw new AssertionError("intro lasts " + intro.length());
			if (camera.position().distanceTo(new Vec3(0.5, -58, 5)) > 0.5) throw new AssertionError("intro starts at " + camera.position());
		});
		context.takeScreenshot("camera_blender");
		context.runOnClient(mc -> ClayCamera.stop());
		server.runCommand("tick unfreeze");

		// a shot started by the server, then stopped by it
		ClayCamera.Clip orbit = new ClayCamera.Timed(ClayCamera.orbit(pillar, 8, 0, 25, 2), 200);
		server.runOnServer(s -> ClayCamera.playFor(s.getPlayerList().getPlayers().getFirst(), orbit));
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> ClayCamera.playing())) throw new AssertionError("server shot didn't start");
		server.runOnServer(s -> ClayCamera.stopFor(s.getPlayerList().getPlayers().getFirst()));
		context.waitTicks(3);
		if (context.computeOnClient(mc -> ClayCamera.playing())) throw new AssertionError("server shot didn't stop");

		// callbacks run in order as a sequence plays through
		List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
		ClayCamera.Clip a = new ClayCamera.Timed(orbit::at, 6).onStart(() -> log.add("a start")).onEnd(() -> log.add("a end"));
		ClayCamera.Clip b = intro.length(6).onStart(() -> log.add("b start")).onEnd(() -> log.add("b end"));
		context.runOnClient(mc -> ClayCamera.play(ClayCamera.sequence().then(a).then(b, ClayCamera.Transition.FADE, 4).build()));
		context.waitTicks(16);
		if (!log.equals(List.of("a start", "a end", "b start", "b end"))) throw new AssertionError("callbacks ran as " + log);
		if (context.computeOnClient(mc -> ClayCamera.playing())) throw new AssertionError("sequence didn't end");
	}

	/** An animator holding {@code clip} at {@code seconds}. */
	private static ClayAnimator pose(String clip, float seconds) {
		ClayAnimator animator = ClayTestScene.SKINNED.animator().play(clip);
		animator.update(0);
		animator.update(seconds);
		return animator;
	}

	/** Screenshots the scene with each renderer; they must agree up to rounding along edges. */
	private static void compareRenderers(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> ClayRenderers.setMode("gpu"));
		context.waitTick();
		if (context.computeOnClient(mc -> ClayRenderers.active()) != GpuRenderer.INSTANCE) {
			throw new AssertionError("GPU renderer unavailable: " + context.computeOnClient(mc -> ClayRenderers.describe()));
		}
		Path gpu = context.takeScreenshot(name + "_gpu");
		context.runOnClient(mc -> ClayRenderers.setMode("cpu"));
		context.waitTick();
		Path cpu = context.takeScreenshot(name + "_cpu");
		context.runOnClient(mc -> ClayRenderers.setMode("auto"));

		double differing = differing(gpu, cpu);
		System.out.printf("[clay test] %s: %.3f%% of pixels differ between the renderers%n", name, differing * 100);
		if (differing > 0.002) throw new AssertionError(name + ": GPU and CPU renderers differ in " + differing * 100 + "% of pixels");
	}

	/** Share of pixels where any channel differs by more than 24 of 255. */
	static double differing(Path a, Path b) {
		try {
			BufferedImage x = ImageIO.read(a.toFile()), y = ImageIO.read(b.toFile());
			int count = 0;
			for (int j = 0; j < x.getHeight(); j++) {
				for (int i = 0; i < x.getWidth(); i++) {
					int p = x.getRGB(i, j), q = y.getRGB(i, j);
					for (int s = 0; s < 24; s += 8) {
						if (Math.abs((p >> s & 0xFF) - (q >> s & 0xFF)) > 24) {
							count++;
							break;
						}
					}
				}
			}
			return count / (double) (x.getWidth() * x.getHeight());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
