package dev.gnottero.clay.test;

import dev.gnottero.clay.render.ClayRenderers;
import dev.gnottero.clay.render.GpuRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

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
			context.runOnClient(mc -> { if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); });
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);

			for (boolean oit : new boolean[] {false, true}) {
				context.runOnClient(mc -> mc.options.improvedTransparency().set(oit));
				context.waitTicks(5);
				compareRenderers(context, oit ? "showcase_oit" : "showcase");
			}
			context.runOnClient(mc -> mc.options.improvedTransparency().set(false));
			ClayTestScene.showcase = null;
		}
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
