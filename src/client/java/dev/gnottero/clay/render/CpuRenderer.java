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
	public void submit(ClayMesh mesh, Matrix4fc[] palette, float[] materials, PoseStack.Pose pose, SubmitNodeCollector collector, int light, int overlay, int tint) {
		// ponytail: one palette and material copy per submit, because the draw runs after the caller reuses its arrays
		float[] posed = posed(mesh, palette, pose), table = materials.clone();
		for (ClayMesh.Part part : mesh.parts) {
			int partLight = part.kind().emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
			collector.submitCustomGeometry(IDENTITY, renderType(part.kind()), (p, vc) -> draw(part, posed, table, vc, partLight, overlay, tint));
		}
	}

	/** Block breaking cracks over the whole mesh, for either renderer. */
	public static void submitCrumbling(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose, SubmitNodeCollector collector,
									   ModelFeatureRenderer.CrumblingOverlay crumbling) {
		float[] posed = posed(mesh, palette, pose), table = new float[mesh.slots * ClayMesh.MATERIAL_FLOATS];
		RenderType type = ModelBakery.DESTROY_TYPES.get(crumbling.progress());
		for (ClayMesh.Part part : mesh.parts) {
			collector.submitCustomGeometry(IDENTITY, type,
				(p, vc) -> draw(part, posed, table, new SheetedDecalTextureGenerator(vc, crumbling.cameraPose(), 1), LightCoordsUtil.FULL_BRIGHT, 0, -1));
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

	/**
	 * Positions are already camera-relative (posed palette), so the vertex consumer gets them as they are. Like the vertex
	 * shader, UVs are clamped to the image unless the material scrolls; scrolling ones are cut at the tile edges ({@link UvWrap}).
	 */
	private static void draw(ClayMesh.Part part, float[] posed, float[] table, VertexConsumer vc, int light, int overlay, int tint) {
		ClayMesh m = part.mesh();
		float[] skin = new float[16], quad = new float[4 * UvWrap.V];
		UvWrap wrap = new UvWrap();
		for (int q = part.firstVertex(); q < part.firstVertex() + part.vertexCount(); q += 4) {
			int o = m.materials[q] * ClayMesh.MATERIAL_FLOATS;
			boolean wraps = table[o + 6] != 0;
			for (int k = 0; k < 4; k++) {
				int v = q + k, at = k * UvWrap.V;
				ClaySkin.blend(posed, m.joints, m.weights, v, skin);
				ClaySkin.transformPosition(skin, m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2], quad, at);
				ClaySkin.transformNormal(skin, m.normals[v * 3], m.normals[v * 3 + 1], m.normals[v * 3 + 2], quad, at + 3);
				float u = m.uvs[v * 2], w = m.uvs[v * 2 + 1];
				quad[at + 6] = wraps ? u + table[o + 4] : Math.clamp(u, 0f, 1f);
				quad[at + 7] = wraps ? w + table[o + 5] : Math.clamp(w, 0f, 1f);
				int color = ARGB.multiply(m.colors[v], tint);
				quad[at + 8] = ARGB.red(color); quad[at + 9] = ARGB.green(color); quad[at + 10] = ARGB.blue(color); quad[at + 11] = ARGB.alpha(color);
			}
			if (!wraps) {
				for (int k = 0; k < 4; k++) vertex(vc, quad, k, table, m.materials[q], light, overlay);
			} else {
				int slot = m.materials[q];
				UvWrap.Emit emit = (poly, a, b, c, d) -> emitAll(vc, poly, table, slot, light, overlay, a, b, c, d);
				wrap.triangle(quad, 0, 1, 2, emit);
				if (!same(quad, 2, 3)) wrap.triangle(quad, 0, 2, 3, emit);
			}
		}
	}

	private static void emitAll(VertexConsumer vc, float[] poly, float[] table, int slot, int light, int overlay, int a, int b, int c, int d) {
		vertex(vc, poly, a, table, slot, light, overlay);
		vertex(vc, poly, b, table, slot, light, overlay);
		vertex(vc, poly, c, table, slot, light, overlay);
		vertex(vc, poly, d, table, slot, light, overlay);
	}

	/** Writes vertex {@code i} of {@code verts} ({@link UvWrap#V} floats each), its 0-1 UVs mapped into the slot's atlas rectangle. */
	private static void vertex(VertexConsumer vc, float[] verts, int i, float[] table, int slot, int light, int overlay) {
		int at = i * UvWrap.V, o = slot * ClayMesh.MATERIAL_FLOATS;
		float n = (float) Math.sqrt(verts[at + 3] * verts[at + 3] + verts[at + 4] * verts[at + 4] + verts[at + 5] * verts[at + 5]);
		if (n == 0) n = 1;
		vc.addVertex(verts[at], verts[at + 1], verts[at + 2])
			.setColor(ARGB.color(Math.round(verts[at + 11]), Math.round(verts[at + 8]), Math.round(verts[at + 9]), Math.round(verts[at + 10])))
			.setUv(table[o] + (table[o + 2] - table[o]) * verts[at + 6], table[o + 1] + (table[o + 3] - table[o + 1]) * verts[at + 7])
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(verts[at + 3] / n, verts[at + 4] / n, verts[at + 5] / n);
	}

	/** A lone triangle is stored as a quad repeating its third vertex. */
	private static boolean same(float[] quad, int a, int b) {
		for (int c = 0; c < 3; c++) if (quad[a * UvWrap.V + c] != quad[b * UvWrap.V + c]) return false;
		return true;
	}
}
