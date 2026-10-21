package dev.gnottero.clay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Cinematic camera for the local player. While a shot plays the camera follows its {@link Path} (detached, so the player
 * is drawn in it), the HUD and hand are hidden, the player stands still, and the effects that change how the world looks
 * (blindness, darkness, nausea, night vision) are hidden. It stops after its length in game ticks, on
 * {@link #stop()}, or when the player leaves the level it started in.
 */
public final class ClayCamera {
	/** Where the camera sits for a frame, and how it is turned, in degrees like an entity's rotation. */
	public record Shot(Vec3 pos, float yRot, float xRot) {
		/** A camera at {@code pos} looking at {@code target}. */
		public static Shot lookAt(Vec3 pos, Vec3 target) {
			Vec3 d = target.subtract(pos);
			return new Shot(pos, (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90,
				(float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG));
		}
	}

	/** The shot for each frame, given the ticks since it started (fractional between ticks). */
	@FunctionalInterface
	public interface Path {
		Shot at(float ticks);
	}

	private static @Nullable ClientLevel level;
	private static @Nullable Path path;
	private static long start, end;

	private ClayCamera() {}

	/** Plays {@code path} for {@code ticks} game ticks, replacing any shot already playing. */
	public static void play(Path path, int ticks) {
		ClientLevel in = Minecraft.getInstance().level;
		if (in == null) return;
		ClayCamera.path = path;
		level = in;
		start = in.getGameTime();
		end = start + ticks;
	}

	public static void stop() {
		level = null;
		path = null;
	}

	public static boolean playing() {
		return level != null && level == Minecraft.getInstance().level && level.getGameTime() < end;
	}

	/** This frame's shot; null when none plays. */
	public static @Nullable Shot shot(float partial) {
		return playing() ? path.at(level.getGameTime() - start + partial) : null;
	}

	/**
	 * Circles {@code target} at {@code distance}, starting at yaw {@code yRot} and turning {@code speed} degrees per tick,
	 * looking {@code xRot} degrees down onto it. The camera is pulled in front of any wall between it and the target.
	 */
	public static Path orbit(Vec3 target, float distance, float yRot, float xRot, float speed) {
		return ticks -> {
			float y = yRot + ticks * speed;
			Vec3 pos = target.subtract(Vec3.directionFromRotation(xRot, y).scale(distance));
			Minecraft mc = Minecraft.getInstance();
			HitResult hit = mc.level.clip(new ClipContext(target, pos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.MISS) pos = hit.getLocation().lerp(target, 0.5 / distance);
			return new Shot(pos, y, xRot);
		};
	}
}
