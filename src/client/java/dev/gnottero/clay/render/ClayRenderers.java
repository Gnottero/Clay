package dev.gnottero.clay.render;

import com.mojang.logging.LogUtils;
import dev.gnottero.clay.ClayAssets;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Picks the renderer once per resource reload. {@code -Dclay.renderer=auto|gpu|cpu} (default auto): auto uses the GPU
 * renderer unless a shader-pack mod is loaded or Clay's pipelines don't compile on this device; gpu skips the shader-pack
 * check. Falling back logs one warning.
 */
public final class ClayRenderers {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Mods that replace vanilla's entity shaders, which Clay's GPU pipelines rely on. */
	private static final List<String> SHADER_PACK_MODS = List.of("iris", "oculus", "optifabric");
	private static String mode = System.getProperty("clay.renderer", "auto").toLowerCase(Locale.ROOT);

	private static ClayRenderer active = CpuRenderer.INSTANCE;
	private static String reason = "not decided yet";
	private static int decidedFor = -1;
	private static boolean warned;

	private ClayRenderers() {}

	public static ClayRenderer active() {
		if (decidedFor != ClayAssets.generation()) decide();
		return active;
	}

	/** Switches renderers at runtime like {@code -Dclay.renderer} would at startup: "auto", "gpu" or "cpu". */
	public static void setMode(String mode) {
		ClayRenderers.mode = mode.toLowerCase(Locale.ROOT);
		decidedFor = -1;
	}

	/** The active renderer and why, for /clay. */
	public static String describe() {
		active();
		return active.name() + " (" + reason + ")";
	}

	private static void decide() {
		decidedFor = ClayAssets.generation();
		String why = whyNotGpu();
		if (why == null) {
			active = GpuRenderer.INSTANCE;
			reason = mode.equals("gpu") ? "-Dclay.renderer=gpu" : "auto";
		} else {
			active = CpuRenderer.INSTANCE;
			reason = why;
			if (mode.equals("gpu")) LOGGER.error("[clay] -Dclay.renderer=gpu, but the GPU renderer can't run: {}. Using the CPU renderer.", why);
			else if (!mode.equals("cpu")) warnOnce(why, null);
		}
		LOGGER.info("[clay] Using the {} renderer ({})", active.name(), reason);
	}

	private static @Nullable String whyNotGpu() {
		switch (mode) {
			case "cpu" -> { return "-Dclay.renderer=cpu"; }
			case "gpu" -> {}
			default -> {
				if (!mode.equals("auto")) warnOnce("unknown -Dclay.renderer=" + mode + ", expected auto, gpu or cpu", null);
				for (String mod : SHADER_PACK_MODS) {
					if (FabricLoader.getInstance().isModLoaded(mod)) return "shader pack mod '" + mod + "' is loaded";
				}
			}
		}
		return GpuRenderer.unavailable();
	}

	/** The GPU renderer broke mid-frame: use the CPU renderer until the next resource reload. */
	static void gpuFailed(String why, @Nullable Throwable error) {
		active = CpuRenderer.INSTANCE;
		reason = why;
		warnOnce(why, error);
	}

	private static void warnOnce(String why, @Nullable Throwable error) {
		if (warned) return;
		warned = true;
		if (error == null) LOGGER.warn("[clay] Falling back to the CPU renderer: {}", why);
		else LOGGER.warn("[clay] Falling back to the CPU renderer: {}", why, error);
	}
}
