package com.stockkeeperplus.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.stockkeeperplus.StockKeeperPlus;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Favorite items, shared by every world and server. Stored as readable SNBT in
 * config/stockkeeperplus/favorites.snbt. Items are matched on item and components (so two
 * different enchanted books are two different favorites), never on count.
 */
public final class Favorites {
	private static final Path FILE = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("favorites.snbt");

	private static final List<ItemStack> ITEMS = new ArrayList<>();
	private static boolean collapsed;
	private static boolean loaded;

	private Favorites() {}

	public static boolean isFavorite(ItemStack stack) {
		for (ItemStack favorite : ITEMS)
			if (ItemStack.isSameItemSameComponents(favorite, stack))
				return true;
		return false;
	}

	public static boolean isEmpty() {
		return ITEMS.isEmpty();
	}

	/** Adds or removes the item. Returns true if it is a favorite afterwards. */
	public static boolean toggle(ItemStack stack, HolderLookup.Provider registries) {
		boolean removed = ITEMS.removeIf(favorite -> ItemStack.isSameItemSameComponents(favorite, stack));
		if (!removed)
			ITEMS.add(stack.copyWithCount(1));
		save(registries);
		return !removed;
	}

	/** Whether the Favorites category is collapsed (remembered on this computer only). */
	public static boolean isCollapsed() {
		return collapsed;
	}

	public static void setCollapsed(boolean value, HolderLookup.Provider registries) {
		if (collapsed == value)
			return;
		collapsed = value;
		save(registries);
	}

	/** Loads the file once. Needs the world's registries to decode item components. */
	public static void loadIfNeeded(HolderLookup.Provider registries) {
		if (loaded)
			return;
		loaded = true;
		if (!Files.exists(FILE))
			return;
		try {
			CompoundTag root = TagParser.parseTag(Files.readString(FILE, StandardCharsets.UTF_8));
			collapsed = root.getBoolean("Collapsed");
			ListTag list = root.getList("Items", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
				// Items from mods that are no longer installed decode as empty: skip them.
				if (!stack.isEmpty())
					ITEMS.add(stack);
			}
		} catch (IOException | CommandSyntaxException e) {
			StockKeeperPlus.LOGGER.error("Could not read favorites from {}", FILE, e);
		}
	}

	private static void save(@Nullable HolderLookup.Provider registries) {
		if (registries == null)
			return;
		CompoundTag root = new CompoundTag();
		root.putBoolean("Collapsed", collapsed);
		ListTag list = new ListTag();
		for (ItemStack stack : ITEMS)
			list.add(stack.save(registries));
		root.put("Items", list);
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			StockKeeperPlus.LOGGER.error("Could not save favorites to {}", FILE, e);
		}
	}
}
