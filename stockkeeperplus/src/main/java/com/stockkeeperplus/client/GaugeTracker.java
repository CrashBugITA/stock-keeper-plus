package com.stockkeeperplus.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.stockkeeperplus.StockKeeperPlus;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Finds the Factory Gauges of a logistics network, client-side only.
 * <p>
 * The server sends the full state of every gauge (item, target, level in storage, promises,
 * status, address, network) to clients, but only for chunks the client has loaded. So gauges near
 * the player are read live from the loaded block entities, and every gauge ever seen is kept in a
 * cache with the time it was last seen (stored per world in config/stockkeeperplus/gauges/), so
 * that far away gauges can still be shown, marked as not up to date.
 */
public final class GaugeTracker {
	private static final Path DIR = FMLPaths.CONFIGDIR.get()
		.resolve(StockKeeperPlus.MOD_ID)
		.resolve("gauges");

	/**
	 * A gauge feeding into another one (an arrow in the world), with the amount used per craft.
	 * {@code path} is the route Create draws for the arrow (FactoryPanelConnection.getPath): one
	 * letter per half-block step (N, S, W, E in the panel's flat frame), starting next to the
	 * receiving gauge and ending on the source.
	 */
	public record Source(FactoryPanelPosition from, int amount, String path) {}

	/**
	 * The display state of one gauge (one of the up to 4 panels on a gauge block).
	 * <p>
	 * {@code plane}, {@code column} and {@code row} place the panel on its wall: gauges with the same
	 * plane are on the same flat surface facing the same way; column and row count half blocks (one
	 * panel) to the right and down, as seen when looking at the wall.
	 */
	public record Gauge(String dimension, FactoryPanelPosition position, ItemStack item, int targetCount,
		boolean upTo, int level, int promised, boolean satisfied, boolean promisedSatisfied, boolean waiting,
		boolean redstone, String address, boolean restocker, long lastSeen, String plane, int column, int row,
		List<Source> sources) {

		public int stackSize() {
			return Math.max(1, item.getMaxStackSize());
		}

		/** Target in items (Create stores it in stacks unless the gauge counts single items). */
		public int targetItems() {
			return upTo ? targetCount : targetCount * stackSize();
		}

		/** Items still needed after counting what is in storage and on the way. */
		public int missingItems() {
			return Math.max(0, targetItems() - level - promised);
		}

		public Status status() {
			if (waiting)
				return Status.WAITING;
			if (redstone)
				return Status.PAUSED;
			if (targetCount == 0)
				return Status.NO_TARGET;
			if (satisfied)
				return Status.SATISFIED;
			if (promisedSatisfied)
				return Status.INCOMING;
			return Status.MISSING;
		}

		/** Color of the arrows into this gauge, as Create draws them (getIngredientStatusColor). */
		public int arrowColor() {
			boolean missingAddress = address.isBlank() && !sources.isEmpty();
			if (targetCount == 0 || missingAddress || redstone)
				return 0x888898;
			if (waiting)
				return 0x5B3B3B;
			if (satisfied)
				return 0x9EFF7F;
			if (promisedSatisfied)
				return 0x22AFAF;
			return 0x3D6EBD;
		}
	}

	/** In the order the Gauge tab lists them: what needs attention first. */
	public enum Status {
		MISSING, INCOMING, WAITING, PAUSED, SATISFIED, NO_TARGET
	}

	/** A gauge plus whether it was read live this time (false = from the cache). */
	public record Entry(Gauge gauge, boolean live) {}

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
			return entries(known, Set.of());

		String dimension = level.dimension()
			.location()
			.toString();
		long now = System.currentTimeMillis();
		Set<Long> scannedChunks = new HashSet<>();
		Set<FactoryPanelPosition> live = new HashSet<>();

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
						Gauge gauge = read(level, dimension, panelBlockEntity, panel, now);
						if (gauge == null)
							continue;
						if (!gauge.equals(known.get(gauge.position())))
							dirty = true;
						known.put(gauge.position(), gauge);
						live.add(gauge.position());
					}
				}
			}
		}

		// A cached gauge in a chunk we just looked at, but not found there anymore, was removed or moved.
		dirty |= known.values()
			.removeIf(gauge -> gauge.dimension()
				.equals(dimension) && scannedChunks.contains(ChunkPos.asLong(gauge.position()
				.pos())) && !live.contains(gauge.position()));

		return entries(known, live);
	}

	private static List<Entry> entries(Map<FactoryPanelPosition, Gauge> known, Set<FactoryPanelPosition> live) {
		List<Entry> result = new ArrayList<>(known.size());
		for (Gauge gauge : known.values())
			result.add(new Entry(gauge, live.contains(gauge.position())));
		return result;
	}

	@Nullable
	private static Gauge read(ClientLevel level, String dimension, FactoryPanelBlockEntity blockEntity,
		FactoryPanelBehaviour panel, long now) {
		FactoryPanelPosition position = panel.getPanelPosition();
		BlockState state = level.getBlockState(position.pos());
		if (!(state.getBlock() instanceof FactoryPanelBlock))
			return null;

		// Same transformation Create uses for the arrows between gauges
		// (FactoryPanelConnection.calculatePathDiff): it brings the wall into a flat x/z frame where
		// +x points to a panel's left and +z to its top.
		float xRot = Mth.RAD_TO_DEG * FactoryPanelBlock.getXRot(state);
		float yRot = Mth.RAD_TO_DEG * FactoryPanelBlock.getYRot(state);
		Vec3 local = Vec3.atLowerCornerOf(position.pos());
		local = VecHelper.rotate(local, -yRot, Axis.Y);
		local = VecHelper.rotate(local, -xRot - 90, Axis.X);
		local = VecHelper.rotate(local, -180, Axis.Y);
		int column = -(int) Math.round(local.x * 2 + position.slot().xOffset);
		int row = -(int) Math.round(local.z * 2 + position.slot().yOffset);
		String plane = state.getValue(FactoryPanelBlock.FACE)
			.getSerializedName() + "/" + state.getValue(FactoryPanelBlock.FACING)
			.getSerializedName() + "/" + Math.round(local.y);

		List<Source> sources = new ArrayList<>();
		for (FactoryPanelConnection connection : panel.targetedBy.values()) {
			StringBuilder path = new StringBuilder();
			for (Direction step : connection.getPath(level, state, position))
				path.append(step.getName()
					.toUpperCase(Locale.ROOT)
					.charAt(0));
			sources.add(new Source(connection.from, connection.amount, path.toString()));
		}

		return new Gauge(dimension, position, panel.getFilter()
			.copyWithCount(1), panel.count, panel.upTo, panel.getLevelInStorage(), panel.getPromised(), panel.satisfied,
			panel.promisedSatisfied, panel.waitingForNetwork, panel.redstonePowered,
			Objects.requireNonNullElse(panel.recipeAddress, ""), blockEntity.restocker, now, plane, column, row,
			List.copyOf(sources));
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
