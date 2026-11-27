package com.stockkeeperplus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.stockkeeperplus.client.ClientConfig;
import com.stockkeeperplus.client.KeyBindings;
import com.stockkeeperplus.client.ScreenSwapHandler;
import com.stockkeeperplus.client.TooltipHandler;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point. Never loaded on a dedicated server.
 */
@Mod(value = StockKeeperPlus.MOD_ID, dist = Dist.CLIENT)
public class StockKeeperPlus {
	public static final String MOD_ID = "stockkeeperplus";
	public static final Logger LOGGER = LogUtils.getLogger();

	public StockKeeperPlus(IEventBus modBus, ModContainer container) {
		container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
		// "Config" button in the Mods list, using NeoForge's generic config screen.
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
		modBus.addListener(KeyBindings::register);
		NeoForge.EVENT_BUS.addListener(ScreenSwapHandler::onScreenOpening);
		NeoForge.EVENT_BUS.addListener(TooltipHandler::onItemTooltip);
		LOGGER.info("Stock Keeper Plus loaded (client-side)");
	}
}
