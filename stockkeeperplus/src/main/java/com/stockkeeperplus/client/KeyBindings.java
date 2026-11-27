package com.stockkeeperplus.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

/**
 * Stock Keeper shortcuts. They only work inside screens (GUI conflict context), so they never clash
 * with in-world controls, and they can be rebound in Options → Controls.
 */
public final class KeyBindings {
	public static final String CATEGORY = "key.categories.stockkeeperplus";

	public static final KeyMapping FOCUS_SEARCH = create("focus_search", KeyModifier.CONTROL, GLFW.GLFW_KEY_F);
	public static final KeyMapping CLEAR_CART = create("clear_cart", KeyModifier.CONTROL, GLFW.GLFW_KEY_DELETE);

	private KeyBindings() {}

	private static KeyMapping create(String name, KeyModifier modifier, int key) {
		return new KeyMapping("key.stockkeeperplus." + name, KeyConflictContext.GUI, modifier,
			InputConstants.Type.KEYSYM, key, CATEGORY);
	}

	public static void register(RegisterKeyMappingsEvent event) {
		event.register(FOCUS_SEARCH);
		event.register(CLEAR_CART);
	}

	/** True if the key press matches the mapping, including its modifier (Ctrl, Shift, Alt). */
	public static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
		return mapping.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
	}
}
