package com.stockkeeperplus.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.logistics.BigItemStack;
import com.stockkeeperplus.StockKeeperPlus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Keeps the Stock Keeper order ("cart") between openings, separately for every Stock Ticker.
 * <p>
 * One readable SNBT file per world or server in config/stockkeeperplus/carts/, with one entry per
 * ticker (dimension + position). The Remote Terminal opens the same ticker, so it shares the cart.
 * Only stays on this computer.
 */
public final class CartStore {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("carts");

	private CartStore() {}

	public static List<BigItemStack> load(ResourceKey<Level> dimension, BlockPos tickerPos,
		HolderLookup.Provider registries) {
		List<BigItemStack> cart = new ArrayList<>();
		Path file = file();
		if (file == null || !Files.exists(file))
			return cart;
		try {
			CompoundTag root = TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8));
			ListTag entries = root.getList(tickerKey(dimension, tickerPos), Tag.TAG_COMPOUND);
			for (int i = 0; i < entries.size(); i++) {
				CompoundTag entry = entries.getCompound(i);
				ItemStack stack = ItemStack.parseOptional(registries, entry.getCompound("Item"));
				int count = entry.getInt("Count");
				// Items from mods that are no longer installed decode as empty: skip them.
				if (!stack.isEmpty() && count > 0)
					cart.add(new BigItemStack(stack, count));
			}
		} catch (IOException | CommandSyntaxException e) {
			StockKeeperPlus.LOGGER.error("Could not read saved Stock Keeper cart from {}", file, e);
		}
		return cart;
	}

	/** Saves the cart; an empty cart removes the ticker's entry. */
	public static void save(ResourceKey<Level> dimension, BlockPos tickerPos, List<BigItemStack> cart,
		HolderLookup.Provider registries) {
		Path file = file();
		if (file == null)
			return;
		try {
			CompoundTag root = Files.exists(file)
				? TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8))
				: new CompoundTag();
			String key = tickerKey(dimension, tickerPos);
			if (cart.isEmpty()) {
				if (!root.contains(key))
					return; // nothing saved and nothing to save
				root.remove(key);
			} else {
				ListTag entries = new ListTag();
				for (BigItemStack entry : cart) {
					CompoundTag tag = new CompoundTag();
					tag.put("Item", entry.stack.copyWithCount(1)
						.save(registries));
					tag.putInt("Count", entry.count);
					entries.add(tag);
				}
				root.put(key, entries);
			}
			Files.createDirectories(DIR);
			if (root.isEmpty())
				Files.deleteIfExists(file);
			else
				Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException | CommandSyntaxException e) {
			StockKeeperPlus.LOGGER.error("Could not save Stock Keeper cart to {}", file, e);
		}
	}

	private static String tickerKey(ResourceKey<Level> dimension, BlockPos pos) {
		return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** File for the current single-player world or multiplayer server, or null if unknown. */
	@Nullable
	private static Path file() {
		String world = WorldKey.current();
		return world == null ? null : DIR.resolve(world + ".snbt");
	}
}
