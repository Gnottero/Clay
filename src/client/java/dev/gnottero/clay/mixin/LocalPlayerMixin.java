package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player stands still while a ClayCamera shot plays. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "applyInput", at = @At("TAIL"))
	private void clay$standStill(CallbackInfo ci) {
		if (!ClayCamera.playing()) return;
		LocalPlayer self = (LocalPlayer) (Object) this;
		self.xxa = 0;
		self.zza = 0;
		self.setJumping(false);
	}
}
