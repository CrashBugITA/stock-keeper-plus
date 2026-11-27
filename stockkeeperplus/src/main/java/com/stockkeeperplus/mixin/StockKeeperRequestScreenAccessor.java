package com.stockkeeperplus.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;

import net.createmod.catnip.data.Couple;

/**
 * Exposes the non-public parts of Create's Stock Keeper screen that the subclass needs.
 */
@Mixin(StockKeeperRequestScreen.class)
public interface StockKeeperRequestScreenAccessor {
	/**
	 * Returns (categoryIndex, slotIndex) of the hovered entry. Special category indices:
	 * -1 = order row, -2 = recipe (blueprint) row. Returns {@link #stockkeeperplus$getNoneHovered()}
	 * (compared by identity) when nothing is hovered.
	 */
	@Invoker("getHoveredSlot")
	Couple<Integer> stockkeeperplus$getHoveredSlot(int mouseX, int mouseY);

	@Accessor("noneHovered")
	Couple<Integer> stockkeeperplus$getNoneHovered();

	/** Pushes the search text to JEI according to the user's sync mode. */
	@Invoker("syncJEI")
	void stockkeeperplus$syncJEI(boolean fromJei);

	/**
	 * Rebuilds displayedItems and categories from the current stock and search text.
	 * Create normally calls it from containerTick.
	 */
	@Invoker("refreshSearchResults")
	void stockkeeperplus$refreshSearchResults(boolean scrollBackUp);

	@Invoker("updateCraftableAmounts")
	void stockkeeperplus$updateCraftableAmounts();

	/** Makes Create drop the next typed character (used when a shortcut also produces a char). */
	@Accessor("ignoreTextInput")
	void stockkeeperplus$setIgnoreTextInput(boolean ignore);

	/** Indices of the categories this player has collapsed; -1 is the "Unsorted" category. */
	@Accessor("hiddenCategories")
	Set<Integer> stockkeeperplus$getHiddenCategories();

	/** True when the player may lock the network, i.e. Create shows the lock button. */
	@Accessor("isAdmin")
	boolean stockkeeperplus$isAdmin();

	/** True while programming a Redstone Requester or Table Cloth held in the main hand. */
	@Accessor("encodeRequester")
	boolean stockkeeperplus$isEncodingRequester();

	@Accessor("itemsX")
	int stockkeeperplus$getItemsX();

	@Accessor("itemsY")
	int stockkeeperplus$getItemsY();
}
