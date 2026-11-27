package com.stockkeeperplus.client;

import java.util.Locale;

import com.simibubi.create.content.logistics.BigItemStack;

/**
 * Number formatting helpers for item counts.
 */
public final class CountFormat {
	/** The stack symbol Create uses on clipboards and checklists. */
	public static final String STACK_SYMBOL = "▤";
	public static final String INFINITE = "∞";

	private CountFormat() {}

	public static boolean isInfinite(long count) {
		return count >= BigItemStack.INF;
	}

	/** {@code 12345} becomes {@code "12,345"}. */
	public static String exact(long count) {
		return String.format(Locale.ROOT, "%,d", count);
	}

	/**
	 * Exact count followed by the stack breakdown, in the same style as Create's checklists:
	 * {@code "12,345 | 192▤ +57"}. Items that do not stack only show the exact count,
	 * and a zero remainder is left out.
	 */
	public static String exactWithStacks(long count, int maxStackSize) {
		if (isInfinite(count))
			return INFINITE;
		String text = exact(count);
		if (maxStackSize <= 1 || count < maxStackSize)
			return text;
		long stacks = count / maxStackSize;
		long remainder = count % maxStackSize;
		text += " | " + exact(stacks) + STACK_SYMBOL;
		if (remainder > 0)
			text += " +" + remainder;
		return text;
	}

	/** Short form for tight spaces: 950, 1.2k, 12k, 1.2M, 12M, 1.2B. */
	public static String compact(long count) {
		if (count < 1_000)
			return Long.toString(count);
		if (count < 10_000)
			return oneDecimal(count / 1_000d) + "k";
		if (count < 1_000_000)
			return (count / 1_000) + "k";
		if (count < 10_000_000)
			return oneDecimal(count / 1_000_000d) + "M";
		if (count < 1_000_000_000)
			return (count / 1_000_000) + "M";
		return oneDecimal(count / 1_000_000_000d) + "B";
	}

	private static String oneDecimal(double value) {
		// Truncate rather than round so that 9,999 never shows as "10.0k".
		return String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10);
	}
}
