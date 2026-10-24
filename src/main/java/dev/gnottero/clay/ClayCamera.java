package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Rig;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cinematic camera for the local player. While a shot plays the camera follows it (detached, so the player is drawn in
 * it), the HUD and hand are hidden, the player stands still, and the effects that change how the world looks (blindness,
 * darkness, nausea, night vision) are hidden. It stops after its length in game ticks, on {@link #stop()}, or when the
 * player leaves the level it started in.
 * <p>
 * What plays is a {@link Clip}: a {@link Path} for a number of ticks ({@link #play(Path, int)}), {@link #keyframes()}
 * along a spline, an {@link #orbit}, a camera animated in Blender ({@link #camera}), or a {@link #sequence()} of those,
 * each with optional shake, fades, letterbox bars and callbacks. Clips built from data rather than code can also be
 * started by the server with {@link #playFor}.
 */
public final class ClayCamera {
	private ClayCamera() {}

	// ---- frames ----

	/**
	 * Where the camera sits for a frame and how it is turned, in degrees like an entity's rotation. {@code fov} is the
	 * vertical field of view in degrees (0 = the player's own); {@code roll} tilts the picture clockwise.
	 */
	public record Shot(Vec3 pos, float yRot, float xRot, float fov, float roll) {
		public Shot(Vec3 pos, float yRot, float xRot) {
			this(pos, yRot, xRot, 0, 0);
		}

		/** A camera at {@code pos} looking at {@code target}. */
		public static Shot lookAt(Vec3 pos, Vec3 target) {
			Vec3 d = target.subtract(pos);
			return new Shot(pos, (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90,
				(float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG));
		}

		public Shot withFov(float fov) {
			return new Shot(pos, yRot, xRot, fov, roll);
		}

		public Shot withRoll(float roll) {
			return new Shot(pos, yRot, xRot, fov, roll);
		}

		/** Between {@code this} and {@code to} by {@code t}: turning the short way round, and only blending fields both set. */
		public Shot lerp(Shot to, float t) {
			return new Shot(pos.lerp(to.pos, t), yRot + Mth.wrapDegrees(to.yRot - yRot) * t, Mth.lerp(t, xRot, to.xRot),
				fov > 0 && to.fov > 0 ? Mth.lerp(t, fov, to.fov) : t < 1 ? fov : to.fov, Mth.lerp(t, roll, to.roll));
		}
	}

	/** The shot for each frame, given the ticks since it started (fractional between ticks). */
	@FunctionalInterface
	public interface Path {
		Shot at(float ticks);
	}

	// ---- clips ----

	/**
	 * Something to play: a camera path that lasts {@link #length()} ticks, plus how the screen looks meanwhile. Clips are
	 * immutable; the effect methods return a new clip. All clips except {@link Timed} ones with a code path and
	 * {@link Hooked} ones can be sent by a server with {@link #playFor}.
	 */
	public sealed interface Clip extends Path permits Timed, Keyframes, ModelCamera, Sequence, Styled, Hooked {
		int length();

		/** Fade and letterbox at {@code ticks}. */
		default Overlay overlay(float ticks) {
			return Overlay.NONE;
		}

		/** Runs the onStart and onEnd callbacks whose moment lies after {@code from} and up to {@code to}. */
		default void fire(float from, float to) {}

		/** Runs the onEnd callbacks of clips that were still running at {@code ticks} when the shot was cut short. */
		default void interrupt(float ticks) {}

		/** Shakes the camera by up to about {@code degrees}, {@code frequency} times a second. */
		default Clip shake(float degrees, float frequency) {
			return new Styled(this, new Shake(degrees, frequency));
		}

		/** Fades in from {@code rgb} over the first {@code in} ticks and out to it over the last {@code out}. */
		default Clip fade(int in, int out, int rgb) {
			return new Styled(this, new Fade(in, out, rgb & 0xFFFFFF));
		}

		/** Black bars at the top and bottom, each {@code size} of the screen height, sliding in and out over the given ticks. */
		default Clip letterbox(float size, int in, int out) {
			return new Styled(this, new Letterbox(size, in, out));
		}

		/** Runs {@code callback} on the client when this clip starts (within a sequence: when its turn comes). */
		default Clip onStart(Runnable callback) {
			return new Hooked(this, callback, null);
		}

		/** Runs {@code callback} on the client when this clip ends, or when the shot is stopped or replaced while it runs. */
		default Clip onEnd(Runnable callback) {
			return new Hooked(this, null, callback);
		}
	}

	/** A fade color (ARGB, alpha = how much it covers) and letterbox bars (fraction of the screen height, each). */
	public record Overlay(int color, float letterbox) {
		public static final Overlay NONE = new Overlay(0, 0);

		Overlay with(Overlay other) {
			return new Overlay((other.color >>> 24) > (color >>> 24) ? other.color : color, Math.max(letterbox, other.letterbox));
		}
	}

	/** {@code path} for {@code length} ticks. Sendable when the path is an {@link Orbit}. */
	public record Timed(Path path, int length) implements Clip {
		@Override
		public Shot at(float ticks) {
			return path.at(ticks);
		}
	}

	/** Plays {@code path} for {@code ticks} game ticks, replacing any shot already playing. */
	public static void play(Path path, int ticks) {
		play(new Timed(path, ticks));
	}

	/**
	 * Circles {@code target} at {@code distance}, starting at yaw {@code yRot} and turning {@code speed} degrees per tick,
	 * looking {@code xRot} degrees down onto it. The camera is pulled in front of any wall between it and the target.
	 */
	public static Path orbit(Vec3 target, float distance, float yRot, float xRot, float speed) {
		return new Orbit(target, distance, yRot, xRot, speed);
	}

	/** See {@link #orbit}. A record, so an orbit can be sent to clients: {@code new Timed(orbit, ticks)}. */
	public record Orbit(Vec3 target, float distance, float yRot, float xRot, float speed) implements Path {
		@Override
		public Shot at(float ticks) {
			float y = yRot + ticks * speed;
			// bounded so a bad value can't make the wall check below trace across the world every frame
			float distance = Math.clamp(this.distance, 0.1f, MAX_ORBIT);
			Vec3 pos = target.subtract(Vec3.directionFromRotation(xRot, y).scale(distance));
			Level level = clientLevel.get();
			if (level != null) {
				HitResult hit = level.clip(new ClipContext(target, pos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.MISS) pos = hit.getLocation().lerp(target, 0.5 / distance);
			}
			return new Shot(pos, y, xRot);
		}
	}

	// ---- keyframes ----

	/** How a segment's time is shaped between two keyframes. */
	public enum Easing {
		LINEAR, IN_SINE, OUT_SINE, IN_OUT_SINE, IN_QUAD, OUT_QUAD, IN_OUT_QUAD, IN_CUBIC, OUT_CUBIC, IN_OUT_CUBIC,
		IN_EXPO, OUT_EXPO, IN_OUT_EXPO, IN_BACK, OUT_BACK, IN_OUT_BACK;

		/** Maps 0-1 to 0-1 (BACK overshoots in between). */
		public float apply(float t) {
			t = Math.clamp(t, 0, 1);
			double c = 1.70158, c2 = c * 1.525;
			return (float) switch (this) {
				case LINEAR -> t;
				case IN_SINE -> 1 - Math.cos(t * Math.PI / 2);
				case OUT_SINE -> Math.sin(t * Math.PI / 2);
				case IN_OUT_SINE -> -(Math.cos(Math.PI * t) - 1) / 2;
				case IN_QUAD -> t * t;
				case OUT_QUAD -> 1 - (1 - t) * (1 - t);
				case IN_OUT_QUAD -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
				case IN_CUBIC -> t * t * t;
				case OUT_CUBIC -> 1 - Math.pow(1 - t, 3);
				case IN_OUT_CUBIC -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
				case IN_EXPO -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
				case OUT_EXPO -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
				case IN_OUT_EXPO -> t == 0 || t == 1 ? t : t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
				case IN_BACK -> (c + 1) * t * t * t - c * t * t;
				case OUT_BACK -> 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2);
				case IN_OUT_BACK -> t < 0.5 ? Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2) / 2
					: (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
			};
		}
	}

	/**
	 * How positions and angles run through the keys: straight lines, a Catmull-Rom spline through every key, or cubic
	 * Bezier curves using each key's handles (keys without handles get the ones that make it match Catmull-Rom).
	 */
	public enum Spline { LINEAR, CATMULL_ROM, BEZIER }

	/** A keyframe at {@code tick}. {@code easing} shapes the segment arriving at it; handles (world positions) are for {@link Spline#BEZIER}. */
	public record Key(float tick, Shot shot, Easing easing, @Nullable Vec3 handleIn, @Nullable Vec3 handleOut) {}

	/** Starts a keyframed path: {@code keyframes().key(0, shotA).key(40, shotB, Easing.IN_OUT_SINE).catmullRom()}. */
	public static KeyframeBuilder keyframes() {
		return new KeyframeBuilder();
	}

	public static final class KeyframeBuilder {
		private final List<Key> keys = new ArrayList<>();

		private KeyframeBuilder() {}

		public KeyframeBuilder key(float tick, Shot shot) {
			return key(tick, shot, Easing.LINEAR);
		}

		public KeyframeBuilder key(float tick, Shot shot, Easing easing) {
			return key(new Key(tick, shot, easing, null, null));
		}

		public KeyframeBuilder key(Key key) {
			if (!keys.isEmpty() && key.tick <= keys.getLast().tick) throw new IllegalArgumentException("Keyframes must be in order of time");
			keys.add(key);
			return this;
		}

		public Keyframes linear() {
			return new Keyframes(keys, Spline.LINEAR);
		}

		public Keyframes catmullRom() {
			return new Keyframes(keys, Spline.CATMULL_ROM);
		}

		public Keyframes bezier() {
			return new Keyframes(keys, Spline.BEZIER);
		}
	}

	/** A path through {@link Key}s; it lasts until the last key. Angles turn the short way between neighbouring keys. */
	public record Keyframes(List<Key> keys, Spline spline) implements Clip {
		public Keyframes {
			if (keys.isEmpty()) throw new IllegalArgumentException("No keyframes");
			for (int i = 0; i < keys.size(); i++) {
				float tick = keys.get(i).tick;
				if (!Float.isFinite(tick) || i > 0 && tick <= keys.get(i - 1).tick) throw new IllegalArgumentException("Keyframes must be in order of time");
			}
			keys = List.copyOf(keys);
		}

		@Override
		public int length() {
			return Mth.ceil(keys.getLast().tick);
		}

		@Override
		public Shot at(float ticks) {
			int n = keys.size();
			if (n == 1 || ticks <= keys.getFirst().tick) return keys.getFirst().shot;
			if (ticks >= keys.getLast().tick) return keys.getLast().shot;
			int i = 0;
			while (keys.get(i + 1).tick < ticks) i++;
			Key k0 = keys.get(Math.max(i - 1, 0)), k1 = keys.get(i), k2 = keys.get(i + 1), k3 = keys.get(Math.min(i + 2, n - 1));
			float u = k2.easing.apply((ticks - k1.tick) / (k2.tick - k1.tick));
			if (spline == Spline.LINEAR) return k1.shot.lerp(k2.shot, u);

			Vec3 p0 = k0.shot.pos, p1 = k1.shot.pos, p2 = k2.shot.pos, p3 = k3.shot.pos;
			// Catmull-Rom as a Bezier: handles a sixth of the neighbour-to-neighbour chord away from each key
			Vec3 h1 = spline == Spline.BEZIER && k1.handleOut != null ? k1.handleOut : p1.add(p2.subtract(p0).scale(1 / 6.0));
			Vec3 h2 = spline == Spline.BEZIER && k2.handleIn != null ? k2.handleIn : p2.subtract(p3.subtract(p1).scale(1 / 6.0));
			Vec3 pos = bezier(p1, h1, h2, p2, u);
			// angles unwrapped against their neighbours so the spline never takes the long way round
			float y1 = k1.shot.yRot, y0 = y1 + Mth.wrapDegrees(k0.shot.yRot - y1), y2 = y1 + Mth.wrapDegrees(k2.shot.yRot - y1), y3 = y2 + Mth.wrapDegrees(k3.shot.yRot - y2);
			float fov = k1.shot.fov > 0 && k2.shot.fov > 0 ? scalar(k0.shot.fov > 0 ? k0.shot.fov : k1.shot.fov, k1.shot.fov, k2.shot.fov, k3.shot.fov > 0 ? k3.shot.fov : k2.shot.fov, u)
				: u < 1 ? k1.shot.fov : k2.shot.fov;
			return new Shot(pos, scalar(y0, y1, y2, y3, u), scalar(k0.shot.xRot, k1.shot.xRot, k2.shot.xRot, k3.shot.xRot, u), fov,
				scalar(k0.shot.roll, k1.shot.roll, k2.shot.roll, k3.shot.roll, u));
		}

		private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, float t) {
			double s = 1 - t;
			return a.scale(s * s * s).add(b.scale(3 * s * s * t)).add(c.scale(3 * s * t * t)).add(d.scale(t * t * t));
		}

		/** Catmull-Rom through v1 and v2. */
		private static float scalar(float v0, float v1, float v2, float v3, float t) {
			float h1 = v1 + (v2 - v0) / 6, h2 = v2 - (v3 - v1) / 6, s = 1 - t;
			return s * s * s * v1 + 3 * s * s * t * h1 + 3 * s * t * t * h2 + t * t * t * v2;
		}
	}

	// ---- cameras from a .glb ----

	/**
	 * The Blender camera named {@code camera} in {@code assets/<ns>/clay/<path>.glb}, moving as the first animation that
	 * animates it (or its parents) does. Clay's {@code ClayModel.camera(name)} is a shortcut. The scene is placed at the
	 * world origin unless {@link ModelCamera#at moved}. Only the client reads the file, so on a server a model camera's
	 * length isn't known.
	 */
	public static ModelCamera camera(Identifier model, String camera) {
		return new ModelCamera(model, camera, Vec3.ZERO, 0, 0);
	}

	/** See {@link #camera}. {@code ticks} 0 lasts as long as the camera's animation. */
	public record ModelCamera(Identifier model, String camera, Vec3 origin, float yaw, int ticks) implements Clip {
		/** The scene with its Blender origin at {@code origin}, turned {@code yaw} degrees like an entity's rotation. */
		public ModelCamera at(Vec3 origin, float yaw) {
			return new ModelCamera(model, camera, origin, yaw, ticks);
		}

		/** Plays for {@code ticks} instead of the animation's length, e.g. to hold a camera that doesn't move. */
		public ModelCamera length(int ticks) {
			return new ModelCamera(model, camera, origin, yaw, ticks);
		}

		@Override
		public int length() {
			if (ticks > 0) return ticks;
			Resolved r = resolve();
			return r == null || r.clip == null ? 0 : Mth.ceil(r.clip.duration() * 20);
		}

		@Override
		public Shot at(float ticks) {
			Resolved r = resolve();
			if (r == null) return new Shot(origin, yaw, 0);
			r.rig.reset();
			if (r.clip != null) r.rig.sample(r.clip, ticks / 20, false);
			Matrix4f global = r.rig.build(null)[r.camera.node()];
			return cameraShot(global, r.camera.yfov(), origin, yaw);
		}

		private @Nullable Resolved resolve() {
			Glb glb = models.apply(model);
			if (glb == null) return null;
			Resolved cached = RESOLVED.get(this.model + "#" + camera);
			if (cached != null && cached.glb == glb) return cached;
			Glb.Camera cam = glb.cameras.stream().filter(c -> c.name().equals(camera)).findFirst().orElse(null);
			if (cam == null) return null;
			Glb.Animation clip = null;
			for (Glb.Animation a : glb.animations) {
				if (a.channels().stream().anyMatch(c -> ancestorOrSelf(glb, c.node(), cam.node()))) {
					clip = a;
					break;
				}
			}
			Resolved r = new Resolved(glb, new Rig(glb), cam, clip);
			RESOLVED.put(this.model + "#" + camera, r);
			return r;
		}
	}

	private record Resolved(Glb glb, Rig rig, Glb.Camera camera, Glb.@Nullable Animation clip) {}

	/** Render thread only. */
	private static final Map<String, Resolved> RESOLVED = new HashMap<>();

	private static boolean ancestorOrSelf(Glb glb, int ancestor, int node) {
		for (int i = node; i >= 0; i = glb.nodes.get(i).parent()) if (i == ancestor) return true;
		return false;
	}

	/**
	 * The shot of a glTF camera (looking down its -Z, +Y up) with model-space transform {@code global}, its scene placed at
	 * {@code origin} turned {@code yaw} degrees. Roll is measured the way {@code Camera.setRotation} applies it.
	 */
	static Shot cameraShot(Matrix4f global, float yfov, Vec3 origin, float yaw) {
		Matrix4f world = new Matrix4f().rotateY(-yaw * Mth.DEG_TO_RAD).mul(global);
		Vector3f pos = world.getTranslation(new Vector3f());
		Vector3f forward = world.transformDirection(new Vector3f(0, 0, -1)).normalize(), up = world.transformDirection(new Vector3f(0, 1, 0)).normalize();
		float yRot = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90;
		float xRot = (float) -(Mth.atan2(forward.y, Math.sqrt(forward.x * forward.x + forward.z * forward.z)) * Mth.RAD_TO_DEG);
		Quaternionf level = new Quaternionf().rotationYXZ((float) Math.PI - yRot * Mth.DEG_TO_RAD, -xRot * Mth.DEG_TO_RAD, 0);
		Vector3f right = level.transform(new Vector3f(1, 0, 0)), levelUp = level.transform(new Vector3f(0, 1, 0));
		float roll = (float) (Math.atan2(-up.dot(right), up.dot(levelUp)) * Mth.RAD_TO_DEG);
		return new Shot(origin.add(pos.x, pos.y, pos.z), yRot, xRot, yfov * Mth.RAD_TO_DEG, roll);
	}

	// ---- sequences ----

	/** How a clip in a sequence takes over from the one before it, over its first ticks. */
	public enum Transition {
		/** At once. */
		CUT,
		/** The camera glides from where the previous clip ended. */
		BLEND,
		/** Through black: the previous clip fades out over its last half of the ticks, this one in over its first half. */
		FADE
	}

	public record Entry(Clip clip, Transition transition, int ticks) {}

	/** Starts a sequence: {@code sequence().then(a).then(b, Transition.FADE, 20).build()}. */
	public static SequenceBuilder sequence() {
		return new SequenceBuilder();
	}

	public static final class SequenceBuilder {
		private final List<Entry> entries = new ArrayList<>();

		private SequenceBuilder() {}

		public SequenceBuilder then(Clip clip) {
			return then(clip, Transition.CUT, 0);
		}

		public SequenceBuilder then(Clip clip, Transition transition, int ticks) {
			entries.add(new Entry(clip, transition, ticks));
			return this;
		}

		public Sequence build() {
			return new Sequence(entries);
		}
	}

	/** Clips one after the other; it lasts as long as all of them. */
	public record Sequence(List<Entry> entries) implements Clip {
		public Sequence {
			if (entries.isEmpty()) throw new IllegalArgumentException("Empty sequence");
			entries = List.copyOf(entries);
		}

		@Override
		public int length() {
			int length = 0;
			for (Entry e : entries) length += e.clip.length();
			return length;
		}

		/** The entry playing at {@code ticks}; offsets[0] receives its start time. */
		private int find(float ticks, int[] offset) {
			int start = 0;
			for (int i = 0; i < entries.size(); i++) {
				int length = entries.get(i).clip.length();
				if (ticks < start + length || i == entries.size() - 1) {
					offset[0] = start;
					return i;
				}
				start += length;
			}
			throw new IllegalStateException();
		}

		@Override
		public Shot at(float ticks) {
			int[] offset = new int[1];
			int i = find(ticks, offset);
			Entry e = entries.get(i);
			float local = ticks - offset[0];
			Shot shot = e.clip.at(local);
			if (e.transition == Transition.BLEND && i > 0 && local < e.ticks) {
				Clip previous = entries.get(i - 1).clip;
				shot = previous.at(previous.length()).lerp(shot, Mth.smoothstep(local / e.ticks));
			}
			return shot;
		}

		@Override
		public Overlay overlay(float ticks) {
			int[] offset = new int[1];
			int i = find(ticks, offset);
			Entry e = entries.get(i);
			float local = ticks - offset[0], length = e.clip.length();
			Overlay overlay = e.clip.overlay(local);
			float black = 0;
			if (e.transition == Transition.FADE && i > 0 && e.ticks > 0) black = 1 - local / (e.ticks / 2f);
			if (i + 1 < entries.size() && entries.get(i + 1).transition == Transition.FADE && entries.get(i + 1).ticks > 0) {
				black = Math.max(black, (local - (length - entries.get(i + 1).ticks / 2f)) / (entries.get(i + 1).ticks / 2f));
			}
			black = Math.clamp(black, 0, 1);
			return black > 0 ? overlay.with(new Overlay((int) (black * 255) << 24, 0)) : overlay;
		}

		@Override
		public void fire(float from, float to) {
			int start = 0;
			for (Entry e : entries) {
				e.clip.fire(from - start, to - start);
				start += e.clip.length();
			}
		}

		@Override
		public void interrupt(float ticks) {
			int[] offset = new int[1];
			int i = find(ticks, offset);
			entries.get(i).clip.interrupt(ticks - offset[0]);
		}
	}

	// ---- effects and callbacks ----

	/** A screen effect added to a clip with {@link Clip#shake}, {@link Clip#fade} or {@link Clip#letterbox}. */
	public sealed interface Effect permits Shake, Fade, Letterbox {}

	public record Shake(float degrees, float frequency) implements Effect {}

	public record Fade(int in, int out, int rgb) implements Effect {}

	public record Letterbox(float size, int in, int out) implements Effect {}

	public record Styled(Clip clip, Effect effect) implements Clip {
		@Override
		public int length() {
			return clip.length();
		}

		@Override
		public Shot at(float ticks) {
			Shot shot = clip.at(ticks);
			if (!(effect instanceof Shake(float degrees, float frequency))) return shot;
			// a few incommensurate sines: wobbly, smooth and the same every time the clip plays
			double t = ticks / 20.0 * frequency * Math.PI * 2;
			float yaw = (float) (Math.sin(t) * 0.6 + Math.sin(t * 2.31 + 1.7) * 0.4), pitch = (float) (Math.sin(t * 1.13 + 4.1) * 0.6 + Math.sin(t * 2.71 + 0.3) * 0.4);
			float roll = (float) (Math.sin(t * 0.87 + 2.2) * 0.5);
			return new Shot(shot.pos(), shot.yRot() + yaw * degrees, shot.xRot() + pitch * degrees, shot.fov(), shot.roll() + roll * degrees);
		}

		@Override
		public Overlay overlay(float ticks) {
			Overlay inner = clip.overlay(ticks);
			int length = clip.length();
			return switch (effect) {
				case Shake s -> inner;
				case Fade(int in, int out, int rgb) -> {
					float a = Math.max(in > 0 ? 1 - ticks / in : 0, out > 0 ? (ticks - (length - out)) / out : 0);
					a = Math.clamp(a, 0, 1);
					yield a > 0 ? inner.with(new Overlay((int) (a * 255) << 24 | rgb, 0)) : inner;
				}
				case Letterbox(float size, int in, int out) -> {
					float k = Math.min(in > 0 ? ticks / in : 1, out > 0 ? (length - ticks) / out : 1);
					yield inner.with(new Overlay(0, size * Mth.smoothstep(Math.clamp(k, 0, 1))));
				}
			};
		}

		@Override
		public void fire(float from, float to) {
			clip.fire(from, to);
		}

		@Override
		public void interrupt(float ticks) {
			clip.interrupt(ticks);
		}
	}

	/** Callbacks around a clip. They run on the client, so a clip with them can't be sent with {@link #playFor}. */
	public record Hooked(Clip clip, @Nullable Runnable onStart, @Nullable Runnable onEnd) implements Clip {
		@Override
		public int length() {
			return clip.length();
		}

		@Override
		public Shot at(float ticks) {
			return clip.at(ticks);
		}

		@Override
		public Overlay overlay(float ticks) {
			return clip.overlay(ticks);
		}

		@Override
		public void fire(float from, float to) {
			if (onStart != null && from < 0 && to >= 0) onStart.run();
			clip.fire(from, to);
			int length = clip.length();
			if (onEnd != null && from < length && to >= length) onEnd.run();
		}

		@Override
		public void interrupt(float ticks) {
			clip.interrupt(ticks);
			if (onEnd != null && ticks >= 0 && ticks < clip.length()) onEnd.run();
		}
	}

	// ---- playing (client) ----

	/** The client's level, and its loaded models; set by ClayClient, empty on a dedicated server. */
	static Supplier<@Nullable Level> clientLevel = () -> null;
	static Function<Identifier, @Nullable Glb> models = id -> null;

	private static @Nullable Level level;
	private static @Nullable Clip clip;
	private static long start;
	/** Ticks into the clip up to which callbacks have run. */
	private static float fired;

	/** Plays {@code clip} on this client, replacing any shot already playing (whose onEnd callbacks run). */
	public static void play(Clip clip) {
		Level in = clientLevel.get();
		if (in == null) return;
		stop();
		ClayCamera.clip = clip;
		level = in;
		start = in.getGameTime();
		fired = 0;
		clip.fire(-1, 0);
	}

	public static void stop() {
		Clip stopped = clip;
		clip = null;
		level = null;
		if (stopped != null && fired < stopped.length()) stopped.interrupt(fired);
	}

	public static boolean playing() {
		return clip != null && level == clientLevel.get() && level.getGameTime() - start < clip.length();
	}

	/** This frame's shot; null when none plays. */
	public static @Nullable Shot shot(float partial) {
		return playing() ? clip.at(level.getGameTime() - start + partial) : null;
	}

	/** This frame's fade and letterbox. */
	public static Overlay overlay(float partial) {
		return playing() ? clip.overlay(level.getGameTime() - start + partial) : Overlay.NONE;
	}

	/** Runs callbacks as time passes, and ends the shot when it is over. Called every client tick. */
	static void tick() {
		if (clip == null) return;
		if (level != clientLevel.get()) { // left the world: cut short
			stop();
			return;
		}
		Clip playing = clip;
		float from = fired, now = Math.min(level.getGameTime() - start, playing.length());
		if (now <= from) return;
		fired = now;
		playing.fire(from, now);
		if (clip != playing) return; // a callback started or stopped a shot
		if (now >= playing.length()) {
			clip = null;
			level = null;
		}
	}

	// ---- server-started shots ----

	/** Starts {@code clip} on {@code player}'s client, if Clay is installed there. Callbacks and code paths can't be sent. */
	public static void playFor(ServerPlayer player, Clip clip) {
		checkSendable(clip, 0);
		if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ServerPlayNetworking.send(player, new Payload(Optional.of(clip)));
	}

	/** Stops whatever shot {@code player}'s client is playing. */
	public static void stopFor(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ServerPlayNetworking.send(player, new Payload(Optional.empty()));
	}

	private static void checkSendable(Clip clip, int depth) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("Clip nested deeper than " + MAX_DEPTH);
		switch (clip) {
			case Timed t when !(t.path instanceof Orbit) -> throw new IllegalArgumentException("A Path written in code can't be sent; use keyframes, an orbit or a model camera");
			case Hooked h -> throw new IllegalArgumentException("onStart/onEnd callbacks run on the client and can't be sent");
			case Styled s -> checkSendable(s.clip, depth + 1);
			case Sequence s -> s.entries.forEach(e -> checkSendable(e.clip, depth + 1));
			default -> {}
		}
	}

	/** Bounds on what a client decodes, so a bad packet can't make it allocate without limit. */
	private static final int MAX_DEPTH = 16, MAX_ITEMS = 1024;
	/** Clips and keyframes one packet may hold in total. */
	private static final int MAX_PARTS = 2048;
	/** Longest orbit radius in blocks, and longest clip a packet can ask for (an hour). */
	private static final float MAX_ORBIT = 256;
	private static final int MAX_TICKS = 72_000;

	/** Plays the clip, or stops the camera when empty. */
	public record Payload(Optional<Clip> clip) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("clay", "camera"));
		public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				FriendlyByteBuf out = new FriendlyByteBuf(buf);
				out.writeBoolean(payload.clip.isPresent());
				payload.clip.ifPresent(c -> write(out, c));
			},
			buf -> {
				FriendlyByteBuf in = new FriendlyByteBuf(buf);
				return new Payload(in.readBoolean() ? Optional.of(read(in, 0)) : Optional.empty());
			});

		@Override
		public Type<Payload> type() {
			return TYPE;
		}
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
	}

	static void write(FriendlyByteBuf out, Clip clip) {
		switch (clip) {
			case Timed(Path path, int length) -> {
				Orbit o = (Orbit) path;
				out.writeByte(0);
				writeVec(out, o.target);
				out.writeFloat(o.distance).writeFloat(o.yRot).writeFloat(o.xRot).writeFloat(o.speed);
				out.writeVarInt(length);
			}
			case Keyframes k -> {
				out.writeByte(1);
				out.writeEnum(k.spline);
				out.writeVarInt(k.keys.size());
				for (Key key : k.keys) {
					out.writeFloat(key.tick);
					writeShot(out, key.shot);
					out.writeEnum(key.easing);
					writeHandle(out, key.handleIn);
					writeHandle(out, key.handleOut);
				}
			}
			case ModelCamera m -> {
				out.writeByte(2);
				out.writeIdentifier(m.model);
				out.writeUtf(m.camera);
				writeVec(out, m.origin);
				out.writeFloat(m.yaw);
				out.writeVarInt(m.ticks);
			}
			case Sequence s -> {
				out.writeByte(3);
				out.writeVarInt(s.entries.size());
				for (Entry e : s.entries) {
					out.writeEnum(e.transition);
					out.writeVarInt(e.ticks);
					write(out, e.clip);
				}
			}
			case Styled(Clip inner, Effect effect) -> {
				switch (effect) {
					case Shake(float degrees, float frequency) -> out.writeByte(4).writeFloat(degrees).writeFloat(frequency);
					case Fade(int in, int fadeOut, int rgb) -> out.writeByte(5).writeVarInt(in).writeVarInt(fadeOut).writeInt(rgb);
					case Letterbox(float size, int in, int boxOut) -> out.writeByte(6).writeFloat(size).writeVarInt(in).writeVarInt(boxOut);
				}
				write(out, inner);
			}
			case Hooked h -> throw new IllegalArgumentException("onStart/onEnd callbacks can't be sent");
		}
	}

	static Clip read(FriendlyByteBuf in, int depth) {
		return read(in, depth, new int[] {MAX_PARTS});
	}

	/** {@code budget} counts down the clips and keyframes left for the whole packet, which bounds the work they cost every frame. */
	private static Clip read(FriendlyByteBuf in, int depth, int[] budget) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("Clip nested too deep");
		spend(budget, 1);
		int type = in.readByte();
		return switch (type) {
			case 0 -> new Timed(new Orbit(readVec(in), Math.clamp(finite(in), 0.1f, MAX_ORBIT), finite(in), finite(in), finite(in)), ticks(in));
			case 1 -> {
				Spline spline = in.readEnum(Spline.class);
				int n = count(in);
				spend(budget, n);
				List<Key> keys = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					keys.add(new Key(Math.clamp(finite(in), 0, MAX_TICKS), readShot(in), in.readEnum(Easing.class), readHandle(in), readHandle(in)));
				}
				yield new Keyframes(keys, spline);
			}
			case 2 -> new ModelCamera(in.readIdentifier(), in.readUtf(256), readVec(in), finite(in), ticks(in));
			case 3 -> {
				int n = count(in);
				List<Entry> entries = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					Transition transition = in.readEnum(Transition.class);
					int ticks = ticks(in);
					entries.add(new Entry(read(in, depth + 1, budget), transition, ticks));
				}
				yield new Sequence(entries);
			}
			case 4 -> {
				Effect e = new Shake(Math.clamp(finite(in), 0, 90), Math.clamp(finite(in), 0, 100));
				yield new Styled(read(in, depth + 1, budget), e);
			}
			case 5 -> {
				Effect e = new Fade(ticks(in), ticks(in), in.readInt() & 0xFFFFFF);
				yield new Styled(read(in, depth + 1, budget), e);
			}
			case 6 -> {
				Effect e = new Letterbox(Math.clamp(finite(in), 0, 0.5f), ticks(in), ticks(in));
				yield new Styled(read(in, depth + 1, budget), e);
			}
			default -> throw new IllegalArgumentException("Unknown clip type " + type);
		};
	}

	private static float finite(FriendlyByteBuf in) {
		float f = in.readFloat();
		if (!Float.isFinite(f)) throw new IllegalArgumentException("Bad number in clip");
		return f;
	}

	private static int ticks(FriendlyByteBuf in) {
		int ticks = in.readVarInt();
		if (ticks < 0 || ticks > MAX_TICKS) throw new IllegalArgumentException("Bad clip length " + ticks);
		return ticks;
	}

	private static void spend(int[] budget, int parts) {
		if ((budget[0] -= parts) < 0) throw new IllegalArgumentException("Clip has more than " + MAX_PARTS + " parts");
	}

	private static int count(FriendlyByteBuf in) {
		int n = in.readVarInt();
		if (n < 1 || n > MAX_ITEMS) throw new IllegalArgumentException("Bad clip size " + n);
		return n;
	}

	private static void writeVec(FriendlyByteBuf out, Vec3 v) {
		out.writeDouble(v.x).writeDouble(v.y).writeDouble(v.z);
	}

	private static Vec3 readVec(FriendlyByteBuf in) {
		return new Vec3(coordinate(in), coordinate(in), coordinate(in));
	}

	/** One coordinate, within the world border's reach (each on its own: a sum lets huge opposites cancel out). */
	private static double coordinate(FriendlyByteBuf in) {
		double c = in.readDouble();
		if (!(Math.abs(c) <= 3.0E7)) throw new IllegalArgumentException("Bad position " + c);
		return c;
	}

	private static void writeHandle(FriendlyByteBuf out, @Nullable Vec3 handle) {
		out.writeBoolean(handle != null);
		if (handle != null) writeVec(out, handle);
	}

	private static @Nullable Vec3 readHandle(FriendlyByteBuf in) {
		return in.readBoolean() ? readVec(in) : null;
	}

	private static void writeShot(FriendlyByteBuf out, Shot shot) {
		writeVec(out, shot.pos);
		out.writeFloat(shot.yRot).writeFloat(shot.xRot).writeFloat(shot.fov).writeFloat(shot.roll);
	}

	private static Shot readShot(FriendlyByteBuf in) {
		return new Shot(readVec(in), finite(in), finite(in), Math.clamp(finite(in), 0, 170), finite(in));
	}
}
