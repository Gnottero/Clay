package dev.gnottero.clay.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.BatchableSubmit;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws {@link ClayMesh}es from GPU buffers. The mesh is uploaded once (per resource reload); each frame only the posed
 * palette of every instance (a texel buffer, 4 texels per matrix) and 16 bytes per instance are uploaded, and every run of
 * instances of one mesh part is a single instanced draw.
 * <p>
 * Instances are submitted as custom nodes into vanilla's solid and translucent phases, so they draw in the same render pass,
 * with the same lighting, overlay, fog and blending as vanilla entities: the vertex shader is Clay's, the fragment shader is
 * vanilla's {@code core/entity.fsh}. With Improved Transparency on, translucent parts use order-independent transparency
 * pipelines like vanilla's. See docs/rendering-design.md.
 */
public final class GpuRenderer implements ClayRenderer {
	public static final GpuRenderer INSTANCE = new GpuRenderer();
	private static final FeatureRendererType<Submit> TYPE = FeatureRendererType.create("Clay");

	static final VertexFormat VERTEX = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("UV0", GpuFormat.RG32_FLOAT)
		.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
		.addAttribute("Joints", GpuFormat.RGBA16_UINT)
		.addAttribute("Weights", GpuFormat.RGBA8_UNORM)
		.build();
	/** Per instance: palette start (in texels), packed light, packed overlay, ARGB tint. */
	private static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1).addAttribute("Instance", GpuFormat.RGBA32_SINT).build();
	private static final int INSTANCE_BYTES = 16;
	private static final BindGroupLayout PALETTE = BindGroupLayout.builder().withUniform("ClayPalette", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("clay", "core/entity");

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
		.withVertexShader(SHADER)
		.withFragmentShader("core/entity")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
		.withBindGroupLayout(BindGroupLayouts.LIGHTING)
		.withBindGroupLayout(PALETTE)
		.withVertexBinding(0, VERTEX)
		.withVertexBinding(1, INSTANCE_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.buildSnippet();
	/** Mirrors RenderPipelines.OIT_ENTITY_SNIPPET with Clay's vertex input. */
	private static final RenderPipeline.Snippet OIT_SNIPPET = RenderPipeline.builder()
		.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
		.withBindGroupLayout(BindGroupLayouts.FOG)
		.withBindGroupLayout(BindGroupLayouts.LIGHTING)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
		.withBindGroupLayout(PALETTE)
		.withVertexShader(SHADER)
		.withFragmentShader("core/entity")
		.withVertexBinding(0, VERTEX)
		.withVertexBinding(1, INSTANCE_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.buildSnippet();

	private static final RenderPipeline CUTOUT_CULL = pipeline("entity_cutout_cull", false, true);
	private static final RenderPipeline CUTOUT = pipeline("entity_cutout", false, false);
	private static final RenderPipeline TRANSLUCENT_CULL = pipeline("entity_translucent_cull", true, true);
	private static final RenderPipeline TRANSLUCENT = pipeline("entity_translucent", true, false);
	private static final OitPipelineSet OIT_CULL = oit("clay_entity_cull", true);
	private static final OitPipelineSet OIT = oit("clay_entity", false);
	private static final List<RenderPipeline> PIPELINES = List.of(CUTOUT_CULL, CUTOUT, TRANSLUCENT_CULL, TRANSLUCENT,
		OIT_CULL.depthBoundsPipeline(), OIT_CULL.transmittancePipeline(), OIT_CULL.accumulatePipeline(),
		OIT.depthBoundsPipeline(), OIT.transmittancePipeline(), OIT.accumulatePipeline());

	/** GPU memory held by uploaded meshes, in bytes. */
	private static long memory;
	private static final ArrayDeque<Instance> POOL = new ArrayDeque<>();

	private GpuRenderer() {}

	/** Registers the pipelines (optional, so a failed compile can't stop a resource reload) and the feature renderer. */
	public static void register() {
		PIPELINES.forEach(RenderPipelines::registerOptional);
		FeatureRendererRegistry.register(TYPE, Feature::new);
	}

	/** Why the GPU renderer can't run on this device and resource pack, or null when it can. Compiles the pipelines if needed. */
	static @Nullable String unavailable() {
		try {
			for (RenderPipeline pipeline : PIPELINES) {
				if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) return "pipeline " + pipeline.getLocation() + " failed to compile";
			}
			return null;
		} catch (RuntimeException e) {
			return "pipelines failed to compile: " + e;
		}
	}

	public static long memory() {
		return memory;
	}

	private static RenderPipeline pipeline(String name, boolean translucent, boolean cull) {
		RenderPipeline.Builder builder = RenderPipeline.builder(SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("clay", "pipeline/" + name))
			.withCull(cull)
			.withColorTargetState(translucent ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT);
		// vanilla lights double-sided entity faces from whichever side the camera sees
		if (!cull) builder.withShaderDefine("PER_FACE_LIGHTING");
		return builder.build();
	}

	private static OitPipelineSet oit(String name, boolean cull) {
		return OitPipelineSet.builder(name, RenderPipeline.builder(OIT_SNIPPET).withCull(cull))
			.withAccumulateModifier(accumulate -> {
				if (!cull) accumulate.withShaderDefine("PER_FACE_LIGHTING");
				accumulate.withBindGroupLayout(BindGroupLayouts.SAMPLER1).withBindGroupLayout(BindGroupLayouts.SAMPLER2);
			})
			.build();
	}

	private static RenderPipeline pipeline(ClayMesh.Kind kind, @Nullable OitStage stage) {
		if (stage != null) return (kind.cull() ? OIT_CULL : OIT).getPipeline(stage);
		if (kind.translucent()) return kind.cull() ? TRANSLUCENT_CULL : TRANSLUCENT;
		return kind.cull() ? CUTOUT_CULL : CUTOUT;
	}

	@Override
	public String name() {
		return "GPU";
	}

	@Override
	public void submit(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose, SubmitNodeCollector collector, int light, int overlay, int tint) {
		Instance instance = POOL.isEmpty() ? new Instance() : POOL.poll();
		if (instance.palette.length < mesh.paletteSize * 16) instance.palette = new float[mesh.paletteSize * 16];
		ClaySkin.pose(pose.pose(), palette, mesh.paletteSize, instance.palette);
		instance.size = mesh.paletteSize;
		instance.light = light;
		instance.overlay = overlay;
		instance.tint = tint;
		for (ClayMesh.Part part : mesh.parts) {
			collector.submitCustom(part.kind().translucent() ? SubmitRenderPhases.TRANSLUCENT_CUSTOM_GEOMETRY : SubmitRenderPhases.SOLID, new Submit(part, instance));
		}
	}

	/** One instance's posed palette and lighting for this frame; pooled, returned when the frame has been drawn. */
	private static final class Instance {
		float[] palette = new float[16];
		int size, light, overlay, tint;
		/** Where this frame's palette buffer holds it, in texels; -1 until a feature renderer has written it. */
		int base = -1;
	}

	/** Batched by part, so vanilla's phases hand all instances of one part to {@link Feature} next to each other. */
	private record Submit(ClayMesh.Part part, Instance instance) implements BatchableSubmit {
		@Override
		public Object batchKey() {
			return part;
		}

		@Override
		public FeatureRendererType<Submit> featureType() {
			return TYPE;
		}
	}

	/** The mesh's vertex and index buffers on the GPU. */
	static final class Buffers implements AutoCloseable {
		final GpuBuffer vertices, indices;

		Buffers(ClayMesh mesh) {
			ByteBuffer v = ByteBuffer.allocateDirect(mesh.vertexCount * VERTEX.getVertexSize()).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < mesh.vertexCount; i++) {
				v.putFloat(mesh.positions[i * 3]).putFloat(mesh.positions[i * 3 + 1]).putFloat(mesh.positions[i * 3 + 2]);
				int c = mesh.colors[i];
				v.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				v.putFloat(mesh.uvs[i * 2]).putFloat(mesh.uvs[i * 2 + 1]);
				v.put(snorm(mesh.normals[i * 3])).put(snorm(mesh.normals[i * 3 + 1])).put(snorm(mesh.normals[i * 3 + 2])).put((byte) 0);
				for (int k = 0; k < 4; k++) v.putShort((short) mesh.joints[i * 4 + k]);
				v.put(mesh.weights, i * 4, 4);
			}
			ByteBuffer ix = ByteBuffer.allocateDirect(mesh.indices.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			ix.asIntBuffer().put(mesh.indices);
			var device = RenderSystem.getDevice();
			vertices = device.createBuffer(() -> "Clay " + mesh.id + " vertices", GpuBuffer.USAGE_VERTEX, v.flip());
			try {
				indices = device.createBuffer(() -> "Clay " + mesh.id + " indices", GpuBuffer.USAGE_INDEX, ix);
			} catch (RuntimeException e) {
				vertices.close();
				throw e;
			}
			memory += vertices.size() + indices.size();
		}

		private static byte snorm(float f) {
			return (byte) Math.round(Math.clamp(f, -1f, 1f) * 127);
		}

		@Override
		public void close() {
			memory -= vertices.size() + indices.size();
			vertices.close();
			indices.close();
		}
	}

	/**
	 * Collects instances while vanilla prepares the frame, uploads them in {@link #finishPrepare}, and draws them when
	 * vanilla executes each phase. Each feature render dispatcher (level, GUI) has its own.
	 */
	private static final class Feature implements FeatureRenderer<Submit> {
		private record Draw(ClayMesh.Part part, int firstInstance, int count) {}

		private final List<List<Draw>> groups = new ArrayList<>();
		private final List<Instance> instances = new ArrayList<>();
		private ByteBuffer palette = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
		private ByteBuffer instanceData = ByteBuffer.allocateDirect(1 << 12).order(ByteOrder.nativeOrder());
		private @Nullable GpuBufferSlice paletteSlice, instanceSlice, transforms;

		@Override
		public void prepareGroup(FeatureFrameContext context, List<Submit> submits, boolean strictlyOrdered) {
			List<Draw> draws = new ArrayList<>();
			for (int i = 0; i < submits.size(); ) {
				ClayMesh.Part part = submits.get(i).part;
				int first = instanceData.position() / INSTANCE_BYTES, j = i;
				for (; j < submits.size() && submits.get(j).part == part; j++) write(submits.get(j));
				if (upload(part.mesh())) draws.add(new Draw(part, first, j - i));
				i = j;
			}
			groups.add(draws);
		}

		private static boolean upload(ClayMesh mesh) {
			if (mesh.gpu != null) return true;
			try {
				mesh.gpu = new Buffers(mesh);
				return true;
			} catch (RuntimeException e) {
				ClayRenderers.gpuFailed("couldn't upload " + mesh.id, e);
				return false;
			}
		}

		private void write(Submit submit) {
			Instance instance = submit.instance;
			if (instance.base < 0) {
				palette = ensure(palette, instance.size * 64);
				instance.base = palette.position() / 16;
				palette.asFloatBuffer().put(instance.palette, 0, instance.size * 16);
				palette.position(palette.position() + instance.size * 64);
				instances.add(instance);
			}
			instanceData = ensure(instanceData, INSTANCE_BYTES);
			instanceData.putInt(instance.base)
				.putInt(submit.part.kind().emissive() ? LightCoordsUtil.FULL_BRIGHT : instance.light)
				.putInt(instance.overlay)
				.putInt(instance.tint);
		}

		private static ByteBuffer ensure(ByteBuffer buffer, int bytes) {
			if (buffer.remaining() >= bytes) return buffer;
			ByteBuffer grown = ByteBuffer.allocateDirect(Math.max(buffer.capacity() * 2, buffer.position() + bytes)).order(buffer.order());
			return grown.put(buffer.flip());
		}

		@Override
		public void finishPrepare(FeatureFrameContext context) {
			if (instances.isEmpty()) return;
			var transient_ = RenderSystem.getDevice().createCommandEncoder().transientMemory();
			int alignment = Math.max(256, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
			paletteSlice = transient_.uploadGpu(palette.flip(), alignment, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
			instanceSlice = transient_.uploadGpu(instanceData.flip(), INSTANCE_BYTES, GpuBuffer.USAGE_VERTEX);
			transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
		}

		@Override
		public void executeGroup(FeatureFrameContext context, @Nullable OitStage stage, RenderPass pass, int groupIndex, List<Submit> submits, boolean strictlyOrdered) {
			if (paletteSlice == null || instanceSlice == null || transforms == null) return;
			TextureHandle atlas = context.textureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			for (Draw draw : groups.get(groupIndex)) {
				Buffers gpu = draw.part.mesh().gpu;
				if (gpu == null) continue; // closed by a resource reload since prepare
				RenderPipeline pipeline = pipeline(draw.part.kind(), stage);
				var compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
				if (compiled == null) { // a resource pack changed the shaders since the renderer was picked
					ClayRenderers.gpuFailed("pipeline " + pipeline.getLocation() + " failed to compile", null);
					return;
				}
				pass.setPipeline(compiled);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.setUniform("Sampler0", atlas.textureView(), atlas.sampler());
				pass.setUniform("Sampler1", Minecraft.getInstance().gameRenderer.overlayTexture().getTextureView(), linear);
				pass.setUniform("Sampler2", context.lightmap(), linear);
				pass.setUniform("ClayPalette", paletteSlice);
				pass.setVertexBuffer(0, gpu.vertices.slice());
				pass.setVertexBuffer(1, instanceSlice.slice((long) draw.firstInstance * INSTANCE_BYTES, (long) draw.count * INSTANCE_BYTES));
				pass.setIndexBuffer(gpu.indices, IndexType.INT);
				pass.drawIndexed(draw.part.indexCount(), draw.count, draw.part.firstIndex(), 0, 0);
			}
		}

		@Override
		public void finishExecute(FeatureFrameContext context) {
			groups.clear();
			for (Instance instance : instances) {
				instance.base = -1;
				POOL.add(instance);
			}
			instances.clear();
			palette.clear();
			instanceData.clear();
			paletteSlice = instanceSlice = transforms = null;
		}
	}
}
