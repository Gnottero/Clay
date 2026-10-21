package dev.gnottero.clay;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.gnottero.clay.gltf.Glb;
import dev.gnottero.clay.gltf.Quads;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.GsonHelper;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block/item geometry baked from a .glb, used through a model json:
 * <pre>{"fabric:type": "clay:gltf", "model": "mymod:statue", "parent": "minecraft:block/block"}</pre>
 * Everything else in the json (parent, display, textures.particle, ambientocclusion, gui_light) works as in vanilla.
 * Baked once per resource reload and blockstate rotation into a Fabric Renderer mesh, so the per-frame cost is zero.
 * <p>
 * Model space: Blender origin = bottom center of the block, so a 1x1x1 cube spans x,z in [-0.5, 0.5], y in [0, 1].
 */
public record ClayGeometry(Identifier model) implements UnbakedGeometry {
	public static final Identifier TYPE = Identifier.fromNamespaceAndPath("clay", "gltf");
	public static final UnbakedModelDeserializer DESERIALIZER = (json, context) -> {
		Identifier model = Identifier.parse(GsonHelper.getAsString(json, "model"));
		JsonObject copy = json.deepCopy();
		if (!copy.has("textures")) copy.add("textures", new JsonObject());
		JsonObject textures = copy.getAsJsonObject("textures");
		if (!textures.has("particle")) textures.addProperty("particle", ClayAssets.spriteId(model, 0).toString());

		CuboidModel base = new CuboidModel.Deserializer().deserialize(copy, CuboidModel.class, context);
		return new CuboidModel(new ClayGeometry(model), base.guiLight(), base.ambientOcclusion(), base.transforms(), base.textureSlots(), base.parent());
	};

	private static final Logger LOGGER = LogUtils.getLogger();
	/** Plain white sprite for untextured materials (shipped in assets/clay/textures/block/white.png). */
	static final Identifier WHITE = Identifier.fromNamespaceAndPath("clay", "block/white");
	private static final float EPS = 1e-4f;
	/**
	 * Meshes by file and blockstate rotation, so any number of model jsons pointing at one heavy .glb bake it once. The
	 * file is held weakly: every resource reload loads new ones. Models bake in parallel, hence the synchronized maps.
	 */
	private static final Map<Glb, Map<Matrix4fc, QuadCollection>> BAKED = Collections.synchronizedMap(new WeakHashMap<>());

	@Override
	public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name) {
		Glb glb = ClayAssets.get(model);
		if (glb == null) {
			LOGGER.error("[clay] Model {} references missing or broken assets/{}/clay/{}.glb", name.debugName(), model.getNamespace(), model.getPath());
			return QuadCollection.EMPTY;
		}
		Matrix4f rotation = new Matrix4f(state.transformation().getMatrix());
		return BAKED.computeIfAbsent(glb, g -> new ConcurrentHashMap<>()).computeIfAbsent(rotation, r -> bake(glb, r, baker, name));
	}

	private QuadCollection bake(Glb glb, Matrix4fc rotation, ModelBaker baker, ModelDebugName name) {
		Material.Baked[] sprites = new Material.Baked[glb.materials.size() + 1];
		for (int m = -1; m < glb.materials.size(); m++) {
			Glb.Material mat = glb.material(m);
			Identifier sprite = mat.image() >= 0 && glb.images.get(mat.image()) != null ? ClayAssets.spriteId(model, mat.image()) : WHITE;
			sprites[m + 1] = baker.materials().get(new Material(sprite, mat.alphaMode() == Glb.AlphaMode.BLEND), name);
		}

		// Block-space transform: Blender origin (bottom center) -> block corner, then blockstate rotation about the center.
		Matrix4f transform = new Matrix4f()
			.translate(0.5f, 0.5f, 0.5f)
			.mul(rotation)
			.translate(0, -0.5f, 0);

		MutableMesh mesh = Renderer.get().mutableMesh();
		QuadEmitter emitter = mesh.emitter();
		boolean warnedUv = false;
		for (Glb.Node node : glb.nodes) {
			Matrix4f nodeToBlock = new Matrix4f(transform).mul(node.global());
			for (Glb.Primitive primitive : node.primitives()) {
				Glb.Material mat = glb.material(primitive.material());
				Material.Baked sprite = sprites[primitive.material() + 1];
				for (Quads.Quad quad : Quads.build(glb, primitive, nodeToBlock)) {
					if (!Quads.wrapUvs(quad.v()) && !warnedUv) {
						LOGGER.warn("[clay] {}: UVs tile past the texture edge; tiling is not supported in the block atlas, clamping", model);
						warnedUv = true;
					}
					emit(emitter, quad.v(), false, mat, sprite, cullFace(quad));
					if (mat.doubleSided()) emit(emitter, quad.v(), true, mat, sprite, null);
				}
			}
		}
		return new MeshQuadCollection(mesh.immutableCopy());
	}

	private static void emit(QuadEmitter e, float[] v, boolean reversed, Glb.Material mat, Material.Baked sprite, Direction cull) {
		for (int i = 0; i < 4; i++) {
			int o = (reversed ? 3 - i : i) * Quads.STRIDE;
			float sign = reversed ? -1 : 1;
			e.pos(i, v[o], v[o + 1], v[o + 2]);
			e.normal(i, v[o + 3] * sign, v[o + 4] * sign, v[o + 5] * sign);
			e.uv(i, sprite.sprite().getU(clamp(v[o + 6])), sprite.sprite().getV(clamp(v[o + 7])));
			e.color(i, ARGB.colorFromFloat(v[o + 11], v[o + 8], v[o + 9], v[o + 10]));
		}
		e.cullFace(cull);
		e.postMaterialBake(sprite);
		switch (mat.alphaMode()) {
			case OPAQUE -> e.chunkLayer(ChunkSectionLayer.SOLID);
			case MASK -> e.chunkLayer(ChunkSectionLayer.CUTOUT);
			case BLEND -> e.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		}
		e.emissive(mat.emissive());
		e.ambientOcclusion(mat.ao() ? TriState.DEFAULT : TriState.FALSE);
		e.emit();
	}

	private static float clamp(float uv) {
		return Math.clamp(uv, 0f, 1f);
	}

	/** A quad lying flat on a block boundary and facing out of the block can be culled by the neighbour on that side. */
	public static Direction cullFace(Quads.Quad q) {
		// geometric normal: smooth-shaded vertex normals can lean away from the face
		Vector3f a = new Vector3f(q.x(0), q.y(0), q.z(0));
		Vector3f normal = new Vector3f(q.x(1), q.y(1), q.z(1)).sub(a).cross(new Vector3f(q.x(2), q.y(2), q.z(2)).sub(a));
		if (normal.lengthSquared() < 1e-12f) return null;
		normal.normalize();
		for (Direction dir : Direction.values()) {
			if (normal.dot(dir.getStepX(), dir.getStepY(), dir.getStepZ()) < 0.99f) continue;
			int axis = dir.getAxis().ordinal();
			float plane = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
			boolean onPlane = true;
			for (int i = 0; i < 4 && onPlane; i++) {
				float c = axis == 0 ? q.x(i) : axis == 1 ? q.y(i) : q.z(i);
				onPlane = Math.abs(c - plane) < EPS;
			}
			return onPlane ? dir : null;
		}
		return null;
	}
}
