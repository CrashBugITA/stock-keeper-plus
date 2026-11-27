package com.stockkeeperplus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.stockkeeperplus.network.NetworkSetup;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Common entry point, loaded on both sides. It only registers the optional network messages
 * (see {@link NetworkSetup}); everything visible lives in the client entry point
 * {@link com.stockkeeperplus.client.StockKeeperPlusClient}.
 * <p>
 * The mod works on the client alone. Installed on the server too (or in single player, where the
 * built-in server runs it), it also answers the client's requests for data clients cannot see.
 */
@Mod(StockKeeperPlus.MOD_ID)
public class StockKeeperPlus {
	public static final String MOD_ID = "stockkeeperplus";
	public static final Logger LOGGER = LogUtils.getLogger();

	public StockKeeperPlus(IEventBus modBus) {
		modBus.addListener(NetworkSetup::register);
	}
}
