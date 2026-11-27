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
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.stockkeeperplus.StockKeeperPlus;

import net.createmod.catnip.math.BlockFace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Finds the storage of a logistics network, client-side only: every Packager Link of the network,
 * the Packager it sits on, and the container that Packager reads from.
 * <p>
 * The client knows how big each container is (number of slots and slot limits, also for Create's
 * vaults), but not what is in it: neither Minecraft nor Create send container contents to clients.
 * So the network's fill level is estimated from the stock the Stock Ticker already receives.
 * <p>
 * As for gauges, only loaded chunks can be read; containers seen before are cached per world in
 * config/stockkeeperplus/storage/ with the time they were last seen.
 */
public final class StorageTracker {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("storage");
	/** Slot limits above this (drawers, bulk storage) still count as one stack per slot. */
	private static final int STACK = 64;

	/** One container behind a Packager of the network. */
	public record Container(String dimension, BlockPos pos, ResourceLocation block, int slots, long itemCapacity,
		long lastSeen) {

		public ItemStack icon() {
			Item item = BuiltInRegistries.ITEM.get(block);
			return new ItemStack(item);
		}
	}

	public record Entry(Container container, boolean live) {}

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

	private static final Map<UUID, Map<BlockPos, Container>> CACHE = new HashMap<>();
	@Nullable
	private static String cacheWorld;
	private static boolean dirty;

	private StorageTracker() {}

	/** Reads the containers in loaded chunks, updates the cache and returns all known ones. */
	public static List<Entry> update(UUID network) {
		ensureLoaded();
		Map<BlockPos, Container> known = CACHE.computeIfAbsent(network, n -> new HashMap<>());

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null)
			return entries(known, Set.of());

		String dimension = level.dimension()
			.location()
			.toString();
		long now = System.currentTimeMillis();
		Set<Long> scannedChunks = new HashSet<>();
		Set<BlockPos> live = new HashSet<>();

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
					Container container = read(level, dimension, link.getPackager(), now);
					if (container == null)
						continue;
					if (!container.equals(known.get(container.pos())))
						dirty = true;
					known.put(container.pos(), container);
					live.add(container.pos());
				}
			}
		}

		// Cached containers in chunks we just looked at, but not linked anymore, are gone.
		dirty |= known.values()
			.removeIf(c -> c.dimension()
				.equals(dimension) && scannedChunks.contains(ChunkPos.asLong(c.pos())) && !live.contains(c.pos()));

		return entries(known, live);
	}

	private static List<Entry> entries(Map<BlockPos, Container> known, Set<BlockPos> live) {
		List<Entry> result = new ArrayList<>(known.size());
		for (Container container : known.values())
			result.add(new Entry(container, live.contains(container.pos())));
		return result;
	}

	@Nullable
	private static Container read(ClientLevel level, String dimension, @Nullable PackagerBlockEntity packager,
		long now) {
		if (packager == null || packager.targetInventory == null)
			return null;
		// The face of the packager that touches the container, seen from the container's side.
		BlockFace face = packager.targetInventory.getTarget()
			.getOpposite();
		BlockPos pos = face.getPos();
		if (!level.isLoaded(pos))
			return null;
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face.getFace());
		if (handler == null || handler.getSlots() == 0)
			return null;

		// A vault is one container however many of its blocks have a packager: count it once.
		if (level.getBlockEntity(pos) instanceof ItemVaultBlockEntity vault && vault.getController() != null)
			pos = vault.getController();

		long capacity = 0;
		for (int slot = 0; slot < handler.getSlots(); slot++)
			capacity += Math.min(STACK, handler.getSlotLimit(slot));
		ResourceLocation block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos)
			.getBlock());
		return new Container(dimension, pos.immutable(), block, handler.getSlots(), capacity, now);
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
				Container container = new Container(tag.getString("Dimension"), BlockPos.of(tag.getLong("Pos")), block,
					tag.getInt("Slots"), tag.getLong("Capacity"), tag.getLong("LastSeen"));
				CACHE.computeIfAbsent(tag.getUUID("Network"), n -> new HashMap<>())
					.put(container.pos(), container);
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
		for (Map.Entry<UUID, Map<BlockPos, Container>> network : CACHE.entrySet()) {
			for (Container container : network.getValue()
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
