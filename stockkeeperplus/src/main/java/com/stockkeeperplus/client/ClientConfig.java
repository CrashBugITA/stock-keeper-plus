package com.stockkeeperplus.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side settings, stored in config/stockkeeperplus-client.toml.
 * Also editable in game from the Mods list (Config button). Changes apply the next time the
 * Stock Keeper screen is drawn; nothing needs a restart.
 */
public final class ClientConfig {
	public static final ModConfigSpec SPEC;

	public static final ModConfigSpec.EnumValue<SortMode> SORT_MODE;
	public static final ModConfigSpec.BooleanValue REMEMBER_ORDER;
	public static final ModConfigSpec.BooleanValue REMEMBER_SEARCH;
	public static final ModConfigSpec.BooleanValue FAVORITES;
	public static final ModConfigSpec.BooleanValue COUNT_TOOLTIP;
	public static final ModConfigSpec.BooleanValue NUMPAD_ENTER_SENDS;

	public static final ModConfigSpec.BooleanValue GAUGES_TAB;
	public static final ModConfigSpec.BooleanValue STORAGE_TAB;
	public static final ModConfigSpec.BooleanValue STORAGE_BAR;

	public static final ModConfigSpec.BooleanValue GAUGE_ANIMATIONS;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		builder.translation("stockkeeperplus.configuration.stockKeeper")
			.push("stockKeeper");
		SORT_MODE = builder
			.comment("How items are sorted inside each category of the Stock Keeper.",
				"Can also be changed with the sort button next to the search bar.")
			.translation("stockkeeperplus.configuration.sortMode")
			.defineEnum("sortMode", SortMode.QUANTITY_DESC);
		REMEMBER_ORDER = builder
			.comment("Keep the order (cart) when the screen closes, separately for every Stock Ticker.")
			.translation("stockkeeperplus.configuration.rememberOrder")
			.define("rememberOrder", true);
		REMEMBER_SEARCH = builder
			.comment("Keep the search text when the screen closes, separately for every Stock Ticker.")
			.translation("stockkeeperplus.configuration.rememberSearch")
			.define("rememberSearch", true);
		FAVORITES = builder
			.comment("Alt + click pins items to a Favorites category at the top of the list.")
			.translation("stockkeeperplus.configuration.favorites")
			.define("favorites", true);
		COUNT_TOOLTIP = builder
			.comment("Show the exact amount in stock and in the order in item tooltips.")
			.translation("stockkeeperplus.configuration.countTooltip")
			.define("countTooltip", true);
		NUMPAD_ENTER_SENDS = builder
			.comment("The numpad Enter key sends the order, like Shift + Enter.")
			.translation("stockkeeperplus.configuration.numpadEnterSends")
			.define("numpadEnterSends", true);
		builder.pop();

		builder.translation("stockkeeperplus.configuration.tabs")
			.push("tabs");
		GAUGES_TAB = builder
			.comment("Show the Factory Gauges tab.")
			.translation("stockkeeperplus.configuration.gaugesTab")
			.define("gaugesTab", true);
		STORAGE_TAB = builder
			.comment("Show the Storage tab.")
			.translation("stockkeeperplus.configuration.storageTab")
			.define("storageTab", true);
		STORAGE_BAR = builder
			.comment("Show the storage fill bar under the order slots.")
			.translation("stockkeeperplus.configuration.storageBar")
			.define("storageBar", true);
		builder.pop();

		builder.translation("stockkeeperplus.configuration.gauges")
			.push("gauges");
		GAUGE_ANIMATIONS = builder
			.comment("Animate the gauge map: a flash when a gauge sends a request, moving arrows while it waits.")
			.translation("stockkeeperplus.configuration.gaugeAnimations")
			.define("animations", true);
		builder.pop();

		SPEC = builder.build();
	}

	private ClientConfig() {}

	private static boolean get(ModConfigSpec.BooleanValue value) {
		return !SPEC.isLoaded() || value.get();
	}

	public static SortMode sortMode() {
		return SPEC.isLoaded() ? SORT_MODE.get() : SortMode.QUANTITY_DESC;
	}

	public static void setSortMode(SortMode mode) {
		if (!SPEC.isLoaded())
			return;
		SORT_MODE.set(mode);
		SORT_MODE.save();
	}

	public static boolean rememberOrder() {
		return get(REMEMBER_ORDER);
	}

	public static boolean rememberSearch() {
		return get(REMEMBER_SEARCH);
	}

	public static boolean favorites() {
		return get(FAVORITES);
	}

	public static boolean countTooltip() {
		return get(COUNT_TOOLTIP);
	}

	public static boolean numpadEnterSends() {
		return get(NUMPAD_ENTER_SENDS);
	}

	public static boolean gaugesTab() {
		return get(GAUGES_TAB);
	}

	public static boolean storageTab() {
		return get(STORAGE_TAB);
	}

	public static boolean storageBar() {
		return get(STORAGE_BAR);
	}

	public static boolean gaugeAnimations() {
		return get(GAUGE_ANIMATIONS);
	}
}
