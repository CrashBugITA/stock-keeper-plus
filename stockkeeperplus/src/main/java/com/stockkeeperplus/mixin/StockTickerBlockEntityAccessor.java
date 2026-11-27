package com.stockkeeperplus.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;

import net.minecraft.world.item.ItemStack;

/**
 * Exposes the category filters of a Stock Ticker (synced to the client).
 */
@Mixin(StockTickerBlockEntity.class)
public interface StockTickerBlockEntityAccessor {
	/** One filter item per category, in display order. An empty stack is an unnamed category. */
	@Accessor("categories")
	List<ItemStack> stockkeeperplus$getCategories();
}
