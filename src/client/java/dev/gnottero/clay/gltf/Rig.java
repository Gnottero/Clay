package dev.gnottero.clay.gltf;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link Glb}'s node hierarchy and animation clips into a palette: one model-space matrix per node, then one per
 * joint of every skin ({@code joint global * inverse bind}). That palette is what both renderers move vertices with.
 * Everything is preallocated, so evaluating a pose doesn't allocate. Not thread-safe: one per render thread user.
 */
public final class Rig {
	public final Glb glb;
	public final int nodeCount, paletteSize;
	/** Where each skin's joints start in the palette. */
	public final int[] skinOffset;
	public final Matrix4f[] palette;
	private final Map<String, Glb.Animation> clips = new HashMap<>();
	/** Rest pose, and two working poses: translation xyz, rotation xyzw, scale xyz per node. */
	private final float[] restT, restR, restS, t, r, s, t2, r2, s2;
	private boolean posed;

	public Rig(Glb glb) {
		this.glb = glb;
		nodeCount = glb.nodes.size();
		skinOffset = skinOffsets(glb);
		paletteSize = nodeCount + glb.skins.stream().mapToInt(skin -> skin.joints().length).sum();
		palette = new Matrix4f[paletteSize];
		for (int i = 0; i < paletteSize; i++) palette[i] = new Matrix4f();
		for (Glb.Animation clip : glb.animations) clips.putIfAbsent(clip.name(), clip);
		restT = new float[nodeCount * 3];
		restR = new float[nodeCount * 4];
		restS = new float[nodeCount * 3];
		Vector3f v = new Vector3f();
		Quaternionf q = new Quaternionf();
		for (int i = 0; i < nodeCount; i++) {
			Matrix4f local = glb.nodes.get(i).local();
			local.getTranslation(v).get(restT, i * 3);
			local.getUnnormalizedRotation(q).normalize();
			restR[i * 4] = q.x; restR[i * 4 + 1] = q.y; restR[i * 4 + 2] = q.z; restR[i * 4 + 3] = q.w;
			local.getScale(v).get(restS, i * 3);
		}
		t = restT.clone(); r = restR.clone(); s = restS.clone();
		t2 = restT.clone(); r2 = restR.clone(); s2 = restS.clone();
	}

	/** Where each skin's joints start in a palette built from {@code glb}: after the nodes, skins in order. */
	public static int[] skinOffsets(Glb glb) {
		int[] offsets = new int[glb.skins.size()];
		int next = glb.nodes.size();
		for (int i = 0; i < offsets.length; i++) {
			offsets[i] = next;
			next += glb.skins.get(i).joints().length;
		}
		return offsets;
	}

	public Glb.@Nullable Animation clip(String name) {
		return clips.get(name);
	}

	public Map<String, Glb.Animation> clips() {
		return clips;
	}

	/** Starts a pose from the rest pose; without {@link #sample} the node transforms are used exactly as exported. */
	public void reset() {
		posed = false;
	}

	/** Poses the nodes {@code clip} animates as it is at {@code time} seconds (wrapped when looping, held at the end otherwise). */
	public void sample(Glb.Animation clip, float time, boolean loop) {
		start();
		apply(clip, time, loop, t, r, s);
	}

	/** Like {@link #sample}, but a crossfade: {@code from} at {@code fromTime} blended into {@code to} at {@code toTime} by {@code weight} (0-1). */
	public void sampleBlend(Glb.@Nullable Animation from, float fromTime, boolean fromLoop, Glb.@Nullable Animation to, float toTime, boolean toLoop, float weight) {
		start();
		System.arraycopy(restT, 0, t2, 0, t2.length);
		System.arraycopy(restR, 0, r2, 0, r2.length);
		System.arraycopy(restS, 0, s2, 0, s2.length);
		if (from != null) apply(from, fromTime, fromLoop, t, r, s);
		if (to != null) apply(to, toTime, toLoop, t2, r2, s2);
		for (int i = 0; i < nodeCount; i++) {
			Keyframes.lerp(t, i * 3, t2, i * 3, weight, 3, t, i * 3);
			Keyframes.lerp(s, i * 3, s2, i * 3, weight, 3, s, i * 3);
			Keyframes.slerp(r, i * 4, r2, i * 4, weight, r, i * 4);
		}
	}

	private void start() {
		if (!posed) {
			System.arraycopy(restT, 0, t, 0, t.length);
			System.arraycopy(restR, 0, r, 0, r.length);
			System.arraycopy(restS, 0, s, 0, s.length);
			posed = true;
		}
	}

	private static void apply(Glb.Animation clip, float time, boolean loop, float[] t, float[] r, float[] s) {
		float at = clip.duration() <= 0 ? 0 : loop ? time - (float) Math.floor(time / clip.duration()) * clip.duration() : Math.clamp(time, 0, clip.duration());
		List<Glb.Channel> channels = clip.channels();
		for (int i = 0; i < channels.size(); i++) { // indexed: an iterator would be garbage every frame
			Glb.Channel channel = channels.get(i);
			switch (channel.path()) {
				case TRANSLATION -> Keyframes.sample(channel, at, t, channel.node() * 3);
				case ROTATION -> Keyframes.sample(channel, at, r, channel.node() * 4);
				case SCALE -> Keyframes.sample(channel, at, s, channel.node() * 3);
			}
		}
	}

	/**
	 * Builds the palette from the current pose. {@code overrides} (by node name, may be null) are multiplied onto a node's
	 * local transform, so they move it and its children on top of the animation. Returns {@link #palette}.
	 */
	public Matrix4f[] build(@Nullable Map<String, ? extends Matrix4fc> overrides) {
		for (int i = 0; i < nodeCount; i++) {
			Glb.Node node = glb.nodes.get(i);
			Matrix4f m = node.parent() < 0 ? palette[i].identity() : palette[i].set(palette[node.parent()]);
			if (posed) {
				m.translate(t[i * 3], t[i * 3 + 1], t[i * 3 + 2])
					.rotate(QUAT.set(r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3]))
					.scale(s[i * 3], s[i * 3 + 1], s[i * 3 + 2]);
			} else {
				m.mul(node.local());
			}
			Matrix4fc extra = overrides == null || overrides.isEmpty() ? null : overrides.get(node.name());
			if (extra != null) m.mul(extra);
		}
		for (int k = 0; k < glb.skins.size(); k++) {
			Glb.Skin skin = glb.skins.get(k);
			for (int j = 0; j < skin.joints().length; j++) palette[skinOffset[k] + j].set(palette[skin.joints()[j]]).mul(skin.inverseBind()[j]);
		}
		posed = false;
		return palette;
	}

	private static final Quaternionf QUAT = new Quaternionf(); // render thread only
}
