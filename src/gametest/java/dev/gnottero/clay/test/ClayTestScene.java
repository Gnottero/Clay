package dev.gnottero.clay.test;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gnottero.clay.ClayModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Draws the test models where {@link ClayGameTest} puts them. */
public final class ClayTestScene implements ClientModInitializer {
	static final ClayModel SHOWCASE = ClayModel.of(Identifier.fromNamespaceAndPath("clay_test", "showcase"));
	/** Where the showcase row stands; null hides it. */
	static volatile @Nullable Vec3 showcase;

	@Override
	public void onInitializeClient() {
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			Vec3 at = showcase;
			var level = Minecraft.getInstance().level;
			if (at == null || level == null) return;
			Vec3 cam = context.levelState().cameraRenderState.pos;
			PoseStack pose = context.poseStack();
			pose.pushPose();
			pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
			int light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(at.add(0, 0.5, 0)));
			SHOWCASE.submit(pose, context.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, at.distanceToSqr(cam));
			pose.popPose();
		});
	}
}
