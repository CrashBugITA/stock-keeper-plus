package com.stockkeeperplus.client;

import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.stockkeeperplus.StockKeeperPlus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Replaces Create's Stock Keeper request screen with {@link StockKeeperPlusScreen} whenever it opens.
 * Works for every source of that menu: the Stock Ticker block itself and Create: Remote Terminal,
 * which both open the {@code create:stock_keeper_request} menu type.
 */
public final class ScreenSwapHandler {
	private ScreenSwapHandler() {}

	public static void onScreenOpening(ScreenEvent.Opening event) {
		Screen next = event.getNewScreen();
		// Exact class match: leave subclasses from other mods (and our own screen) alone.
		if (next == null || next.getClass() != StockKeeperRequestScreen.class)
			return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null)
			return;

		StockKeeperRequestScreen original = (StockKeeperRequestScreen) next;
		try {
			event.setNewScreen(new StockKeeperPlusScreen(original.getMenu(), mc.player.getInventory(), original.getTitle()));
		} catch (RuntimeException e) {
			// Never break the vanilla Create screen because of us: fall back to it.
			StockKeeperPlus.LOGGER.error("Failed to open Stock Keeper Plus screen, using Create's screen instead", e);
			original.getMenu().screenReference = original;
		}
	}
}
