package dev.gnottero.clay;

import dev.gnottero.clay.render.ClayMesh;
import dev.gnottero.clay.render.ClayRenderers;
import dev.gnottero.clay.render.GpuRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.Locale;

public final class ClayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath("clay", "gltf"), ClayAssets.CODEC);
		UnbakedModelDeserializer.register(ClayGeometry.TYPE, ClayGeometry.DESERIALIZER);
		GpuRenderer.register();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			var renderer = ClientCommands.literal("renderer");
			for (String mode : new String[] {"auto", "gpu", "cpu"}) {
				renderer.then(ClientCommands.literal(mode).executes(c -> {
					ClayRenderers.setMode(mode);
					c.getSource().sendFeedback(Component.literal("Clay renderer: " + ClayRenderers.describe()));
					return 1;
				}));
			}
			dispatcher.register(ClientCommands.literal("clay").executes(c -> {
				c.getSource().sendFeedback(Component.literal(status()));
				return 1;
			}).then(renderer));
		});
	}

	/** What /clay prints: the renderer, GPU memory, and every entity model drawn since the last reload. */
	static String status() {
		StringBuilder out = new StringBuilder("Clay renderer: ").append(ClayRenderers.describe())
			.append(String.format(Locale.ROOT, "\nGPU memory: %.1f MB", GpuRenderer.memory() / 1048576.0))
			.append("\n.glb files loaded: ").append(ClayAssets.all().size());
		ClayModel.meshes().stream().sorted(Comparator.comparing(m -> m.id.toString())).forEach(mesh ->
			out.append("\n  ").append(mesh.id).append(": ").append(mesh.quadCount()).append(" quads, ").append(mesh.paletteSize).append(" nodes")
				.append(mesh.isOnGpu() ? ", on the GPU" : ""));
		return out.toString();
	}
}
