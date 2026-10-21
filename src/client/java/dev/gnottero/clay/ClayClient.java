package dev.gnottero.clay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.minecraft.resources.Identifier;

public final class ClayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath("clay", "gltf"), ClayAssets.CODEC);
		UnbakedModelDeserializer.register(ClayGeometry.TYPE, ClayGeometry.DESERIALIZER);
	}
}
