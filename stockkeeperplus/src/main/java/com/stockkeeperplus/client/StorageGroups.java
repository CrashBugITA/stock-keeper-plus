package com.stockkeeperplus.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.common.ContainerData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Names the player gives to groups of containers in the Storage tab ("Main storage", "Iron"...).
 * Stored on this computer only, per world, keyed by each container's position, so groups survive
 * while the containers are far away or unloaded. File: config/stockkeeperplus/groups/world.snbt.
 */
public final class StorageGroups {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("groups");

	private static final Map<String, String> GROUP_BY_CONTAINER = new HashMap<>();
	@Nullable
	private static String loadedWorld;

	private StorageGroups() {}

	/** Group name of a container, or null if it is not in a group. */
	@Nullable
	public static String groupOf(ContainerData container) {
		ensureLoaded();
		return GROUP_BY_CONTAINER.get(container.key());
	}

	/** Puts the container in the group, or takes it out of any group if the name is blank. */
	public static void setGroup(ContainerData container, String name) {
		ensureLoaded();
		String trimmed = name.trim();
		if (trimmed.isEmpty())
			GROUP_BY_CONTAINER.remove(container.key());
		else
			GROUP_BY_CONTAINER.put(container.key(), trimmed);
		save();
	}

	/** Renames a group (blank: dissolves it, its containers go back to their own rows). */
	public static void renameGroup(String oldName, String newName) {
		ensureLoaded();
		String trimmed = newName.trim();
		GROUP_BY_CONTAINER.replaceAll((key, group) -> group.equals(oldName) ? trimmed : group);
		GROUP_BY_CONTAINER.values()
			.removeIf(String::isEmpty);
		save();
	}

	/** Existing group names, sorted, for completing what the player types. */
	public static TreeSet<String> names() {
		ensureLoaded();
		return new TreeSet<>(GROUP_BY_CONTAINER.values());
	}

	private static void ensureLoaded() {
		String world = WorldKey.current();
		if (Objects.equals(world, loadedWorld))
			return;
		loadedWorld = world;
		GROUP_BY_CONTAINER.clear();
		Path file = file();
		if (file == null || !Files.exists(file))
			return;
		try {
			CompoundTag root = TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8));
			for (String key : root.getAllKeys())
				GROUP_BY_CONTAINER.put(key, root.getString(key));
		} catch (IOException | CommandSyntaxException e) {
			StockKeeperPlus.LOGGER.error("Could not read storage groups from {}", file, e);
		}
	}

	private static void save() {
		Path file = file();
		if (file == null)
			return;
		CompoundTag root = new CompoundTag();
		GROUP_BY_CONTAINER.forEach(root::putString);
		try {
			Files.createDirectories(DIR);
			Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			StockKeeperPlus.LOGGER.error("Could not save storage groups to {}", file, e);
		}
	}

	@Nullable
	private static Path file() {
		return loadedWorld == null ? null : DIR.resolve(loadedWorld + ".snbt");
	}
}
