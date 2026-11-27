package com.stockkeeperplus.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Every Factory Gauge panel the server has loaded, so the gauges of a network can be listed
 * without scanning chunks. Panels add themselves when Create initialises them (see
 * FactoryPanelBehaviourMixin); they are held weakly and skipped once unloaded or removed.
 */
public final class GaugeRegistry {
	private static final Set<FactoryPanelBehaviour> PANELS = Collections.newSetFromMap(new WeakHashMap<>());

	private GaugeRegistry() {}

	public static void register(FactoryPanelBehaviour panel) {
		Level level = panel.getWorld();
		if (level == null || level.isClientSide)
			return;
		synchronized (PANELS) {
			PANELS.add(panel);
		}
	}

	/** Panels that are still in the world. */
	public static List<FactoryPanelBehaviour> loadedPanels() {
		List<FactoryPanelBehaviour> result = new ArrayList<>();
		synchronized (PANELS) {
			PANELS.removeIf(panel -> !isLoaded(panel));
			result.addAll(PANELS);
		}
		return result;
	}

	private static boolean isLoaded(FactoryPanelBehaviour panel) {
		BlockEntity blockEntity = panel.blockEntity;
		Level level = blockEntity.getLevel();
		return level != null && !blockEntity.isRemoved() && level.isLoaded(blockEntity.getBlockPos())
			&& level.getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
	}
}
