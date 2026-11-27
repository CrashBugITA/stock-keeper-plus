package com.stockkeeperplus.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Adds exact stock / cart counts to item tooltips shown by {@link StockKeeperPlusScreen}.
 */
public final class TooltipHandler {
	private TooltipHandler() {}

	public static void onItemTooltip(ItemTooltipEvent event) {
		if (Minecraft.getInstance().screen instanceof StockKeeperPlusScreen screen)
			screen.appendCountTooltip(event.getItemStack(), event.getToolTip());
	}
}
