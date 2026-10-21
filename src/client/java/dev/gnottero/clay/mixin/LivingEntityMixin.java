package dev.gnottero.clay.mixin;

import dev.gnottero.clay.ClayCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** While a ClayCamera shot plays, the local player's effects that change how the world looks read as absent, so they don't spoil the shot. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	private static final List<Holder<MobEffect>> VIEW_EFFECTS = List.of(MobEffects.BLINDNESS, MobEffects.DARKNESS, MobEffects.NAUSEA, MobEffects.NIGHT_VISION);

	@Inject(method = "hasEffect", at = @At("HEAD"), cancellable = true)
	private void clay$hasEffect(Holder<MobEffect> effect, CallbackInfoReturnable<Boolean> cir) {
		if (hides(effect)) cir.setReturnValue(false);
	}

	@Inject(method = "getEffect", at = @At("HEAD"), cancellable = true)
	private void clay$getEffect(Holder<MobEffect> effect, CallbackInfoReturnable<MobEffectInstance> cir) {
		if (hides(effect)) cir.setReturnValue(null);
	}

	@Inject(method = "getEffectBlendFactor", at = @At("HEAD"), cancellable = true)
	private void clay$getEffectBlendFactor(Holder<MobEffect> effect, float partial, CallbackInfoReturnable<Float> cir) {
		if (hides(effect)) cir.setReturnValue(0f);
	}

	private boolean hides(Holder<MobEffect> effect) {
		return ClayCamera.playing() && (Object) this == Minecraft.getInstance().player && VIEW_EFFECTS.contains(effect);
	}
}
