package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a ClayCamera shot plays: hides the HUD (and with it the first-person hand) and draws the shot's fade and letterbox. */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void clay$shot(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!ClayCamera.playing()) return;
		Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.isHudHidden = true;
		ClayCamera.Overlay overlay = ClayCamera.overlay(deltaTracker.getGameTimeDeltaPartialTick(false));
		int width = graphics.guiWidth(), height = graphics.guiHeight(), bar = Math.round(overlay.letterbox() * height);
		if (bar > 0) {
			graphics.fill(0, 0, width, bar, 0xFF000000);
			graphics.fill(0, height - bar, width, height, 0xFF000000);
		}
		if ((overlay.color() >>> 24) > 0) graphics.fill(0, 0, width, height, overlay.color());
		ci.cancel();
	}
}
