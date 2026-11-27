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
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.common.GaugeData;
import com.stockkeeperplus.common.GaugeData.Gauge;
import com.stockkeeperplus.common.GaugeData.Source;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Finds the Factory Gauges of a logistics network.
 * <p>
 * Minecraft sends the full state of every gauge (item, target, level in storage, promises, status,
 * address, network) to clients, but only for chunks the client has loaded. So gauges near the
 * player are read live from the loaded block entities. With the mod on the server too, the server
 * also reports every gauge it has loaded (see {@link ServerLink}). Every gauge ever seen is kept in
 * a cache with the time it was last seen (stored per world in config/stockkeeperplus/gauges/), so
 * that gauges nobody has loaded can still be shown, marked as not up to date.
 */
public final class GaugeTracker {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("gauges");

	/**
	 * A gauge plus whether it was read live this time (false = from the cache). For live gauges,
	 * {@code panel} is Create's own panel, used for animations that change every frame (the bulb's
	 * glow after a request, the result of the last request on each arrow).
	 */
	public record Entry(Gauge gauge, boolean live, @Nullable FactoryPanelBehaviour panel) {}

	private static final Map<UUID, Map<FactoryPanelPosition, Gauge>> CACHE = new HashMap<>();
	@Nullable
	private static String cacheWorld;
	private static boolean dirty;

	private GaugeTracker() {}

	/** Reads the gauges in loaded chunks, updates the cache and returns all known gauges of the network. */
	public static List<Entry> update(UUID network, HolderLookup.Provider registries) {
		ensureLoaded(registries);
		Map<FactoryPanelPosition, Gauge> known = CACHE.computeIfAbsent(network, n -> new HashMap<>());

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null)
			return entries(known, Map.of(), Set.of());

		String dimension = level.dimension()
			.location()
			.toString();
		long now = System.currentTimeMillis();
		Set<Long> scannedChunks = new HashSet<>();
		Map<FactoryPanelPosition, FactoryPanelBehaviour> live = new HashMap<>();

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
					if (!(blockEntity instanceof FactoryPanelBlockEntity panelBlockEntity))
						continue;
					for (FactoryPanelBehaviour panel : panelBlockEntity.panels.values()) {
						if (!panel.isActive() || !network.equals(panel.network) || panel.getFilter()
							.isEmpty())
							continue;
						Gauge gauge = GaugeData.read(level, panelBlockEntity, panel, now);
						if (gauge == null)
							continue;
						if (!gauge.equals(known.get(gauge.position())))
							dirty = true;
						known.put(gauge.position(), gauge);
						live.put(gauge.position(), panel);
					}
				}
			}
		}

		// A cached gauge in a chunk we just looked at, but not found there anymore, was removed or moved.
		dirty |= known.values()
			.removeIf(gauge -> gauge.dimension()
				.equals(dimension) && scannedChunks.contains(ChunkPos.asLong(gauge.position()
				.pos())) && !live.containsKey(gauge.position()));

		// With the mod on the server too, it also reports the gauges the server has loaded.
		Set<FactoryPanelPosition> fromServer = new HashSet<>();
		List<Gauge> serverGauges = ServerLink.gauges();
		if (serverGauges != null) {
			for (Gauge gauge : serverGauges) {
				known.put(gauge.position(), gauge);
				fromServer.add(gauge.position());
			}
			dirty |= !serverGauges.isEmpty();
		}

		return entries(known, live, fromServer);
	}

	private static List<Entry> entries(Map<FactoryPanelPosition, Gauge> known,
		Map<FactoryPanelPosition, FactoryPanelBehaviour> live, Set<FactoryPanelPosition> fromServer) {
		List<Entry> result = new ArrayList<>(known.size());
		for (Gauge gauge : known.values()) {
			FactoryPanelBehaviour panel = live.get(gauge.position());
			result.add(new Entry(gauge, panel != null || fromServer.contains(gauge.position()), panel));
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------
	// Cache on disk
	// ---------------------------------------------------------------------------------------------

	private static void ensureLoaded(HolderLookup.Provider registries) {
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
			ListTag list = root.getList("Gauges", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag tag = list.getCompound(i);
				ItemStack item = ItemStack.parseOptional(registries, tag.getCompound("Item"));
				// Skip unknown items and entries from older versions without a wall position.
				if (item.isEmpty() || !tag.hasUUID("Network") || !tag.contains("Plane"))
					continue;
				FactoryPanelPosition position = new FactoryPanelPosition(BlockPos.of(tag.getLong("Pos")),
					PanelSlot.valueOf(tag.getString("Slot")));
				List<Source> sources = new ArrayList<>();
				ListTag sourceTags = tag.getList("Sources", Tag.TAG_COMPOUND);
				for (int j = 0; j < sourceTags.size(); j++) {
					CompoundTag source = sourceTags.getCompound(j);
					sources.add(new Source(new FactoryPanelPosition(BlockPos.of(source.getLong("Pos")),
						PanelSlot.valueOf(source.getString("Slot"))), source.getInt("Amount"), source.getString("Path")));
				}
				Gauge gauge = new Gauge(tag.getString("Dimension"), position, item, tag.getInt("Target"),
					tag.getBoolean("UpTo"), tag.getInt("Level"), tag.getInt("Promised"), tag.getBoolean("Satisfied"),
					tag.getBoolean("PromisedSatisfied"), tag.getBoolean("Waiting"), tag.getBoolean("Redstone"),
					tag.getString("Address"), tag.getBoolean("Restocker"), tag.getLong("LastSeen"), tag.getString("Plane"),
					tag.getInt("Column"), tag.getInt("Row"), List.copyOf(sources));
				CACHE.computeIfAbsent(tag.getUUID("Network"), n -> new HashMap<>())
					.put(position, gauge);
			}
		} catch (IOException | CommandSyntaxException | IllegalArgumentException e) {
			StockKeeperPlus.LOGGER.error("Could not read the gauge cache from {}", file, e);
		}
	}

	/** Writes the cache if something changed since the last save. */
	public static void save(HolderLookup.Provider registries) {
		if (!dirty)
			return;
		Path file = file(cacheWorld);
		if (file == null)
			return;
		ListTag list = new ListTag();
		for (Map.Entry<UUID, Map<FactoryPanelPosition, Gauge>> network : CACHE.entrySet()) {
			for (Gauge gauge : network.getValue()
				.values()) {
				CompoundTag tag = new CompoundTag();
				tag.putUUID("Network", network.getKey());
				tag.putString("Dimension", gauge.dimension());
				tag.putLong("Pos", gauge.position()
					.pos()
					.asLong());
				tag.putString("Slot", gauge.position()
					.slot()
					.name());
				tag.put("Item", gauge.item()
					.save(registries));
				tag.putInt("Target", gauge.targetCount());
				tag.putBoolean("UpTo", gauge.upTo());
				tag.putInt("Level", gauge.level());
				tag.putInt("Promised", gauge.promised());
				tag.putBoolean("Satisfied", gauge.satisfied());
				tag.putBoolean("PromisedSatisfied", gauge.promisedSatisfied());
				tag.putBoolean("Waiting", gauge.waiting());
				tag.putBoolean("Redstone", gauge.redstone());
				tag.putString("Address", gauge.address());
				tag.putBoolean("Restocker", gauge.restocker());
				tag.putLong("LastSeen", gauge.lastSeen());
				tag.putString("Plane", gauge.plane());
				tag.putInt("Column", gauge.column());
				tag.putInt("Row", gauge.row());
				ListTag sourceTags = new ListTag();
				for (Source source : gauge.sources()) {
					CompoundTag sourceTag = new CompoundTag();
					sourceTag.putLong("Pos", source.from()
						.pos()
						.asLong());
					sourceTag.putString("Slot", source.from()
						.slot()
						.name());
					sourceTag.putInt("Amount", source.amount());
					sourceTag.putString("Path", source.path());
					sourceTags.add(sourceTag);
				}
				tag.put("Sources", sourceTags);
				list.add(tag);
			}
		}
		CompoundTag root = new CompoundTag();
		root.put("Gauges", list);
		try {
			Files.createDirectories(DIR);
			Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException e) {
			StockKeeperPlus.LOGGER.error("Could not save the gauge cache to {}", file, e);
		}
	}

	@Nullable
	private static Path file(@Nullable String world) {
		return world == null ? null : DIR.resolve(world + ".snbt");
	}
}
