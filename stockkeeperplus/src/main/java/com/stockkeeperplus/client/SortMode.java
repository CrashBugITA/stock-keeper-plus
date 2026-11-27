package com.stockkeeperplus.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.simibubi.create.content.logistics.BigItemStack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Sort orders for the items inside each Stock Keeper category.
 * Every mode ends with a tie-breaker on the item id, so the order stays stable while the stock
 * refreshes (Create's own order can shuffle items that have the same count).
 */
public enum SortMode {
	/** Create's default: most items first. */
	QUANTITY_DESC("quantity_desc"),
	QUANTITY_ASC("quantity_asc"),
	NAME("name"),
	MOD("mod");

	private final String id;

	/** The icon of each mode is at u = ordinal * 16 in textures/gui/buttons.png. */
	SortMode(String id) {
		this.id = id;
	}

	public String translationKey() {
		return "stockkeeperplus.sort." + id;
	}

	public SortMode next() {
		SortMode[] values = values();
		return values[(ordinal() + 1) % values.length];
	}

	public SortMode previous() {
		SortMode[] values = values();
		return values[(ordinal() + values.length - 1) % values.length];
	}

	/** Returns a sorted copy; the input list is left untouched. */
	public List<BigItemStack> sorted(List<BigItemStack> items) {
		// Compute the sort keys once per item instead of once per comparison.
		List<Keyed> keyed = new ArrayList<>(items.size());
		for (BigItemStack entry : items)
			keyed.add(new Keyed(entry));
		keyed.sort(comparator());
		List<BigItemStack> result = new ArrayList<>(keyed.size());
		for (Keyed k : keyed)
			result.add(k.entry);
		return result;
	}

	private Comparator<Keyed> comparator() {
		Comparator<Keyed> byCountDesc = Comparator.comparingInt((Keyed k) -> k.entry.count)
			.reversed();
		Comparator<Keyed> byName = Comparator.comparing((Keyed k) -> k.name);
		Comparator<Keyed> byId = Comparator.comparing((Keyed k) -> k.id);
		return switch (this) {
			case QUANTITY_DESC -> byCountDesc.thenComparing(byName)
				.thenComparing(byId);
			case QUANTITY_ASC -> Comparator.comparingInt((Keyed k) -> k.entry.count)
				.thenComparing(byName)
				.thenComparing(byId);
			case NAME -> byName.thenComparing(byId);
			case MOD -> Comparator.comparing((Keyed k) -> k.namespace)
				.thenComparing(byName)
				.thenComparing(byId);
		};
	}

	private static final class Keyed {
		final BigItemStack entry;
		final String name;
		final String namespace;
		final String id;

		Keyed(BigItemStack entry) {
			this.entry = entry;
			this.name = entry.stack.getHoverName()
				.getString()
				.toLowerCase(Locale.ROOT);
			ResourceLocation key = BuiltInRegistries.ITEM.getKey(entry.stack.getItem());
			this.namespace = key.getNamespace();
			this.id = key.toString();
		}
	}
}
