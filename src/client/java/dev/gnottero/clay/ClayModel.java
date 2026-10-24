package dev.gnottero.clay;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Rig;
import dev.gnottero.clay.render.ClayMesh;
import dev.gnottero.clay.render.ClayRenderers;
import dev.gnottero.clay.render.CpuRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A .glb model for entities and block entities. Create once (e.g. a static field in your renderer), draw from
 * {@code EntityRenderer.submit} / {@code BlockEntityRenderer.submit}:
 * <pre>{@code
 * static final ClayModel MODEL = ClayModel.of(Identifier.fromNamespaceAndPath("mymod", "golem"))
 *     .lod(Identifier.fromNamespaceAndPath("mymod", "golem_low"), 32);
 * ...
 * MODEL.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.distanceToCameraSq,
 *     Map.of("head", new Matrix4f().rotateY(headYaw)));
 * }</pre>
 * Node transforms are applied on top of the node's own Blender transform, in its parent's space, by node name.
 * For Blender animations and skinned meshes, draw with a {@link ClayAnimator} instead.
 * Model space: Blender origin = the entity's feet, 1 Blender unit = 1 block.
 * <p>
 * The model is drawn by the active {@link dev.gnottero.clay.render.ClayRenderer}: from GPU buffers when it can, by
 * streaming vertices from the CPU otherwise.
 */
public final class ClayModel {
	/** Baked meshes by model, shared by every ClayModel of that file and rebuilt after a resource reload. Render thread only. */
	private static final Map<Identifier, Cached> MESHES = new HashMap<>();

	private final Identifier id;
	private final List<Lod> lods = new ArrayList<>();
	private @Nullable Baked baked;

	private record Lod(double minDistanceSq, ClayModel model) {}

	private ClayModel(Identifier id) {
		this.id = id;
	}

	/** {@code ns:path} refers to {@code assets/ns/clay/path.glb}. */
	public static ClayModel of(Identifier id) {
		return new ClayModel(id);
	}

	/** Use {@code lowPoly} instead of this model when the camera is at least {@code distance} blocks away. */
	public ClayModel lod(Identifier lowPoly, float distance) {
		lods.add(new Lod((double) distance * distance, new ClayModel(lowPoly)));
		lods.sort((a, b) -> Double.compare(b.minDistanceSq, a.minDistanceSq));
		return this;
	}

	/** Rest-pose bounds in model space, for sizing your entity's culling box. Empty until resources are loaded. */
	public AABB bounds() {
		Baked b = baked();
		return b == null ? new AABB(0, 0, 0, 0, 0, 0) : b.mesh.bounds;
	}

	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq) {
		submit(pose, collector, light, overlay, distanceToCameraSq, Map.of());
	}

	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq,
					   Map<String, ? extends Matrix4fc> nodeTransforms) {
		submit(pose, collector, light, overlay, distanceToCameraSq, nodeTransforms, null);
	}

	/** Also draws the block breaking cracks over the model when {@code crumbling} (a block entity's {@code state.breakProgress}) is set. */
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq,
					   Map<String, ? extends Matrix4fc> nodeTransforms, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		submit(pose, collector, light, overlay, distanceToCameraSq, null, nodeTransforms, null, crumbling);
	}

	/** Draws the pose {@code animator} is at: its clips, and its overrides on top. */
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq, ClayAnimator animator) {
		submit(pose, collector, light, overlay, distanceToCameraSq, animator, animator.overrides(), null, null);
	}

	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq, ClayAnimator animator,
					   ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		submit(pose, collector, light, overlay, distanceToCameraSq, animator, animator.overrides(), null, crumbling);
	}

	/** Draws {@code instance}: its animator's pose, with its tint, textures and scrolling. */
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq, ClayInstance instance) {
		submit(pose, collector, light, overlay, distanceToCameraSq, instance, null);
	}

	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq, ClayInstance instance,
					   ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		ClayAnimator animator = instance.animator();
		submit(pose, collector, light, overlay, distanceToCameraSq, animator, animator.overrides(), instance, crumbling);
	}

	private void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, double distanceToCameraSq, @Nullable ClayAnimator animator,
						Map<String, ? extends Matrix4fc> nodeTransforms, @Nullable ClayInstance instance, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		for (Lod lod : lods) {
			if (distanceToCameraSq >= lod.minDistanceSq) {
				lod.model.submit(pose, collector, light, overlay, 0, animator, nodeTransforms, instance, crumbling);
				return;
			}
		}
		Baked b = baked();
		if (b == null) return;
		Matrix4f[] palette = pose(b.rig, animator, nodeTransforms);
		double seconds = scrollClock();
		b.mesh.defaultMaterials(seconds, b.materials);
		if (instance != null) instance.apply(b.mesh, atlas(), seconds, b.materials);
		ClayRenderers.active().submit(b.mesh, palette, b.materials, pose.last(), collector, light, overlay, instance == null ? -1 : instance.tint());
		if (crumbling != null) CpuRenderer.submitCrumbling(b.mesh, palette, pose.last(), collector, crumbling);
	}

	/** Seconds of game time, which pauses with the game: what scrolling textures move with. */
	private static double scrollClock() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : (mc.level.getGameTime() + (double) mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20;
	}

	private static TextureAtlas atlas() {
		return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
	}

	/** Samples the animator's clips (if any) and builds the palette, without allocating. */
	private static Matrix4f[] pose(Rig rig, @Nullable ClayAnimator animator, Map<String, ? extends Matrix4fc> nodeTransforms) {
		rig.reset();
		if (animator != null) {
			Glb.Animation clip = animator.clip() == null ? null : rig.clip(animator.clip());
			if (animator.fading()) {
				Glb.Animation from = animator.previous() == null ? null : rig.clip(animator.previous());
				rig.sampleBlend(from, animator.previousTime(), animator.previousLoop(), clip, animator.time(), animator.loop(), animator.weight());
			} else if (clip != null) {
				rig.sample(clip, animator.time(), animator.loop());
			}
		}
		return rig.build(nodeTransforms);
	}

	/** Length of the clip {@code name} in seconds, 0 if the model has no such clip or isn't loaded. */
	public float duration(String name) {
		Baked b = baked();
		Glb.Animation clip = b == null ? null : b.rig.clip(name);
		return clip == null ? 0 : clip.duration();
	}

	/** The names of the model's animation clips. Empty until resources are loaded. */
	public java.util.Set<String> animations() {
		Baked b = baked();
		return b == null ? java.util.Set.of() : java.util.Collections.unmodifiableSet(b.rig.clips().keySet());
	}

	/**
	 * The Blender empty named {@code socket_<name>}: a point on the model that follows its animation, for attaching
	 * held items, other models or particles. The socket is looked up when used, so it can be made before resources load.
	 */
	public Socket socket(String name) {
		return new Socket("socket_" + name);
	}

	/** See {@link #socket}. */
	public final class Socket {
		private final String node;

		private Socket(String node) {
			this.node = node;
		}

		/** Whether the model has this socket (false until resources are loaded). */
		public boolean exists() {
			Baked b = baked();
			return b != null && b.rig.node(node) >= 0;
		}

		/**
		 * The socket's model-space transform in {@code animator}'s pose (or the rest pose when null), with its overrides,
		 * into {@code dest}. Identity if the socket doesn't exist.
		 */
		public Matrix4f transform(@Nullable ClayAnimator animator, Matrix4f dest) {
			Baked b = baked();
			int i = b == null ? -1 : b.rig.node(node);
			if (i < 0) return dest.identity();
			return dest.set(pose(b.rig, animator, animator == null ? Map.of() : animator.overrides())[i]);
		}

		/** Moves {@code pose} onto the socket, so whatever you submit next is attached to it. Push the pose first. */
		public void apply(PoseStack pose, @Nullable ClayAnimator animator) {
			pose.mulPose(transform(animator, new Matrix4f()));
		}

		/** Where the socket is in model space, e.g. to spawn particles there after turning it like your entity. */
		public Vector3f position(@Nullable ClayAnimator animator, Vector3f dest) {
			return transform(animator, new Matrix4f()).getTranslation(dest);
		}
	}

	/** A new instance (animator, tint, texture swaps) of this model. */
	public ClayInstance instance() {
		return new ClayInstance(this);
	}

	/** A new animator for one instance of this model. */
	public ClayAnimator animator() {
		return new ClayAnimator(this);
	}

	/** Lazily (re)builds after every resource reload: the glb instance and the atlas sprites both change then. */
	private @Nullable Baked baked() {
		Glb glb = ClayAssets.get(id);
		if (glb == null) return null;
		TextureAtlas atlas = atlas();
		if (baked == null || baked.glb != glb || baked.atlasMarker != atlas.missingSprite()) baked = new Baked(glb, atlas, mesh(id, glb, atlas));
		return baked;
	}

	private record Cached(Glb glb, TextureAtlasSprite atlasMarker, ClayMesh mesh) {}

	private static ClayMesh mesh(Identifier id, Glb glb, TextureAtlas atlas) {
		Cached cached = MESHES.get(id);
		if (cached != null && cached.glb == glb && cached.atlasMarker == atlas.missingSprite()) return cached.mesh;
		if (cached != null) cached.mesh.close();
		TextureAtlasSprite white = atlas.getSprite(ClayGeometry.WHITE);
		ClayMesh mesh = new ClayMesh(id, glb, material -> {
			Glb.Material mat = glb.material(material);
			TextureAtlasSprite sprite = mat.image() >= 0 && glb.images.get(mat.image()) != null ? atlas.getSprite(ClayAssets.spriteId(id, mat.image())) : white;
			return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
		});
		MESHES.put(id, new Cached(glb, atlas.missingSprite(), mesh));
		return mesh;
	}

	/** The baked meshes in use, for /clay. */
	public static List<ClayMesh> meshes() {
		return MESHES.values().stream().map(Cached::mesh).toList();
	}

	private static final class Baked {
		final Glb glb;
		final TextureAtlasSprite atlasMarker;
		final ClayMesh mesh;
		/** Poses this model; its palette is scratch reused by every submit, like the material table. */
		final Rig rig;
		final float[] materials;

		Baked(Glb glb, TextureAtlas atlas, ClayMesh mesh) {
			this.glb = glb;
			this.atlasMarker = atlas.missingSprite();
			this.mesh = mesh;
			this.rig = new Rig(glb);
			this.materials = new float[mesh.slots * ClayMesh.MATERIAL_FLOATS];
		}
	}
}
