package dev.gnottero.clay.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4fc;

/**
 * Streams a {@link ClayMesh} through vanilla's entity render types every frame: each vertex is moved by its blended palette
 * matrix ({@link ClaySkin}) on the CPU and written to the vertex consumer. Opaque and cutout materials use
 * {@code entityCutoutCull}, translucent ones {@code entityTranslucentCull}, double-sided ones the no-cull variants.
 */
public final class CpuRenderer implements ClayRenderer {
	public static final CpuRenderer INSTANCE = new CpuRenderer();
	/** Vertices arrive camera-relative, so the collector's pose is the identity. Render thread only, never pushed. */
	private static final PoseStack IDENTITY = new PoseStack();

	private CpuRenderer() {}

	@Override
	public String name() {
		return "CPU";
	}

	@Override
	public void submit(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose, SubmitNodeCollector collector, int light, int overlay, int tint) {
		// ponytail: one palette copy per submit, because the draw runs after the caller reuses its matrices
		float[] posed = posed(mesh, palette, pose);
		for (ClayMesh.Part part : mesh.parts) {
			int partLight = part.kind().emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
			collector.submitCustomGeometry(IDENTITY, renderType(part.kind()), (p, vc) -> draw(part, posed, vc, partLight, overlay, tint));
		}
	}

	/** Block breaking cracks over the whole mesh, for either renderer. */
	public static void submitCrumbling(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose, SubmitNodeCollector collector,
									   ModelFeatureRenderer.CrumblingOverlay crumbling) {
		float[] posed = posed(mesh, palette, pose);
		RenderType type = ModelBakery.DESTROY_TYPES.get(crumbling.progress());
		for (ClayMesh.Part part : mesh.parts) {
			collector.submitCustomGeometry(IDENTITY, type,
				(p, vc) -> draw(part, posed, new SheetedDecalTextureGenerator(vc, crumbling.cameraPose(), 1), LightCoordsUtil.FULL_BRIGHT, 0, -1));
		}
	}

	private static float[] posed(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose) {
		float[] posed = new float[mesh.paletteSize * 16];
		ClaySkin.pose(pose.pose(), palette, mesh.paletteSize, posed);
		return posed;
	}

	public static RenderType renderType(ClayMesh.Kind kind) {
		var atlas = TextureAtlas.LOCATION_BLOCKS;
		if (kind.translucent()) return kind.cull() ? RenderTypes.entityTranslucentCull(atlas) : RenderTypes.entityTranslucent(atlas);
		return kind.cull() ? RenderTypes.entityCutoutCull(atlas) : RenderTypes.entityCutout(atlas);
	}

	/** Positions are already camera-relative (posed palette), so the vertex consumer gets them as they are. */
	private static void draw(ClayMesh.Part part, float[] posed, VertexConsumer vc, int light, int overlay, int tint) {
		ClayMesh m = part.mesh();
		float[] skin = new float[16], out = new float[6];
		for (int v = part.firstVertex(); v < part.firstVertex() + part.vertexCount(); v++) {
			ClaySkin.blend(posed, m.joints, m.weights, v, skin);
			ClaySkin.transformPosition(skin, m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2], out, 0);
			ClaySkin.transformNormal(skin, m.normals[v * 3], m.normals[v * 3 + 1], m.normals[v * 3 + 2], out, 3);
			vc.addVertex(out[0], out[1], out[2])
				.setColor(ARGB.multiply(m.colors[v], tint))
				.setUv(m.uvs[v * 2], m.uvs[v * 2 + 1])
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(out[3], out[4], out[5]);
		}
	}
}
