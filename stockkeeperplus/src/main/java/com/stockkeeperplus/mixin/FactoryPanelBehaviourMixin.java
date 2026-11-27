package com.stockkeeperplus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.stockkeeperplus.server.GaugeRegistry;

/**
 * Lets the server list the gauges it has loaded (GaugeRegistry) without scanning chunks.
 * Only adds a weak reference per panel; does nothing on the client.
 */
@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public abstract class FactoryPanelBehaviourMixin {
	@Inject(method = "initialize", at = @At("TAIL"))
	private void stockkeeperplus$register(CallbackInfo ci) {
		GaugeRegistry.register((FactoryPanelBehaviour) (Object) this);
	}
}
