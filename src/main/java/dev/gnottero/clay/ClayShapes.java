package dev.gnottero.clay;

import dev.gnottero.clay.gltf.Glb;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block shapes ({@link VoxelShape}) made from a .glb, for {@code getShape} and {@code getCollisionShape}. Works on the
 * server too: the file is read from the mod jar ({@code assets/<ns>/clay/<path>.glb}), not from resource packs.
 * Shapes are in block space with the model placed like a block model (Blender origin at the bottom center) and facing
 * as modelled; turn them for other facings with {@code Shapes.rotateHorizontal}.
 */
public final class ClayShapes {
	/** Voxels per block edge that {@link #fromModel} uses: 8, so 2 pixels. */
	public static final int RESOLUTION = 8;
	private static final Map<String, VoxelShape> CACHE = new ConcurrentHashMap<>();

	private ClayShapes() {}

	/**
	 * The model's {@code collision} objects (one box around each), when it has any; you can hide them in Blender, they're
	 * never drawn. Otherwise a {@link #voxelize} of the visible mesh.
	 */
	public static VoxelShape fromModel(Identifier model) {
		return CACHE.computeIfAbsent("model " + model, k -> {
			Glb glb = read(model);
			List<double[]> boxes = collisionBoxes(glb);
			return shape(boxes.isEmpty() ? voxels(glb, RESOLUTION) : boxes);
		});
	}

	/** One box around the visible mesh. */
	public static VoxelShape bounds(Identifier model) {
		return CACHE.computeIfAbsent("bounds " + model, k -> {
			double[] box = bounds(read(model), false);
			return box == null ? Shapes.empty() : Shapes.box(box[0], box[1], box[2], box[3], box[4], box[5]);
		});
	}

	/** The visible mesh filled in on a grid of {@code resolution} cells per block: the cells its surface touches and everything they enclose. */
	public static VoxelShape voxelize(Identifier model, int resolution) {
		return CACHE.computeIfAbsent("voxels " + resolution + " " + model, k -> shape(voxels(read(model), resolution)));
	}

	private static VoxelShape shape(List<double[]> boxes) {
		VoxelShape shape = Shapes.empty();
		for (double[] b : boxes) shape = Shapes.or(shape, Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]));
		return shape.optimize();
	}

	private static Glb read(Identifier model) {
		String path = "assets/" + model.getNamespace() + "/clay/" + model.getPath() + ".glb";
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			Optional<Path> file = mod.findPath(path);
			if (file.isEmpty()) continue;
			try {
				return Glb.read(Files.readAllBytes(file.get()));
			} catch (IOException e) {
				throw new IllegalArgumentException("Can't read " + path, e);
			}
		}
		throw new IllegalArgumentException("No mod has " + path);
	}

	/** Each collision object's bounds, in block space. */
	static List<double[]> collisionBoxes(Glb glb) {
		List<double[]> boxes = new ArrayList<>();
		for (Glb.Node node : glb.nodes) {
			if (!Glb.isCollision(node)) continue;
			Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), p = new Vector3f();
			for (Glb.Primitive primitive : node.primitives()) {
				for (int i = 0; i < primitive.vertexCount(); i++) {
					block(node, primitive, i, p);
					min.min(p);
					max.max(p);
				}
			}
			if (min.x <= max.x) boxes.add(new double[] {min.x, min.y, min.z, max.x, max.y, max.z});
		}
		return boxes;
	}

	/** Vertex {@code i} of a primitive in block space: Blender's origin (bottom center) at (0.5, 0, 0.5). */
	private static Vector3f block(Glb.Node node, Glb.Primitive primitive, int i, Vector3f out) {
		float[] p = primitive.positions();
		return node.global().transformPosition(out.set(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])).add(0.5f, 0, 0.5f);
	}

	/** Bounds of the visible (or collision) mesh in block space, null if it has no vertices. */
	static double[] bounds(Glb glb, boolean collision) {
		Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), p = new Vector3f();
		for (Glb.Node node : glb.nodes) {
			if (Glb.isCollision(node) != collision) continue;
			for (Glb.Primitive primitive : node.primitives()) {
				for (int i = 0; i < primitive.vertexCount(); i++) {
					block(node, primitive, i, p);
					min.min(p);
					max.max(p);
				}
			}
		}
		return min.x > max.x ? null : new double[] {min.x, min.y, min.z, max.x, max.y, max.z};
	}

	/**
	 * Voxelizes the visible mesh: cells its triangles pass through (sampled at a quarter cell), plus the cells they enclose
	 * (everything a flood fill from outside can't reach). Returns one box per run of solid cells along x.
	 */
	static List<double[]> voxels(Glb glb, int resolution) {
		double[] b = bounds(glb, false);
		if (b == null) return List.of();
		double cell = 1.0 / resolution;
		// the grid covers the bounds in whole cells, with an empty border so the flood fill can get around
		int x0 = (int) Math.floor(b[0] / cell) - 1, y0 = (int) Math.floor(b[1] / cell) - 1, z0 = (int) Math.floor(b[2] / cell) - 1;
		int nx = (int) Math.ceil(b[3] / cell) - x0 + 2, ny = (int) Math.ceil(b[4] / cell) - y0 + 2, nz = (int) Math.ceil(b[5] / cell) - z0 + 2;
		if ((long) nx * ny * nz > 1 << 24) throw new IllegalArgumentException("Model too large to voxelize at " + resolution + " cells per block");
		boolean[] solid = new boolean[nx * ny * nz];
		Vector3f a = new Vector3f(), c = new Vector3f(), d = new Vector3f();
		for (Glb.Node node : glb.nodes) {
			if (Glb.isCollision(node)) continue;
			for (Glb.Primitive primitive : node.primitives()) {
				int[] idx = primitive.indices();
				for (int t = 0; t + 2 < idx.length; t += 3) {
					block(node, primitive, idx[t], a);
					block(node, primitive, idx[t + 1], c);
					block(node, primitive, idx[t + 2], d);
					float longest = Math.max(a.distance(c), Math.max(c.distance(d), d.distance(a)));
					int steps = Math.max(1, (int) Math.ceil(longest / cell * 4));
					for (int i = 0; i <= steps; i++) {
						for (int j = 0; i + j <= steps; j++) {
							float u = (float) i / steps, v = (float) j / steps, w = 1 - u - v;
							int x = (int) Math.floor((a.x * w + c.x * u + d.x * v) / cell) - x0;
							int y = (int) Math.floor((a.y * w + c.y * u + d.y * v) / cell) - y0;
							int z = (int) Math.floor((a.z * w + c.z * u + d.z * v) / cell) - z0;
							solid[(x * ny + y) * nz + z] = true;
						}
					}
				}
			}
		}
		// flood the outside from a corner; whatever stays dry is inside the mesh
		boolean[] outside = new boolean[solid.length];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		outside[0] = true;
		queue.add(0);
		int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		while (!queue.isEmpty()) {
			int i = queue.poll(), x = i / (ny * nz), y = i / nz % ny, z = i % nz;
			for (int[] s : steps) {
				int px = x + s[0], py = y + s[1], pz = z + s[2];
				if (px < 0 || py < 0 || pz < 0 || px >= nx || py >= ny || pz >= nz) continue;
				int n = (px * ny + py) * nz + pz;
				if (outside[n] || solid[n]) continue;
				outside[n] = true;
				queue.add(n);
			}
		}
		List<double[]> boxes = new ArrayList<>();
		for (int y = 0; y < ny; y++) {
			for (int z = 0; z < nz; z++) {
				for (int x = 0; x < nx; x++) {
					if (outside[(x * ny + y) * nz + z]) continue;
					int start = x;
					while (x + 1 < nx && !outside[((x + 1) * ny + y) * nz + z]) x++;
					boxes.add(new double[] {(start + x0) * cell, (y + y0) * cell, (z + z0) * cell, (x + 1 + x0) * cell, (y + 1 + y0) * cell, (z + 1 + z0) * cell});
				}
			}
		}
		return boxes;
	}
}
