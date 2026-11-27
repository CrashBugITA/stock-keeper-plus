package com.stockkeeperplus.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.stockkeeperplus.client.ItemGridCover;

import net.createmod.catnip.data.Couple;

/**
 * While the Factory Gauges tab covers the item grid, nothing above the order row counts as hovered:
 * no item tooltips, no clicks or scroll-wheel amounts on hidden items, no JEI lookups.
 * The order row below stays usable.
 */
@Mixin(StockKeeperRequestScreen.class)
public abstract class StockKeeperRequestScreenMixin {
	@Shadow
	@Final
	Couple<Integer> noneHovered;

	@Shadow
	int orderY;

	@Inject(method = "getHoveredSlot", at = @At("HEAD"), cancellable = true)
	private void stockkeeperplus$hideCoveredGrid(int x, int y, CallbackInfoReturnable<Couple<Integer>> cir) {
		if ((Object) this instanceof ItemGridCover cover && cover.stockkeeperplus$coversItemGrid() && y < orderY)
			cir.setReturnValue(noneHovered);
	}
}
