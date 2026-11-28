package com.stockkeeperplus.common;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.stockkeeperplus.mixin.ItemVaultBlockEntityAccessor;

import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * One container of a logistics network: the inventory a Packager of the network reads from.
 * Shared by the client (sizes of loaded containers) and the optional server part (also how full
 * each container is).
 *
 * @param usedSlots slots holding something, or -1 when unknown (the client cannot see contents)
 */
public record ContainerData(String dimension, BlockPos pos, ResourceLocation block, int slots, long itemCapacity,
	int usedSlots, long lastSeen) {

	/** Slot limits above this (drawers, bulk storage) still count as one stack per slot. */
	private static final int STACK = 64;

	public static final StreamCodec<RegistryFriendlyByteBuf, ContainerData> STREAM_CODEC = StreamCodec.of((buf, c) -> {
		buf.writeUtf(c.dimension);
		buf.writeBlockPos(c.pos);
		buf.writeResourceLocation(c.block);
		buf.writeVarInt(c.slots);
		buf.writeVarLong(c.itemCapacity);
		buf.writeVarInt(c.usedSlots + 1);
	}, buf -> new ContainerData(buf.readUtf(), buf.readBlockPos(), buf.readResourceLocation(), buf.readVarInt(),
		buf.readVarLong(), buf.readVarInt() - 1, System.currentTimeMillis()));

	public boolean fillKnown() {
		return usedSlots >= 0;
	}

	public ItemStack icon() {
		return new ItemStack(BuiltInRegistries.ITEM.get(block));
	}

	/** Key used to remember things about this container (groups), stable across sessions. */
	public String key() {
		return key(dimension, pos);
	}

	public static String key(String dimension, BlockPos pos) {
		return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** The container a packager reads from, as seen from that container's side. */
	@Nullable
	public static BlockFace targetOf(@Nullable PackagerBlockEntity packager) {
		if (packager == null || packager.targetInventory == null)
			return null;
		return packager.targetInventory.getTarget()
			.getOpposite();
	}

	@Nullable
	public static IItemHandler handlerAt(Level level, BlockFace face) {
		if (!level.isLoaded(face.getPos()))
			return null;
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, face.getPos(), face.getFace());
		return handler == null || handler.getSlots() == 0 ? null : handler;
	}

	/**
	 * Reads the container behind a packager. {@code withFill} also counts the slots in use; only the
	 * server can do that, since clients do not receive container contents.
	 */
	@Nullable
	public static ContainerData read(Level level, @Nullable PackagerBlockEntity packager, boolean withFill, long now) {
		BlockFace face = targetOf(packager);
		if (face == null)
			return null;
		IItemHandler handler = handlerAt(level, face);
		if (handler == null)
			return null;

		BlockPos pos = canonicalPos(level, face.getPos());
		long capacity = 0;
		int used = withFill ? 0 : -1;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			capacity += Math.min(STACK, handler.getSlotLimit(slot));
			if (withFill && !handler.getStackInSlot(slot)
				.isEmpty())
				used++;
		}
		int slots = handler.getSlots();
		// A vault builds its inventory once, from the blocks loaded at that moment: when part of it
		// was in an unloaded chunk, the inventory comes out too small. Size it from its shape instead.
		if (level.getBlockEntity(face.getPos()) instanceof ItemVaultBlockEntity part
			&& part.getControllerBE() instanceof ItemVaultBlockEntity controller) {
			ItemStackHandler inventory = ((ItemVaultBlockEntityAccessor) controller).stockkeeperplus$getInventory();
			if (inventory != null && inventory.getSlots() > 0) {
				slots = controller.getWidth() * controller.getWidth() * controller.getHeight() * inventory.getSlots();
				capacity = (long) slots * Math.min(STACK, inventory.getSlotLimit(0));
			}
		}

		ResourceLocation block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos)
			.getBlock());
		return new ContainerData(level.dimension()
			.location()
			.toString(), pos.immutable(), block, slots, capacity, used, now);
	}

	/** A vault is one container however many of its blocks have a packager: use its controller. */
	public static BlockPos canonicalPos(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof ItemVaultBlockEntity vault && vault.getController() != null)
			return vault.getController();
		return pos;
	}
}
