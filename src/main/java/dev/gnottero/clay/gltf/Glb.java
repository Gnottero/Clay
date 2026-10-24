package dev.gnottero.clay.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Minimal glTF 2.0 binary (.glb) reader: just what Blender's exporter emits for meshes, skins and node animations.
 * Pure Java (Gson + JOML only) so it can be unit-tested without a game instance.
 */
public final class Glb {
	public enum AlphaMode { OPAQUE, MASK, BLEND }

	/** @param baseColor RGBA factor; @param image index into {@link #images}, or -1 for untextured. */
	/**
	 * Custom properties: {@code clay_ao} (false = no ambient occlusion), {@code clay_scroll} ([u, v] texture widths per
	 * second, null if not set) and {@code clay_frametime} (ticks per frame of an animated texture, 0 = not animated).
	 */
	public record Material(String name, float[] baseColor, int image, AlphaMode alphaMode, boolean doubleSided, boolean emissive, boolean ao,
						   float[] scroll, int frametime) {
		static final Material DEFAULT = new Material("default", new float[] {1, 1, 1, 1}, -1, AlphaMode.OPAQUE, false, false, true, null, 0);
	}

	/**
	 * Mesh-local triangle data. {@code normals}/{@code uvs}/{@code colors} may be null. Colors are RGBA floats.
	 * {@code joints}/{@code weights} (4 per vertex, JOINTS_0/WEIGHTS_0) are set on skinned meshes: joints index the skin's joint list.
	 */
	public record Primitive(float[] positions, float[] normals, float[] uvs, float[] colors, int[] indices, int material, int[] joints, float[] weights) {
		public int vertexCount() {
			return positions.length / 3;
		}
	}

	/** Nodes are ordered parents-first; {@code parent} indexes this list (-1 = root), {@code skin} {@link #skins} (-1 = none). */
	public record Node(String name, int parent, Matrix4f local, Matrix4f global, List<Primitive> primitives, int skin) {}

	/** {@code joints} index {@link #nodes}; a vertex bound to joint j moves with {@code joint global * inverseBind[j]}. */
	public record Skin(String name, int[] joints, Matrix4f[] inverseBind) {}

	public enum Path {
		TRANSLATION(3), ROTATION(4), SCALE(3);

		public final int components;

		Path(int components) {
			this.components = components;
		}
	}

	public enum Interpolation { LINEAR, STEP, CUBICSPLINE }

	/**
	 * One animated property of one node ({@link #nodes} index). {@code values} holds {@code path.components} floats per key,
	 * or for CUBICSPLINE three groups per key: in-tangent, value, out-tangent.
	 */
	public record Channel(int node, Path path, Interpolation interpolation, float[] times, float[] values) {}

	/** A named clip; {@code duration} is the last key time of any channel. */
	public record Animation(String name, float duration, List<Channel> channels) {}

	public final List<Node> nodes = new ArrayList<>();
	public final List<Material> materials = new ArrayList<>();
	public final List<Skin> skins = new ArrayList<>();
	public final List<Animation> animations = new ArrayList<>();
	/** Raw encoded image bytes (PNG), indexed like the glTF {@code images} array. Null when not embedded. */
	public final List<byte[]> images = new ArrayList<>();
	/** Non-fatal problems the caller should log. */
	public final List<String> warnings = new ArrayList<>();

	private static final int MAGIC = 0x46546C67, CHUNK_JSON = 0x4E4F534A, CHUNK_BIN = 0x004E4942;

	public static Glb read(byte[] data) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (data.length < 20 || buf.getInt(0) != MAGIC) throw new IOException("Not a .glb file (export as 'glTF Binary' in Blender)");
		if (buf.getInt(4) != 2) throw new IOException("Unsupported glTF version " + buf.getInt(4));

		JsonObject json = null;
		ByteBuffer bin = ByteBuffer.allocate(0);
		int offset = 12;
		while (offset + 8 <= data.length) {
			int length = buf.getInt(offset), type = buf.getInt(offset + 4);
			if (length < 0 || length > data.length - offset - 8) throw new IOException("Chunk length " + length + " runs past the end of the file");
			if (type == CHUNK_JSON) {
				json = JsonParser.parseString(new String(data, offset + 8, length, StandardCharsets.UTF_8)).getAsJsonObject();
			} else if (type == CHUNK_BIN) {
				bin = buf.slice(offset + 8, length).order(ByteOrder.LITTLE_ENDIAN);
			}
			offset += 8 + length;
		}
		if (json == null) throw new IOException("Missing JSON chunk");
		return new Glb(json, bin);
	}

	/** Deeper node hierarchies are rejected so a crafted file can't overflow the stack. */
	private static final int MAX_DEPTH = 256;
	/**
	 * Memory a file may make Clay hold (128 MB): every image copy, every accessor decode and the quads each primitive bakes
	 * into later, so images, accessors or meshes reused many times can't multiply past it.
	 */
	private static final long MAX_BYTES = 1 << 27;
	/** Nodes plus skin joints: the GPU renderer addresses them with 16-bit indices. */
	public static final int MAX_PALETTE = 65535;

	private final JsonObject json;
	private final ByteBuffer bin;
	private boolean[] visited;
	/** {@link #nodes} index of each glTF node, -1 for nodes outside the scene. */
	private int[] nodeIndex;
	private long spentBytes;
	private int paletteEntries;

	private Glb(JsonObject json, ByteBuffer bin) throws IOException {
		this.json = json;
		this.bin = bin;

		JsonArray required = array(json, "extensionsRequired");
		if (!required.isEmpty()) throw new IOException("Unsupported required extensions " + required + " (disable compression in the Blender exporter)");

		for (JsonElement e : array(json, "images")) {
			JsonObject image = e.getAsJsonObject();
			if (image.has("bufferView")) {
				ByteBuffer view = bufferView(image.get("bufferView").getAsInt());
				spend(view.remaining());
				byte[] bytes = new byte[view.remaining()];
				view.get(bytes);
				images.add(bytes);
			} else {
				warnings.add("Image " + images.size() + " is not embedded; use the 'glTF Binary (.glb)' export format");
				images.add(null);
			}
		}

		for (JsonElement e : array(json, "materials")) materials.add(material(e.getAsJsonObject()));

		JsonArray scenes = array(json, "scenes");
		if (scenes.isEmpty()) return;
		JsonObject scene = scenes.get(json.has("scene") ? json.get("scene").getAsInt() : 0).getAsJsonObject();
		visited = new boolean[array(json, "nodes").size()];
		nodeIndex = new int[visited.length];
		Arrays.fill(nodeIndex, -1);
		for (JsonElement root : array(scene, "nodes")) addNode(root.getAsInt(), -1, 0);

		for (JsonElement e : array(json, "skins")) skins.add(skin(e.getAsJsonObject()));
		for (Node node : nodes) {
			if (node.skin < 0) continue;
			if (node.skin >= skins.size()) throw new IOException("Node '" + node.name + "' uses missing skin " + node.skin);
			int count = skins.get(node.skin).joints.length;
			for (Primitive p : node.primitives) {
				if (p.joints == null) continue;
				for (int j : p.joints) if (j < 0 || j >= count) throw new IOException("Node '" + node.name + "': joint " + j + " out of range (" + count + " joints)");
			}
		}
		for (JsonElement e : array(json, "animations")) {
			Animation animation = animation(e.getAsJsonObject());
			if (animation != null) animations.add(animation);
		}
	}

	private Skin skin(JsonObject s) throws IOException {
		String name = s.has("name") ? s.get("name").getAsString() : "skin" + skins.size();
		JsonArray jointArray = array(s, "joints");
		paletteEntries += jointArray.size();
		if (paletteEntries > MAX_PALETTE) throw new IOException("More than " + MAX_PALETTE + " nodes and skin joints");
		// the joint list, its inverse bind matrices and the palette matrices a Rig keeps for them
		spend((long) jointArray.size() * (Integer.BYTES + 2 * 16 * Float.BYTES));
		int[] joints = new int[jointArray.size()];
		for (int i = 0; i < joints.length; i++) joints[i] = node(jointArray.get(i).getAsInt(), "Skin '" + name + "'");
		Matrix4f[] inverseBind = new Matrix4f[joints.length];
		float[] raw = s.has("inverseBindMatrices") ? accessor(s.get("inverseBindMatrices").getAsInt()) : null;
		if (raw != null && raw.length != joints.length * 16) throw new IOException("Skin '" + name + "' has " + raw.length / 16 + " inverse bind matrices for " + joints.length + " joints");
		for (int i = 0; i < joints.length; i++) inverseBind[i] = raw == null ? new Matrix4f() : new Matrix4f().set(raw, i * 16);
		return new Skin(name, joints, inverseBind);
	}

	/** The {@link #nodes} index of glTF node {@code gltfIndex}, which must be in the scene. */
	private int node(int gltfIndex, String user) throws IOException {
		if (gltfIndex < 0 || gltfIndex >= nodeIndex.length || nodeIndex[gltfIndex] < 0) throw new IOException(user + " uses node " + gltfIndex + ", which is not in the scene");
		return nodeIndex[gltfIndex];
	}

	private Animation animation(JsonObject a) throws IOException {
		String name = a.has("name") ? a.get("name").getAsString() : "animation" + animations.size();
		JsonArray samplers = array(a, "samplers");
		List<Channel> channels = new ArrayList<>();
		float duration = 0;
		for (JsonElement e : array(a, "channels")) {
			JsonObject channel = e.getAsJsonObject(), target = channel.getAsJsonObject("target");
			String path = target.get("path").getAsString();
			if (!target.has("node") || !List.of("translation", "rotation", "scale").contains(path)) {
				warnings.add("Animation '" + name + "': skipping a " + path + " channel (only node translation, rotation and scale are supported)");
				continue;
			}
			JsonObject sampler = samplers.get(channel.get("sampler").getAsInt()).getAsJsonObject();
			Path p = Path.valueOf(path.toUpperCase(Locale.ROOT));
			String mode = sampler.has("interpolation") ? sampler.get("interpolation").getAsString() : "LINEAR";
			if (!List.of("LINEAR", "STEP", "CUBICSPLINE").contains(mode)) throw new IOException("Animation '" + name + "': unknown interpolation " + mode);
			Interpolation interpolation = Interpolation.valueOf(mode);
			float[] times = accessor(sampler.get("input").getAsInt()), values = accessor(sampler.get("output").getAsInt());
			int perKey = p.components * (interpolation == Interpolation.CUBICSPLINE ? 3 : 1);
			if (times.length == 0 || values.length != times.length * perKey) throw new IOException("Animation '" + name + "': " + path + " channel has " + values.length + " values for " + times.length + " keys");
			for (int i = 1; i < times.length; i++) if (!(times[i] >= times[i - 1])) throw new IOException("Animation '" + name + "': key times must increase");
			channels.add(new Channel(node(target.get("node").getAsInt(), "Animation '" + name + "'"), p, interpolation, times, values));
			duration = Math.max(duration, times[times.length - 1]);
		}
		return channels.isEmpty() ? null : new Animation(name, duration, channels);
	}

	private void addNode(int gltfIndex, int parent, int depth) throws IOException {
		if (depth > MAX_DEPTH) throw new IOException("Node hierarchy deeper than " + MAX_DEPTH);
		if (gltfIndex < 0 || gltfIndex >= visited.length || visited[gltfIndex]) throw new IOException("Node " + gltfIndex + " is missing or has more than one parent");
		visited[gltfIndex] = true;
		JsonObject node = array(json, "nodes").get(gltfIndex).getAsJsonObject();
		Matrix4f local = new Matrix4f();
		if (node.has("matrix")) {
			local.set(floats(node.getAsJsonArray("matrix"), 16));
		} else {
			float[] t = node.has("translation") ? floats(node.getAsJsonArray("translation"), 3) : new float[3];
			float[] r = node.has("rotation") ? floats(node.getAsJsonArray("rotation"), 4) : new float[] {0, 0, 0, 1};
			float[] s = node.has("scale") ? floats(node.getAsJsonArray("scale"), 3) : new float[] {1, 1, 1};
			local.translationRotateScale(new Vector3f(t), new Quaternionf(r[0], r[1], r[2], r[3]), new Vector3f(s));
		}
		Matrix4f global = parent < 0 ? new Matrix4f(local) : new Matrix4f(nodes.get(parent).global()).mul(local);

		nodeIndex[gltfIndex] = nodes.size();
		if (++paletteEntries > MAX_PALETTE) throw new IOException("More than " + MAX_PALETTE + " nodes and skin joints");
		List<Primitive> primitives = new ArrayList<>();
		if (node.has("mesh")) {
			JsonObject mesh = array(json, "meshes").get(node.get("mesh").getAsInt()).getAsJsonObject();
			for (JsonElement p : array(mesh, "primitives")) {
				Primitive primitive = primitive(p.getAsJsonObject());
				if (primitive != null) primitives.add(primitive);
			}
		}

		String name = node.has("name") ? node.get("name").getAsString() : "node" + gltfIndex;
		int self = nodes.size();
		nodes.add(new Node(name, parent, local, global, primitives, node.has("skin") ? node.get("skin").getAsInt() : -1));
		for (JsonElement child : array(node, "children")) addNode(child.getAsInt(), self, depth + 1);
	}

	private Primitive primitive(JsonObject p) throws IOException {
		int mode = p.has("mode") ? p.get("mode").getAsInt() : 4;
		if (mode != 4) {
			warnings.add("Skipping non-triangle primitive (mode " + mode + ")");
			return null;
		}
		JsonObject attributes = p.getAsJsonObject("attributes");
		if (!attributes.has("POSITION")) return null;

		float[] positions = accessor(attributes.get("POSITION").getAsInt());
		float[] normals = attributes.has("NORMAL") ? accessor(attributes.get("NORMAL").getAsInt()) : null;
		int material = p.has("material") ? p.get("material").getAsInt() : -1;
		int uvSet = material >= 0 ? texCoordSet(material) : 0;
		float[] uvs = attributes.has("TEXCOORD_" + uvSet) ? accessor(attributes.get("TEXCOORD_" + uvSet).getAsInt()) : null;
		float[] colors = attributes.has("COLOR_0") ? rgba(attributes.get("COLOR_0").getAsInt()) : null;
		float[] rawJoints = attributes.has("JOINTS_0") ? accessor(attributes.get("JOINTS_0").getAsInt()) : null;
		float[] weights = attributes.has("WEIGHTS_0") ? accessor(attributes.get("WEIGHTS_0").getAsInt()) : null;
		int[] joints = null;
		if (rawJoints != null && weights != null) {
			joints = new int[rawJoints.length];
			for (int i = 0; i < joints.length; i++) joints[i] = (int) rawJoints[i];
		} else {
			weights = null;
		}

		int[] indices;
		if (p.has("indices")) {
			float[] raw = accessor(p.get("indices").getAsInt());
			indices = new int[raw.length];
			for (int i = 0; i < raw.length; i++) indices[i] = (int) raw[i];
		} else {
			indices = new int[positions.length / 3];
			Arrays.setAll(indices, i -> i);
		}
		int n = positions.length / 3;
		if (positions.length % 3 != 0 || normals != null && normals.length != n * 3 || uvs != null && uvs.length != n * 2 || colors != null && colors.length != n * 4
			|| joints != null && (joints.length != n * 4 || weights.length != n * 4))
			throw new IOException("Vertex attributes have mismatched counts");
		for (int i : indices) if (i < 0 || i >= n) throw new IOException("Vertex index " + i + " out of range (" + n + " vertices)");
		if (material < -1 || material >= materials.size()) throw new IOException("Material " + material + " out of range");
		// what Quads.build allocates for it: every vertex at full stride, and in the worst case a 4-vertex quad per triangle
		spend(((long) n * Quads.STRIDE + (long) indices.length / 3 * 4 * Quads.STRIDE) * Float.BYTES);
		return new Primitive(positions, normals, uvs, colors, indices, material, joints, weights);
	}

	private Material material(JsonObject m) throws IOException {
		String name = m.has("name") ? m.get("name").getAsString() : "material" + materials.size();
		float[] baseColor = {1, 1, 1, 1};
		int image = -1;
		if (m.has("pbrMetallicRoughness")) {
			JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
			if (pbr.has("baseColorFactor")) baseColor = floats(pbr.getAsJsonArray("baseColorFactor"), 4);
			if (pbr.has("baseColorTexture")) image = textureImage(pbr.getAsJsonObject("baseColorTexture"));
			if (pbr.has("metallicRoughnessTexture")) warnings.add("Material '" + name + "': metallic/roughness maps are ignored by vanilla shaders");
		}
		if (m.has("normalTexture") || m.has("occlusionTexture")) warnings.add("Material '" + name + "': normal/occlusion maps are ignored by vanilla shaders");

		AlphaMode alpha = m.has("alphaMode") ? AlphaMode.valueOf(m.get("alphaMode").getAsString()) : AlphaMode.OPAQUE;
		boolean doubleSided = m.has("doubleSided") && m.get("doubleSided").getAsBoolean();
		boolean emissive = m.has("emissiveTexture");
		if (m.has("emissiveFactor")) for (float f : floats(m.getAsJsonArray("emissiveFactor"), 3)) emissive |= f > 0;
		if (image >= images.size()) {
			warnings.add("Material '" + name + "': image " + image + " does not exist");
			image = -1;
		}
		JsonObject extras = m.has("extras") && m.get("extras").isJsonObject() ? m.getAsJsonObject("extras") : new JsonObject();
		boolean ao = !extras.has("clay_ao") || extras.get("clay_ao").getAsBoolean();
		float[] scroll = extras.has("clay_scroll") ? floats(extras.getAsJsonArray("clay_scroll"), 2) : null;
		int frametime = extras.has("clay_frametime") ? Math.max(0, extras.get("clay_frametime").getAsInt()) : 0;
		return new Material(name, baseColor, image, alpha, doubleSided, emissive, ao, scroll, frametime);
	}

	/** Objects named {@code collision} (or {@code collision.001}, ...) describe a block's shape for ClayShapes and aren't drawn. */
	public static boolean isCollision(Node node) {
		return node.name().startsWith("collision");
	}

	public Material material(int index) {
		return index >= 0 && index < materials.size() ? materials.get(index) : Material.DEFAULT;
	}

	private int texCoordSet(int material) {
		JsonObject m = array(json, "materials").get(material).getAsJsonObject();
		JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
		if (pbr == null || !pbr.has("baseColorTexture")) return 0;
		JsonObject tex = pbr.getAsJsonObject("baseColorTexture");
		return tex.has("texCoord") ? tex.get("texCoord").getAsInt() : 0;
	}

	private int textureImage(JsonObject textureInfo) {
		JsonObject texture = array(json, "textures").get(textureInfo.get("index").getAsInt()).getAsJsonObject();
		return texture.has("source") ? texture.get("source").getAsInt() : -1;
	}

	private ByteBuffer bufferView(int index) throws IOException {
		JsonObject view = array(json, "bufferViews").get(index).getAsJsonObject();
		if (view.get("buffer").getAsInt() != 0) throw new IOException("External buffers are not supported; export as .glb");
		int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
		return bin.slice(offset, view.get("byteLength").getAsInt()).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** Reads any accessor as a flat float array, applying normalization for integer types. */
	private float[] accessor(int index) throws IOException {
		JsonObject a = array(json, "accessors").get(index).getAsJsonObject();
		if (a.has("sparse")) throw new IOException("Sparse accessors are not supported");
		int count = a.get("count").getAsInt();
		int components = switch (a.get("type").getAsString()) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4" -> 4;
			case "MAT4" -> 16;
			default -> throw new IOException("Unsupported accessor type " + a.get("type").getAsString());
		};
		int componentType = a.get("componentType").getAsInt();
		int size = switch (componentType) {
			case 5120, 5121 -> 1;
			case 5122, 5123 -> 2;
			case 5125, 5126 -> 4;
			default -> throw new IOException("Unsupported component type " + componentType);
		};
		boolean normalized = a.has("normalized") && a.get("normalized").getAsBoolean();
		if (!a.has("bufferView")) throw new IOException("Accessors without a bufferView are not supported");

		JsonObject viewJson = array(json, "bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
		ByteBuffer view = bufferView(a.get("bufferView").getAsInt());
		int base = a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0;
		int stride = viewJson.has("byteStride") ? viewJson.get("byteStride").getAsInt() : size * components;
		// checked before allocating, so the declared count can't exceed what the file actually holds
		if (count < 0 || base < 0 || stride < size * components || count > 0 && (long) base + (long) (count - 1) * stride + size * components > view.remaining())
			throw new IOException("Accessor " + index + " reads past its bufferView");
		spend((long) count * components * Float.BYTES);
		float[] out = new float[count * components];

		for (int i = 0; i < count; i++) {
			for (int c = 0; c < components; c++) {
				int at = base + i * stride + c * size;
				out[i * components + c] = switch (componentType) {
					case 5120 -> normalized ? Math.max(view.get(at) / 127f, -1f) : view.get(at);
					case 5121 -> normalized ? (view.get(at) & 0xFF) / 255f : view.get(at) & 0xFF;
					case 5122 -> normalized ? Math.max(view.getShort(at) / 32767f, -1f) : view.getShort(at);
					case 5123 -> normalized ? (view.getShort(at) & 0xFFFF) / 65535f : view.getShort(at) & 0xFFFF;
					case 5125 -> (float) Integer.toUnsignedLong(view.getInt(at));
					default -> view.getFloat(at);
				};
			}
		}
		return out;
	}

	/** COLOR_0 may be VEC3 or VEC4; always return RGBA. */
	private float[] rgba(int index) throws IOException {
		float[] raw = accessor(index);
		JsonObject a = array(json, "accessors").get(index).getAsJsonObject();
		if (!a.get("type").getAsString().equals("VEC3")) return raw;
		int count = raw.length / 3;
		float[] out = new float[count * 4];
		for (int i = 0; i < count; i++) {
			System.arraycopy(raw, i * 3, out, i * 4, 3);
			out[i * 4 + 3] = 1;
		}
		return out;
	}

	private static JsonArray array(JsonObject o, String key) {
		return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
	}

	private void spend(long bytes) throws IOException {
		spentBytes += bytes;
		if (spentBytes > MAX_BYTES) throw new IOException("Model needs more than " + (MAX_BYTES >> 20) + " MB");
	}

	/** A fixed-size JSON number array, such as a color or a transform. */
	private static float[] floats(JsonArray a, int size) throws IOException {
		if (a.size() != size) throw new IOException("Expected " + size + " numbers, got " + a.size() + ": " + a);
		float[] out = new float[a.size()];
		for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
		return out;
	}
}
