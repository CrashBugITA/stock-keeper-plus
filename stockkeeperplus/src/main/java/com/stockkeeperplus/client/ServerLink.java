package com.stockkeeperplus.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.stockkeeperplus.common.ContainerData;
import com.stockkeeperplus.common.GaugeData.Gauge;
import com.stockkeeperplus.network.Payloads.ContentsRequest;
import com.stockkeeperplus.network.Payloads.ContentsResponse;
import com.stockkeeperplus.network.Payloads.GaugesRequest;
import com.stockkeeperplus.network.Payloads.GaugesResponse;
import com.stockkeeperplus.network.Payloads.ItemCount;
import com.stockkeeperplus.network.Payloads.StorageRequest;
import com.stockkeeperplus.network.Payloads.StorageResponse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.GlobalPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the optional server data. Everything here is a no-op when the server does not
 * have the mod: the views then keep using what the client can read itself.
 * <p>
 * Requests are only sent while the matching view is open, and at most once per interval.
 */
public final class ServerLink {
	private static final long GAUGES_INTERVAL_MS = 1000;
	private static final long STORAGE_INTERVAL_MS = 2000;
	private static final long CONTENTS_MAX_AGE_MS = 3000;
	/** Ask again if no answer came within this time (the server skips requests sent too close together). */
	private static final long CONTENTS_RETRY_MS = 1000;
	/** Data older than this is not used anymore (the view was probably closed meanwhile). */
	private static final long FRESH_MS = 6000;

	public record Contents(List<ItemCount> items, long time) {}

	private static List<Gauge> gauges = List.of();
	private static long gaugesTime;
	private static long gaugesAsked;

	private static List<ContainerData> containers = List.of();
	private static long containersTime;
	private static long containersAsked;

	private static final Map<List<GlobalPos>, Contents> CONTENTS = new HashMap<>();
	/** Requests sent and not answered yet, with when they were sent. */
	private static final Map<List<GlobalPos>, Long> PENDING = new HashMap<>();

	@Nullable
	private static ClientPacketListener lastConnection;

	private ServerLink() {}

	/** True when the server has the mod and will answer. */
	public static boolean available() {
		ClientPacketListener connection = Minecraft.getInstance()
			.getConnection();
		if (connection != lastConnection) {
			clear(); // new world or server
			lastConnection = connection;
		}
		return connection != null && connection.hasChannel(GaugesRequest.TYPE);
	}

	private static void clear() {
		gauges = List.of();
		containers = List.of();
		gaugesTime = containersTime = gaugesAsked = containersAsked = 0;
		CONTENTS.clear();
		PENDING.clear();
	}

	// --- Gauges ---------------------------------------------------------------------------------

	/** Asks for the network's gauges if the last answer is getting old. */
	public static void pollGauges() {
		long now = System.currentTimeMillis();
		if (!available() || now - gaugesAsked < GAUGES_INTERVAL_MS)
			return;
		gaugesAsked = now;
		PacketDistributor.sendToServer(new GaugesRequest());
	}

	/** Gauges from the server, or null if there is no recent answer. */
	@Nullable
	public static List<Gauge> gauges() {
		return System.currentTimeMillis() - gaugesTime < FRESH_MS ? gauges : null;
	}

	public static void receive(GaugesResponse response) {
		gauges = List.copyOf(response.gauges());
		gaugesTime = System.currentTimeMillis();
	}

	// --- Containers -----------------------------------------------------------------------------

	public static void pollStorage() {
		long now = System.currentTimeMillis();
		if (!available() || now - containersAsked < STORAGE_INTERVAL_MS)
			return;
		containersAsked = now;
		PacketDistributor.sendToServer(new StorageRequest());
	}

	@Nullable
	public static List<ContainerData> containers() {
		return System.currentTimeMillis() - containersTime < FRESH_MS ? containers : null;
	}

	public static void receive(StorageResponse response) {
		containers = List.copyOf(response.containers());
		containersTime = System.currentTimeMillis();
	}

	// --- Contents of hovered containers ---------------------------------------------------------

	/**
	 * What the given containers hold, asking the server if needed. Returns the last answer (maybe
	 * a few seconds old) or null while waiting for the first one.
	 */
	@Nullable
	public static Contents contents(List<GlobalPos> positions) {
		if (!available() || positions.isEmpty())
			return null;
		Contents known = CONTENTS.get(positions);
		long now = System.currentTimeMillis();
		Long sent = PENDING.get(positions);
		boolean waiting = sent != null && now - sent < CONTENTS_RETRY_MS;
		if ((known == null || now - known.time() > CONTENTS_MAX_AGE_MS) && !waiting) {
			PENDING.put(List.copyOf(positions), now);
			PacketDistributor.sendToServer(new ContentsRequest(List.copyOf(positions)));
		}
		return known;
	}

	public static void receive(ContentsResponse response) {
		PENDING.remove(response.containers());
		CONTENTS.put(List.copyOf(response.containers()), new Contents(List.copyOf(response.items()),
			System.currentTimeMillis()));
		// Keep the map small: drop answers nobody looked at for a while.
		CONTENTS.values()
			.removeIf(c -> System.currentTimeMillis() - c.time() > 60_000);
	}

	static boolean sameWorld(@Nullable String a, @Nullable String b) {
		return Objects.equals(a, b);
	}
}
