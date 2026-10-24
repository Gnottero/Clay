package dev.gnottero.clay.test;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gnottero.clay.ClayAnimator;
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

import java.util.ArrayList;
import java.util.List;

/** Draws the test models where {@link ClayGameTest} puts them. */
public final class ClayTestScene implements ClientModInitializer {
	static final ClayModel SHOWCASE = ClayModel.of(Identifier.fromNamespaceAndPath("clay_test", "showcase"));
	static final ClayModel SKINNED = ClayModel.of(Identifier.fromNamespaceAndPath("clay_test", "skinned"));
	/** Where the showcase row stands; null hides it. */
	static volatile @Nullable Vec3 showcase;
	/** Skinned models, each frozen in a pose, standing in a row from {@link #skinned}; null hides them. */
	static volatile @Nullable Vec3 skinned;
	static final List<ClayAnimator> POSES = new ArrayList<>();

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
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			Vec3 at = skinned;
			if (at == null) return;
			Vec3 cam = context.levelState().cameraRenderState.pos;
			PoseStack pose = context.poseStack();
			for (int i = 0; i < POSES.size(); i++) {
				pose.pushPose();
				pose.translate(at.x + 1.5 * i - cam.x, at.y - cam.y, at.z - cam.z);
				SKINNED.submit(pose, context.submitNodeCollector(), LightCoordsUtil.FULL_SKY, OverlayTexture.NO_OVERLAY, at.distanceToSqr(cam), POSES.get(i));
				pose.popPose();
			}
		});
	}
}
