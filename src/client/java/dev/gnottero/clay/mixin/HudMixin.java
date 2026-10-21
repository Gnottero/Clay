package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the HUD, and with it the first-person hand, while a ClayCamera shot plays. */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void clay$shot(CallbackInfo ci) {
		if (!ClayCamera.playing()) return;
		Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.isHudHidden = true;
		ci.cancel();
	}
}
