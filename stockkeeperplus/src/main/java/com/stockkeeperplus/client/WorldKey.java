package com.stockkeeperplus.client;

import java.nio.file.Path;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A file-name-safe name for the current single-player world or multiplayer server, used to keep
 * per-world data (carts, gauge cache) apart.
 */
public final class WorldKey {
	private WorldKey() {}

	/** For example "world_new_world" or "server_play.example.com_25565"; null when not in a world. */
	@Nullable
	public static String current() {
		Minecraft mc = Minecraft.getInstance();
		String name;
		IntegratedServer singleplayer = mc.getSingleplayerServer();
		if (singleplayer != null) {
			Path worldDir = singleplayer.getWorldPath(LevelResource.ROOT)
				.toAbsolutePath()
				.normalize();
			name = "world_" + worldDir.getFileName();
		} else {
			ServerData server = mc.getCurrentServer();
			if (server == null)
				return null;
			name = "server_" + server.ip;
		}
		return name.replaceAll("[^A-Za-z0-9._-]", "_")
			.toLowerCase(Locale.ROOT);
	}
}
