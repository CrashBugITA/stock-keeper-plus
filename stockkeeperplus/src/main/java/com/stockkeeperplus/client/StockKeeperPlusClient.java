package com.stockkeeperplus.client;

import com.stockkeeperplus.StockKeeperPlus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point. Never loaded on a dedicated server.
 */
@Mod(value = StockKeeperPlus.MOD_ID, dist = Dist.CLIENT)
public class StockKeeperPlusClient {
	public StockKeeperPlusClient(IEventBus modBus, ModContainer container) {
		container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
		// "Config" button in the Mods list, using NeoForge's generic config screen.
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
		modBus.addListener(KeyBindings::register);
		modBus.addListener(RegisterClientTooltipComponentFactoriesEvent.class,
			event -> event.register(ContentsTooltip.class, ContentsTooltip.Renderer::new));
		NeoForge.EVENT_BUS.addListener(ScreenSwapHandler::onScreenOpening);
		NeoForge.EVENT_BUS.addListener(TooltipHandler::onItemTooltip);
		StockKeeperPlus.LOGGER.info("Stock Keeper Plus loaded (client-side)");
	}
}
