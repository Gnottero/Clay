package dev.gnottero.clay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Quads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 */
public final class ClayModel {
	private final Identifier id;
	private final List<Lod> lods = new ArrayList<>();
	private Baked baked;

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
		return b == null ? new AABB(0, 0, 0, 0, 0, 0) : b.bounds;
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

		Matrix4f[] global = new Matrix4f[b.nodes.size()];
		for (int i = 0; i < global.length; i++) {
			BakedNode node = b.nodes.get(i);
			Matrix4f m = node.parent < 0 ? new Matrix4f() : new Matrix4f(global[node.parent]);
			m.mul(node.local);
			Matrix4fc extra = nodeTransforms.get(node.name);
			if (extra != null) m.mul(extra);
			global[i] = m;
		}

		for (int i = 0; i < global.length; i++) {
			List<Group> groups = b.nodes.get(i).groups;
			if (groups.isEmpty()) continue;
			pose.pushPose();
			pose.mulPose(global[i]);
			for (Group g : groups) {
				int groupLight = g.emissive ? LightCoordsUtil.FULL_BRIGHT : light;
				collector.submitCustomGeometry(pose, g.renderType, (p, vc) -> g.draw(p, vc, groupLight, overlay));
				if (crumbling != null) {
					collector.submitCustomGeometry(pose, ModelBakery.DESTROY_TYPES.get(crumbling.progress()),
						(p, vc) -> g.draw(p, new SheetedDecalTextureGenerator(vc, crumbling.cameraPose(), 1), groupLight, overlay));
				}
			}
			pose.popPose();
		}
	}

	/** Lazily (re)builds after every resource reload: the glb instance and the atlas sprites both change then. */
	private Baked baked() {
		Glb glb = ClayAssets.get(id);
		if (glb == null) return null;
		TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		if (baked == null || baked.glb != glb || baked.atlasMarker != atlas.missingSprite()) baked = new Baked(id, glb, atlas);
		return baked;
	}

	private record BakedNode(String name, int parent, Matrix4f local, List<Group> groups) {}

	private static final class Baked {
		final Glb glb;
		final TextureAtlasSprite atlasMarker;
		final List<BakedNode> nodes = new ArrayList<>();
		final AABB bounds;

		Baked(Identifier id, Glb glb, TextureAtlas atlas) {
			this.glb = glb;
			this.atlasMarker = atlas.missingSprite();
			TextureAtlasSprite white = atlas.getSprite(ClayGeometry.WHITE);
			Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), tmp = new Vector3f();

			for (Glb.Node node : glb.nodes) {
				Map<List<Object>, Group> groups = new LinkedHashMap<>();
				for (Glb.Primitive primitive : node.primitives()) {
					Glb.Material mat = glb.material(primitive.material());
					TextureAtlasSprite sprite = mat.image() >= 0 && glb.images.get(mat.image()) != null
						? atlas.getSprite(ClayAssets.spriteId(id, mat.image())) : white;
					RenderType type = renderType(mat);
					// emissive and non-emissive materials can share a render type but not a light value
					Group group = groups.computeIfAbsent(List.of(type, mat.emissive()), k -> new Group(type, mat.emissive()));
					for (Quads.Quad quad : Quads.build(glb, primitive, new Matrix4f())) {
						Quads.wrapUvs(quad.v());
						group.add(quad.v(), sprite);
						for (int v = 0; v < 4; v++) {
							node.global().transformPosition(tmp.set(quad.x(v), quad.y(v), quad.z(v)));
							min.min(tmp);
							max.max(tmp);
						}
					}
				}
				nodes.add(new BakedNode(node.name(), node.parent(), node.local(), groups.values().stream().filter(g -> g.size > 0).toList()));
			}
			bounds = min.x > max.x ? new AABB(0, 0, 0, 0, 0, 0) : new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
		}
	}

	private static RenderType renderType(Glb.Material mat) {
		Identifier atlas = TextureAtlas.LOCATION_BLOCKS;
		if (mat.alphaMode() == Glb.AlphaMode.BLEND) {
			return mat.doubleSided() ? RenderTypes.entityTranslucent(atlas) : RenderTypes.entityTranslucentCull(atlas);
		}
		return mat.doubleSided() ? RenderTypes.entityCutout(atlas) : RenderTypes.entityCutoutCull(atlas);
	}

	/**
	 * Pre-packed vertices for one render type: x y z nx ny nz u v (atlas UVs) + ARGB.
	 * ponytail: streamed from the CPU every frame through VertexConsumer; move to a retained GpuBuffer
	 * with a custom RenderPipeline if many high-poly entities show up in profiles.
	 */
	private static final class Group {
		final RenderType renderType;
		final boolean emissive;
		float[] data = new float[8 * 64];
		int[] colors = new int[64];
		int size; // vertices

		Group(RenderType renderType, boolean emissive) {
			this.renderType = renderType;
			this.emissive = emissive;
		}

		void add(float[] quad, TextureAtlasSprite sprite) {
			if (colors.length < size + 4) {
				data = Arrays.copyOf(data, data.length * 2);
				colors = Arrays.copyOf(colors, colors.length * 2);
			}
			for (int i = 0; i < 4; i++) {
				int o = i * Quads.STRIDE, d = (size + i) * 8;
				System.arraycopy(quad, o, data, d, 6);
				data[d + 6] = sprite.getU(Math.clamp(quad[o + 6], 0f, 1f));
				data[d + 7] = sprite.getV(Math.clamp(quad[o + 7], 0f, 1f));
				colors[size + i] = ARGB.colorFromFloat(quad[o + 11], quad[o + 8], quad[o + 9], quad[o + 10]);
			}
			size += 4;
		}

		void draw(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay) {
			for (int i = 0; i < size; i++) {
				int d = i * 8;
				vc.addVertex(pose, data[d], data[d + 1], data[d + 2])
					.setColor(colors[i])
					.setUv(data[d + 6], data[d + 7])
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(pose, data[d + 3], data[d + 4], data[d + 5]);
			}
		}
	}
}
