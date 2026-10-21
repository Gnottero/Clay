package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves the camera to ClayCamera's shot while one plays, detached so the player is drawn in it. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void clay$shot(float partialTicks, CallbackInfo ci) {
		ClayCamera.Shot shot = ClayCamera.shot(partialTicks);
		if (shot == null) return;
		setRotation(shot.yRot(), shot.xRot());
		setPosition(shot.pos());
		detached = true;
	}
}
