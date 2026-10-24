package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Moves the camera to ClayCamera's shot while one plays, detached so the player is drawn in it, with the shot's roll and
 * field of view.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private boolean detached;

	/** The shot this frame, if one plays: set while aligning, read by the field of view that is worked out next. */
	@Unique
	private ClayCamera.@Nullable Shot clay$shot;
	@Unique
	private float clay$roll;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void clay$shot(float partialTicks, CallbackInfo ci) {
		ClayCamera.Shot shot = clay$shot = ClayCamera.shot(partialTicks);
		if (shot == null) return;
		clay$roll = shot.roll() * Mth.DEG_TO_RAD;
		setRotation(shot.yRot(), shot.xRot());
		clay$roll = 0;
		setPosition(shot.pos());
		detached = true;
	}

	/** setRotation builds the camera's orientation with no roll; while a shot sets it, give it the shot's. */
	@ModifyArg(method = "setRotation", at = @At(value = "INVOKE", target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"), index = 2)
	private float clay$roll(float roll) {
		return roll + clay$roll;
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void clay$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (clay$shot != null && clay$shot.fov() > 0) cir.setReturnValue(clay$shot.fov());
	}
}
