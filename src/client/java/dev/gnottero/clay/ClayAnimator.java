package dev.gnottero.clay;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Plays a {@link ClayModel}'s Blender animations for one instance, such as one entity. Keep one per entity (client side)
 * and draw with {@link ClayModel#submit(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector,
 * int, int, double, ClayAnimator)}:
 * <pre>{@code
 * animator.update((entity.tickCount + partialTick) / 20f);   // your clock, in seconds
 * if (entity.isAttacking()) animator.play("attack", false, 0.15f);
 * else animator.play(entity.walking() ? "walk" : "idle", true, 0.25f);
 * animator.override("head", new Matrix4f().rotateY(headYaw));
 * MODEL.submit(pose, collector, light, overlay, distanceSq, animator);
 * }</pre>
 * Clips are the glTF animations in the .glb, by name (Blender's action names). Time only moves in {@link #update}, so the
 * animation pauses with whatever clock you feed it. Nothing here allocates once an override exists for each node you set.
 * The animator only holds which clips play and when: the same animator can drive a model's LOD too, which samples its own
 * clips of the same names.
 */
public final class ClayAnimator {
	private final ClayModel model;
	private @Nullable String clip, previous;
	private boolean loop, previousLoop, fading;
	private float time, previousTime, fade, fadeLength, speed = 1, clock = Float.NaN;
	private final Map<String, Matrix4f> overrides = new HashMap<>();
	private final Map<String, Matrix4f> active = new HashMap<>();

	public ClayAnimator(ClayModel model) {
		this.model = model;
	}

	/** Loops {@code clip}. */
	public ClayAnimator play(String clip) {
		return play(clip, true, 0);
	}

	public ClayAnimator play(String clip, boolean loop) {
		return play(clip, loop, 0);
	}

	/**
	 * Starts {@code clip} from its beginning, blending over from the current pose for {@code fadeSeconds}. Asking for the
	 * clip that is already playing (and not finished) changes nothing, so this can be called every frame.
	 */
	public ClayAnimator play(String clip, boolean loop, float fadeSeconds) {
		if (clip.equals(this.clip) && loop == this.loop && !finished()) return this;
		fadeTo(clip, loop, fadeSeconds);
		return this;
	}

	/** Blends back to the rest pose over {@code fadeSeconds}. */
	public ClayAnimator stop(float fadeSeconds) {
		if (clip != null) fadeTo(null, false, fadeSeconds);
		return this;
	}

	private void fadeTo(@Nullable String next, boolean nextLoop, float fadeSeconds) {
		if (fadeSeconds > 0) {
			// ponytail: a fade interrupting a fade leaves from whichever clip shows more, not the blend of both
			if (!fading || weight() >= 0.5f) {
				previous = clip;
				previousTime = time;
				previousLoop = loop;
			}
			fading = true;
			fade = 0;
			fadeLength = fadeSeconds;
		} else {
			fading = false;
			previous = null;
		}
		clip = next;
		loop = nextLoop;
		time = 0;
	}

	/** Multiplies playback speed (1 = as exported, negative plays backwards). */
	public ClayAnimator speed(float speed) {
		this.speed = speed;
		return this;
	}

	/** Advances to {@code seconds} on your clock. The first call only sets the clock. */
	public void update(float seconds) {
		float delta = Float.isNaN(clock) ? 0 : Math.max(0, seconds - clock);
		clock = seconds;
		time += delta * speed;
		if (fading) {
			previousTime += delta * speed;
			fade += delta;
			if (fade >= fadeLength) {
				fading = false;
				previous = null;
			}
		}
	}

	/**
	 * Transforms {@code node} by {@code transform} on top of the animation, in its parent's space like the node's own
	 * transform (and like the map passed to {@link ClayModel#submit}). The matrix is copied.
	 */
	public ClayAnimator override(String node, Matrix4fc transform) {
		Matrix4f m = overrides.computeIfAbsent(node, n -> new Matrix4f());
		m.set(transform);
		active.put(node, m);
		return this;
	}

	public ClayAnimator clearOverride(String node) {
		active.remove(node);
		return this;
	}

	public ClayAnimator clearOverrides() {
		active.clear();
		return this;
	}

	/** The clip playing (or fading in), or null at rest. */
	public @Nullable String clip() {
		return clip;
	}

	/** Seconds since the current clip started, scaled by speed. */
	public float time() {
		return time;
	}

	/** True once a clip played with {@code loop = false} has reached its end. */
	public boolean finished() {
		return clip != null && !loop && time >= model.duration(clip);
	}

	// read by ClayModel when sampling

	@Nullable String previous() {
		return previous;
	}

	float previousTime() {
		return previousTime;
	}

	boolean loop() {
		return loop;
	}

	boolean previousLoop() {
		return previousLoop;
	}

	/** Whether the pose is a blend from {@link #previous()} (null = the rest pose) into {@link #clip()}. */
	boolean fading() {
		return fading;
	}

	/** How far the current clip has faded in: 1 when no fade is running. */
	float weight() {
		return fading ? Math.clamp(fade / fadeLength, 0, 1) : 1;
	}

	Map<String, Matrix4f> overrides() {
		return active;
	}
}
