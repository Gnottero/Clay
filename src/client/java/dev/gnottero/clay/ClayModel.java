package dev.gnottero.clay;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gnottero.clay.gltf.Glb;
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
		for (Lod lod : lods) {
			if (distanceToCameraSq >= lod.minDistanceSq) {
				lod.model.submit(pose, collector, light, overlay, 0, nodeTransforms, crumbling);
				return;
			}
		}
		Baked b = baked();
		if (b == null) return;

		for (int i = 0; i < b.nodes.size(); i++) {
			BakedNode node = b.nodes.get(i);
			Matrix4f m = node.parent < 0 ? b.palette[i].identity() : b.palette[i].set(b.palette[node.parent]);
			m.mul(node.local);
			Matrix4fc extra = nodeTransforms.get(node.name);
			if (extra != null) m.mul(extra);
		}
		ClayRenderers.active().submit(b.mesh, b.palette, pose.last(), collector, light, overlay, -1);
		if (crumbling != null) CpuRenderer.submitCrumbling(b.mesh, b.palette, pose.last(), collector, crumbling);
	}

	/** Lazily (re)builds after every resource reload: the glb instance and the atlas sprites both change then. */
	private @Nullable Baked baked() {
		Glb glb = ClayAssets.get(id);
		if (glb == null) return null;
		TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
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

	private record BakedNode(String name, int parent, Matrix4f local) {}

	private static final class Baked {
		final Glb glb;
		final TextureAtlasSprite atlasMarker;
		final ClayMesh mesh;
		final List<BakedNode> nodes = new ArrayList<>();
		/** Scratch for this frame's node transforms, reused by every submit. */
		final Matrix4f[] palette;

		Baked(Glb glb, TextureAtlas atlas, ClayMesh mesh) {
			this.glb = glb;
			this.atlasMarker = atlas.missingSprite();
			this.mesh = mesh;
			for (Glb.Node node : glb.nodes) nodes.add(new BakedNode(node.name(), node.parent(), node.local()));
			palette = new Matrix4f[mesh.paletteSize];
			for (int i = 0; i < palette.length; i++) palette[i] = new Matrix4f();
		}
	}
}
