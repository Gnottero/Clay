package dev.gnottero.clay;

import dev.gnottero.clay.render.ClayMesh;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * How one copy of a {@link ClayModel} looks: its {@link ClayAnimator}, a tint, swapped textures and scrolling speeds, all
 * without copying the model. Keep one per entity and draw with
 * {@link ClayModel#submit(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, int, int, double, ClayInstance)}:
 * <pre>{@code
 * instance.tint(0xFFFF8080)                                                  // reddish
 *     .texture("cloth", Identifier.withDefaultNamespace("block/red_wool"))  // any block atlas sprite
 *     .scroll("water", 0, 0.5f);                                             // half the image per second
 * }</pre>
 * Materials are named as in Blender. Swapped-in textures must be in the block atlas: vanilla block textures, and every
 * image in a Clay model ({@link ClayAssets#spriteId}).
 */
public final class ClayInstance {
	private final ClayAnimator animator;
	private int tint = -1;
	private final Map<String, Identifier> textures = new HashMap<>();
	private final Map<String, float[]> scrolls = new HashMap<>();

	public ClayInstance(ClayModel model) {
		animator = new ClayAnimator(model);
	}

	public ClayAnimator animator() {
		return animator;
	}

	/** ARGB multiplied into every vertex color; -1 (white) for none. */
	public ClayInstance tint(int argb) {
		tint = argb;
		return this;
	}

	public int tint() {
		return tint;
	}

	/** Draws {@code material} with the block atlas sprite {@code sprite} instead of its own image. */
	public ClayInstance texture(String material, Identifier sprite) {
		textures.put(material, sprite);
		return this;
	}

	public ClayInstance resetTexture(String material) {
		textures.remove(material);
		return this;
	}

	/** Scrolls {@code material}'s texture by {@code (u, v)} image widths per second, replacing its clay_scroll. (0, 0) stops it. */
	public ClayInstance scroll(String material, float u, float v) {
		scrolls.put(material, new float[] {u, v});
		return this;
	}

	/** Writes this instance's textures and scrolls over the model's own in a material table. */
	void apply(ClayMesh mesh, TextureAtlas atlas, double seconds, float[] table) {
		if (!textures.isEmpty()) {
			for (Map.Entry<String, Identifier> e : textures.entrySet()) {
				int slot = mesh.slot(e.getKey());
				if (slot < 0) continue;
				TextureAtlasSprite sprite = atlas.getSprite(e.getValue());
				int o = slot * ClayMesh.MATERIAL_FLOATS;
				table[o] = sprite.getU0();
				table[o + 1] = sprite.getV0();
				table[o + 2] = sprite.getU1();
				table[o + 3] = sprite.getV1();
			}
		}
		if (!scrolls.isEmpty()) {
			for (Map.Entry<String, float[]> e : scrolls.entrySet()) {
				int slot = mesh.slot(e.getKey());
				if (slot >= 0) ClayMesh.scroll(table, slot, e.getValue()[0], e.getValue()[1], seconds);
			}
		}
	}
}
