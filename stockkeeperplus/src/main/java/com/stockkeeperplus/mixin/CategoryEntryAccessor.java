package com.stockkeeperplus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen.CategoryEntry;

/**
 * Exposes the package-private fields of a category header in Create's Stock Keeper screen.
 */
@Mixin(CategoryEntry.class)
public interface CategoryEntryAccessor {
	@Accessor("hidden")
	boolean stockkeeperplus$isHidden();

	@Accessor("hidden")
	void stockkeeperplus$setHidden(boolean hidden);

	/** Y offset of the header row inside the scrolling item list (not scaled by scroll). */
	@Accessor("y")
	int stockkeeperplus$getY();

	@Accessor("y")
	void stockkeeperplus$setY(int y);

	/** Index into the ticker's category list, or -1 for "Unsorted". */
	@Accessor("targetBECategory")
	int stockkeeperplus$getTargetBECategory();
}
