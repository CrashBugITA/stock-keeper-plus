package com.stockkeeperplus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;

import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Exposes the inventory of one vault block, to size a whole vault from its shape.
 */
@Mixin(ItemVaultBlockEntity.class)
public interface ItemVaultBlockEntityAccessor {
	@Accessor("inventory")
	ItemStackHandler stockkeeperplus$getInventory();
}
