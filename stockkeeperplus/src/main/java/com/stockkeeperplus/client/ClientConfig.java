package com.stockkeeperplus.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side settings, stored in config/stockkeeperplus-client.toml.
 * Also editable in game from the Mods list (Config button).
 */
public final class ClientConfig {
	public static final ModConfigSpec SPEC;

	public static final ModConfigSpec.EnumValue<SortMode> SORT_MODE;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		builder.push("stockKeeper");
		SORT_MODE = builder
			.comment("How items are sorted inside each category of the Stock Keeper.",
				"Can also be changed with the sort button next to the search bar.")
			.translation("stockkeeperplus.config.sort_mode")
			.defineEnum("sortMode", SortMode.QUANTITY_DESC);
		builder.pop();

		SPEC = builder.build();
	}

	private ClientConfig() {}

	public static SortMode sortMode() {
		return SPEC.isLoaded() ? SORT_MODE.get() : SortMode.QUANTITY_DESC;
	}

	public static void setSortMode(SortMode mode) {
		if (!SPEC.isLoaded())
			return;
		SORT_MODE.set(mode);
		SORT_MODE.save();
	}
}
