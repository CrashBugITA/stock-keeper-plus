package com.stockkeeperplus.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.AllMenuTypes;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperCategoryMenu;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.common.ContainerData;
import com.stockkeeperplus.common.GaugeData;
import com.stockkeeperplus.network.Payloads.ContentsRequest;
import com.stockkeeperplus.network.Payloads.ContentsResponse;
import com.stockkeeperplus.network.Payloads.GaugesRequest;
import com.stockkeeperplus.network.Payloads.GaugesResponse;
import com.stockkeeperplus.network.Payloads.ItemCount;
import com.stockkeeperplus.network.Payloads.StorageRequest;
import com.stockkeeperplus.network.Payloads.StorageResponse;

import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server side of the optional data: answers the client's requests, on the server thread.
 * <p>
 * Kept light on purpose: nothing runs unless a player with the mod has a Stock Keeper open and is
 * looking at the matching view; each player can ask at most once per interval; gauges come from
 * GaugeRegistry (no chunk scanning) and containers from Create's own list of loaded links. Only
 * networks the player has open in a Stock Keeper (or Remote Terminal) can be queried.
 */
public final class ServerQueries {
	private static final int GAUGES_INTERVAL = 10; // ticks
	private static final int STORAGE_INTERVAL = 20;
	private static final int CONTENTS_INTERVAL = 2;
	private static final int MAX_GAUGES = 4096;
	private static final int MAX_CONTAINERS = 2048;
	private static final int MAX_REQUESTED_CONTAINERS = 256;
	private static final int MAX_CONTENT_ENTRIES = 512;

	/** Last request time per player and kind: [gauges, storage, contents]. */
	private static final Map<ServerPlayer, long[]> LAST_REQUEST = new WeakHashMap<>();

	private ServerQueries() {}

	public static void onGauges(GaugesRequest request, IPayloadContext context) {
		context.enqueueWork(() -> safely("onGauges", () -> {
			if (!(context.player() instanceof ServerPlayer player) || !allowed(player, 0, GAUGES_INTERVAL))
				return;
			UUID network = openNetwork(player);
			if (network == null)
				return;
			long now = System.currentTimeMillis();
			List<GaugeData.Gauge> gauges = new ArrayList<>();
			for (FactoryPanelBehaviour panel : GaugeRegistry.loadedPanels()) {
				if (gauges.size() >= MAX_GAUGES)
					break;
				if (!panel.isActive() || !network.equals(panel.network) || panel.getFilter()
					.isEmpty() || !(panel.blockEntity instanceof FactoryPanelBlockEntity blockEntity))
					continue;
				GaugeData.Gauge gauge = GaugeData.read(blockEntity.getLevel(), blockEntity, panel, now);
				if (gauge != null)
					gauges.add(gauge);
			}
			context.reply(new GaugesResponse(gauges));
		}));
	}

	public static void onStorage(StorageRequest request, IPayloadContext context) {
		context.enqueueWork(() -> safely("onStorage", () -> {
			if (!(context.player() instanceof ServerPlayer player) || !allowed(player, 1, STORAGE_INTERVAL))
				return;
			UUID network = openNetwork(player);
			if (network == null)
				return;
			long now = System.currentTimeMillis();
			Map<String, ContainerData> containers = new LinkedHashMap<>();
			for (PackagerLinkBlockEntity link : links(network)) {
				if (containers.size() >= MAX_CONTAINERS)
					break;
				ContainerData container = ContainerData.read(link.getLevel(), link.getPackager(), true, now);
				if (container != null)
					containers.putIfAbsent(container.key(), container);
			}
			context.reply(new StorageResponse(new ArrayList<>(containers.values())));
		}));
	}

	public static void onContents(ContentsRequest request, IPayloadContext context) {
		context.enqueueWork(() -> safely("onContents", () -> {
			if (!(context.player() instanceof ServerPlayer player) || !allowed(player, 2, CONTENTS_INTERVAL))
				return;
			UUID network = openNetwork(player);
			if (network == null || request.containers()
				.size() > MAX_REQUESTED_CONTAINERS)
				return;

			// Only containers of this network can be read: map their keys to where they are.
			Map<String, Level> levels = new HashMap<>();
			Map<String, BlockFace> faces = new HashMap<>();
			for (PackagerLinkBlockEntity link : links(network)) {
				Level level = link.getLevel();
				BlockFace face = ContainerData.targetOf(link.getPackager());
				if (level == null || face == null)
					continue;
				String key = ContainerData.key(level.dimension()
					.location()
					.toString(), ContainerData.canonicalPos(level, face.getPos()));
				faces.putIfAbsent(key, face);
				levels.putIfAbsent(key, level);
			}

			InventorySummary summary = new InventorySummary();
			for (GlobalPos requested : request.containers()) {
				String key = ContainerData.key(requested.dimension()
					.location()
					.toString(), requested.pos());
				BlockFace face = faces.get(key);
				if (face == null)
					continue;
				IItemHandler handler = ContainerData.handlerAt(levels.get(key), face);
				if (handler == null)
					continue;
				for (int slot = 0; slot < handler.getSlots(); slot++)
					summary.add(handler.getStackInSlot(slot));
			}

			List<ItemCount> items = new ArrayList<>();
			for (BigItemStack stack : summary.getStacksByCount()) {
				if (items.size() >= MAX_CONTENT_ENTRIES)
					break;
				items.add(new ItemCount(stack.stack.copyWithCount(1), stack.count));
			}
			context.reply(new ContentsResponse(request.containers(), items));
		}));
	}

	/** Runs a request handler, logging failures (the work queue would swallow them silently). */
	private static void safely(String what, Runnable handler) {
		try {
			handler.run();
		} catch (RuntimeException e) {
			StockKeeperPlus.LOGGER.error("Stock Keeper Plus: {} request failed", what, e);
		}
	}

	/** Packager Links of the network that the server has loaded (Create's own list). */
	private static List<PackagerLinkBlockEntity> links(UUID network) {
		List<PackagerLinkBlockEntity> result = new ArrayList<>();
		for (LogisticallyLinkedBehaviour behaviour : LogisticallyLinkedBehaviour.getAllPresent(network, false))
			if (behaviour.blockEntity instanceof PackagerLinkBlockEntity link && link.getLevel() != null)
				result.add(link);
		return result;
	}

	/**
	 * Network of the Stock Keeper (or Remote Terminal) the player has open, or null.
	 * <p>
	 * Checked by menu type, not class: on the server Create builds the request menu as a
	 * StockKeeperCategoryMenu with the request menu's type (StockKeeperRequestMenu.create), while
	 * Create: Remote Terminal uses a StockKeeperRequestMenu subclass.
	 */
	@Nullable
	private static UUID openNetwork(ServerPlayer player) {
		AbstractContainerMenu open = player.containerMenu;
		StockTickerBlockEntity ticker = null;
		if (open instanceof StockKeeperRequestMenu menu)
			ticker = menu.contentHolder;
		else if (open instanceof StockKeeperCategoryMenu menu && isRequestMenu(menu))
			ticker = menu.contentHolder;
		if (ticker == null || ticker.isRemoved() || ticker.behaviour == null)
			return null;
		return ticker.behaviour.freqId;
	}

	private static boolean isRequestMenu(AbstractContainerMenu menu) {
		try {
			return menu.getType() == AllMenuTypes.STOCK_KEEPER_REQUEST.get();
		} catch (UnsupportedOperationException e) {
			return false; // menus without a registered type
		}
	}

	private static boolean allowed(ServerPlayer player, int kind, int interval) {
		long now = player.serverLevel()
			.getGameTime();
		long[] last = LAST_REQUEST.computeIfAbsent(player, p -> new long[] { Long.MIN_VALUE / 2, Long.MIN_VALUE / 2,
			Long.MIN_VALUE / 2 });
		if (now - last[kind] < interval)
			return false;
		last[kind] = now;
		return true;
	}
}
