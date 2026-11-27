package com.stockkeeperplus.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.common.ContainerData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Finds the storage of a logistics network: every Packager Link of the network, the Packager it
 * sits on, and the container that Packager reads from.
 * <p>
 * The client can read the size of containers in chunks it has loaded, but never what is in them.
 * With the mod on the server too, the server lists every container it has loaded, with the slots
 * in use (see {@link ServerLink}). Containers seen before are cached per world in
 * config/stockkeeperplus/storage/ with the time they were last seen.
 * <p>
 * The network's fill level ({@link #summarize}) is always estimated from the stock the Stock
 * Ticker receives, so it works without the server part.
 */
public final class StorageTracker {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("storage");

	/** A container and whether its data is current (read now, or just reported by the server). */
	public record Entry(ContainerData container, boolean live) {}

	/** Fill level of the network, from the stock and the known containers. */
	public record Summary(int types, long items, boolean infiniteItems, long usedSlots, long totalSlots,
		long itemCapacity, int containers, int notLoaded) {

		public boolean known() {
			return totalSlots > 0;
		}

		/** Share of slots in use, 0..1 (can pass 1 if some containers were never seen). */
		public double slotFill() {
			return totalSlots <= 0 ? 0 : (double) usedSlots / totalSlots;
		}

		public double itemFill() {
			return itemCapacity <= 0 ? 0 : (double) items / itemCapacity;
		}
	}

	private static final Map<UUID, Map<String, ContainerData>> CACHE = new HashMap<>();
	@Nullable
	private static String cacheWorld;
	private static boolean dirty;

	private StorageTracker() {}

	/** Reads the containers in loaded chunks, merges what the server reported, returns all known ones. */
	public static List<Entry> update(UUID network) {
		ensureLoaded();
		Map<String, ContainerData> known = CACHE.computeIfAbsent(network, n -> new HashMap<>());
		Set<String> live = new HashSet<>();

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level != null && mc.player != null) {
			String dimension = level.dimension()
				.location()
				.toString();
			long now = System.currentTimeMillis();
			Set<Long> scannedChunks = new HashSet<>();

			ChunkPos center = mc.player.chunkPosition();
			int radius = mc.options.getEffectiveRenderDistance() + 1;
			for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
				for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
					LevelChunk chunk = level.getChunkSource()
						.getChunk(cx, cz, ChunkStatus.FULL, false);
					if (chunk == null)
						continue;
					scannedChunks.add(ChunkPos.asLong(cx, cz));
					for (BlockEntity blockEntity : chunk.getBlockEntities()
						.values()) {
						if (!(blockEntity instanceof PackagerLinkBlockEntity link) || link.behaviour == null
							|| !network.equals(link.behaviour.freqId))
							continue;
						ContainerData read = ContainerData.read(level, link.getPackager(), false, now);
						if (read == null)
							continue;
						// Keep the last fill level the server reported; the client cannot see it.
						ContainerData previous = known.get(read.key());
						if (previous != null && previous.fillKnown())
							read = new ContainerData(read.dimension(), read.pos(), read.block(), read.slots(),
								read.itemCapacity(), previous.usedSlots(), now);
						if (!read.equals(previous))
							dirty = true;
						known.put(read.key(), read);
						live.add(read.key());
					}
				}
			}

			// Cached containers in chunks we just looked at, but not linked anymore, are gone.
			dirty |= known.values()
				.removeIf(c -> c.dimension()
					.equals(dimension) && scannedChunks.contains(ChunkPos.asLong(c.pos())) && !live.contains(c.key()));
		}

		// With the mod on the server too, it reports every container it has loaded, with its fill.
		List<ContainerData> fromServer = ServerLink.containers();
		if (fromServer != null) {
			for (ContainerData container : fromServer) {
				known.put(container.key(), container);
				live.add(container.key());
			}
			dirty |= !fromServer.isEmpty();
		}

		List<Entry> result = new ArrayList<>(known.size());
		for (ContainerData container : known.values())
			result.add(new Entry(container, live.contains(container.key())));
		return result;
	}

	/** The network's fill level: what the stock holds against what the known containers can hold. */
	public static Summary summarize(@Nullable InventorySummary stock, List<Entry> containers) {
		int types = 0;
		long items = 0;
		long usedSlots = 0;
		boolean infinite = false;
		if (stock != null) {
			for (BigItemStack entry : stock.getStacks()) {
				types++;
				if (CountFormat.isInfinite(entry.count)) {
					infinite = true;
					continue;
				}
				items += entry.count;
				int stackSize = Math.max(1, entry.stack.getMaxStackSize());
				// Fewest slots these items can take: exact when containers are kept compact.
				usedSlots += (entry.count + stackSize - 1) / stackSize;
			}
		}
		long totalSlots = 0;
		long capacity = 0;
		int notLoaded = 0;
		for (Entry entry : containers) {
			totalSlots += entry.container()
				.slots();
			capacity += entry.container()
				.itemCapacity();
			if (!entry.live())
				notLoaded++;
		}
		return new Summary(types, items, infinite, usedSlots, totalSlots, capacity, containers.size(), notLoaded);
	}

	// ---------------------------------------------------------------------------------------------
	// Cache on disk
	// ---------------------------------------------------------------------------------------------

	private static void ensureLoaded() {
		String world = WorldKey.current();
		if (Objects.equals(world, cacheWorld))
			return;
		CACHE.clear();
		dirty = false;
		cacheWorld = world;
		Path file = file(world);
		if (file == null || !Files.exists(file))
			return;
		try {
			CompoundTag root = TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8));
			ListTag list = root.getList("Containers", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag tag = list.getCompound(i);
				ResourceLocation block = ResourceLocation.tryParse(tag.getString("Block"));
				if (block == null || !tag.hasUUID("Network"))
					continue;
				ContainerData container = new ContainerData(tag.getString("Dimension"), BlockPos.of(tag.getLong("Pos")),
					block, tag.getInt("Slots"), tag.getLong("Capacity"), tag.contains("Used") ? tag.getInt("Used") : -1,
					tag.getLong("LastSeen"));
				CACHE.computeIfAbsent(tag.getUUID("Network"), n -> new HashMap<>())
					.put(container.key(), container);
			}
		} catch (IOException | CommandSyntaxException e) {
			StockKeeperPlus.LOGGER.error("Could not read the storage cache from {}", file, e);
		}
	}

	/** Writes the cache if something changed since the last save. */
	public static void save() {
		if (!dirty)
			return;
		Path file = file(cacheWorld);
		if (file == null)
			return;
		ListTag list = new ListTag();
		for (Map.Entry<UUID, Map<String, ContainerData>> network : CACHE.entrySet()) {
			for (ContainerData container : network.getValue()
				.values()) {
				CompoundTag tag = new CompoundTag();
				tag.putUUID("Network", network.getKey());
				tag.putString("Dimension", container.dimension());
				tag.putLong("Pos", container.pos()
					.asLong());
				tag.putString("Block", container.block()
					.toString());
				tag.putInt("Slots", container.slots());
				tag.putLong("Capacity", container.itemCapacity());
				if (container.fillKnown())
					tag.putInt("Used", container.usedSlots());
				tag.putLong("LastSeen", container.lastSeen());
				list.add(tag);
			}
		}
		CompoundTag root = new CompoundTag();
		root.put("Containers", list);
		try {
			Files.createDirectories(DIR);
			Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException e) {
			StockKeeperPlus.LOGGER.error("Could not save the storage cache to {}", file, e);
		}
	}

	@Nullable
	private static Path file(@Nullable String world) {
		return world == null ? null : DIR.resolve(world + ".snbt");
	}
}
