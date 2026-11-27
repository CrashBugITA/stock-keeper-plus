package com.stockkeeperplus.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Display data of Factory Gauges, shared by the client (reading loaded gauges) and the optional
 * server part (reading every gauge the server has loaded).
 */
public final class GaugeData {
	private GaugeData() {}

	/**
	 * A gauge feeding into another one (an arrow in the world), with the amount used per craft.
	 * {@code path} is the route Create draws for the arrow (FactoryPanelConnection.getPath): one
	 * letter per half-block step (N, S, W, E in the panel's flat frame), starting next to the
	 * receiving gauge and ending on the source.
	 */
	public record Source(FactoryPanelPosition from, int amount, String path) {
		static final StreamCodec<RegistryFriendlyByteBuf, Source> STREAM_CODEC = StreamCodec.of((buf, s) -> {
			writePosition(buf, s.from);
			buf.writeVarInt(s.amount);
			buf.writeUtf(s.path);
		}, buf -> new Source(readPosition(buf), buf.readVarInt(), buf.readUtf()));
	}

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

		public static final StreamCodec<RegistryFriendlyByteBuf, Gauge> STREAM_CODEC = StreamCodec.of((buf, g) -> {
			buf.writeUtf(g.dimension);
			writePosition(buf, g.position);
			ItemStack.STREAM_CODEC.encode(buf, g.item);
			buf.writeVarInt(g.targetCount);
			buf.writeBoolean(g.upTo);
			buf.writeVarInt(g.level);
			buf.writeVarInt(g.promised);
			buf.writeBoolean(g.satisfied);
			buf.writeBoolean(g.promisedSatisfied);
			buf.writeBoolean(g.waiting);
			buf.writeBoolean(g.redstone);
			buf.writeUtf(g.address);
			buf.writeBoolean(g.restocker);
			buf.writeUtf(g.plane);
			buf.writeVarInt(g.column);
			buf.writeVarInt(g.row);
			Source.STREAM_CODEC.apply(ByteBufCodecs.list())
				.encode(buf, g.sources);
		}, buf -> new Gauge(buf.readUtf(), readPosition(buf), ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt(),
			buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
			buf.readBoolean(), buf.readUtf(), buf.readBoolean(), System.currentTimeMillis(), buf.readUtf(), buf.readVarInt(),
			buf.readVarInt(), Source.STREAM_CODEC.apply(ByteBufCodecs.list())
				.decode(buf)));

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

	/** Reads one panel of a gauge block, or null if the block is not a gauge anymore. */
	@Nullable
	public static Gauge read(Level level, FactoryPanelBlockEntity blockEntity, FactoryPanelBehaviour panel, long now) {
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

		String dimension = level.dimension()
			.location()
			.toString();
		return new Gauge(dimension, position, panel.getFilter()
			.copyWithCount(1), panel.count, panel.upTo, panel.getLevelInStorage(), panel.getPromised(), panel.satisfied,
			panel.promisedSatisfied, panel.waitingForNetwork, panel.redstonePowered,
			Objects.requireNonNullElse(panel.recipeAddress, ""), blockEntity.restocker, now, plane, column, row,
			List.copyOf(sources));
	}

	static void writePosition(RegistryFriendlyByteBuf buf, FactoryPanelPosition position) {
		buf.writeBlockPos(position.pos());
		buf.writeEnum(position.slot());
	}

	static FactoryPanelPosition readPosition(RegistryFriendlyByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		return new FactoryPanelPosition(pos, buf.readEnum(PanelSlot.class));
	}
}
