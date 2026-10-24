package dev.gnottero.clay;

import net.fabricmc.api.ModInitializer;

public final class ClayMod implements ModInitializer {
	@Override
	public void onInitialize() {
		ClayCamera.register();
	}
}
