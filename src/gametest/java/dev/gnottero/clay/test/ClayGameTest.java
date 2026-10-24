package dev.gnottero.clay.test;

import dev.gnottero.clay.ClayAnimator;
import dev.gnottero.clay.render.ClayRenderers;
import dev.gnottero.clay.render.GpuRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

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
		}
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
