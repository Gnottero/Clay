package dev.gnottero.clay;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import dev.gnottero.clay.gltf.Glb;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Atlas sprite source ({@code "type": "clay:gltf"}) that loads every {@code assets/<ns>/clay/<path>.glb}
 * and stitches its embedded images into the block atlas as {@code <ns>:clay/<path>/<imageIndex>}.
 * <p>
 * It runs at the start of every resource reload, before models bake, so it is also where the parsed
 * glTF files are cached for the rest of the reload: one parse per file per reload.
 */
public final class ClayAssets implements SpriteSource {
	public static final ClayAssets INSTANCE = new ClayAssets();
	public static final MapCodec<ClayAssets> CODEC = MapCodec.unit(INSTANCE);

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final FileToIdConverter GLB = new FileToIdConverter("clay", ".glb");
	private static volatile Map<Identifier, Glb> loaded = Map.of();
	private static volatile int generation;

	private ClayAssets() {}

	/** The parsed model for {@code ns:path} ({@code assets/ns/clay/path.glb}), or null if missing or broken. */
	public static @Nullable Glb get(Identifier model) {
		return loaded.get(model);
	}

	/** Counts resource reloads, so caches can tell their data is stale. */
	public static int generation() {
		return generation;
	}

	/** Every loaded model, for /clay. */
	public static Map<Identifier, Glb> all() {
		return loaded;
	}

	public static Identifier spriteId(Identifier model, int image) {
		return model.withPrefix("clay/").withSuffix("/" + image);
	}

	@Override
	public void run(ResourceManager manager, SpriteSource.Output output) {
		Map<Identifier, Glb> next = new HashMap<>();
		GLB.listMatchingResources(manager).forEach((file, resource) -> {
			Identifier id = GLB.fileToId(file);
			try (InputStream in = resource.open()) {
				Glb glb = Glb.read(in.readAllBytes());
				glb.warnings.forEach(w -> LOGGER.warn("[clay] {}: {}", id, w));
				next.put(id, glb);
				for (int i = 0; i < glb.images.size(); i++) {
					byte[] png = glb.images.get(i);
					if (png == null) continue;
					// a material's clay_frametime makes its image a vertical strip of square frames, animated by the atlas like a vanilla .mcmeta
					int image = i, frametime = glb.materials.stream().filter(m -> m.image() == image).mapToInt(Glb.Material::frametime).max().orElse(0);
					ResourceMetadata meta = frametime <= 0 ? ResourceMetadata.EMPTY : ResourceMetadata.of(AnimationMetadataSection.TYPE,
						new AnimationMetadataSection(Optional.empty(), Optional.empty(), Optional.empty(), frametime, false));
					output.add(spriteId(id, i), new Resource(resource.source(), () -> new ByteArrayInputStream(png), () -> meta));
				}
			} catch (Exception e) {
				LOGGER.error("[clay] Failed to load {}", file, e);
			}
		});
		loaded = next;
		generation++;
	}

	@Override
	public MapCodec<ClayAssets> codec() {
		return CODEC;
	}
}
