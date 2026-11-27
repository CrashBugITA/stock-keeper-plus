package com.stockkeeperplus.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A fill bar drawn as a groove cut into Create's wood: dark top edge and sides, a light bottom
 * edge (like the highlight rows of Create's panels), filled in the colors Create uses on gauges.
 * Everything is whole-pixel rectangles, so it looks the same at any GUI scale.
 */
final class FillBar {
	private static final int EDGE_DARK = 0xFF4A2D31; // Create's darkest panel color
	private static final int EMPTY = 0xFF603D39;
	private static final int EDGE_LIGHT = 0xFFB59370; // highlight row of Create's shelf

	// Create's gauge colors: satisfied, promised (warning), missing (full)
	private static final int COLOR_OK = 0xFFD7FFA8;
	private static final int COLOR_HIGH = 0xFFFFCD75;
	private static final int COLOR_FULL = 0xFFFFBFA8;

	private FillBar() {}

	/** Color for a fill fraction: green below 75 %, yellow below 90 %, red above. */
	static int colorFor(double fraction) {
		if (fraction >= 0.9)
			return COLOR_FULL;
		if (fraction >= 0.75)
			return COLOR_HIGH;
		return COLOR_OK;
	}

	/** Draws the bar in the box (x, y, width, height); height should be at least 4. */
	static void render(GuiGraphics graphics, int x, int y, int width, int height, double fraction) {
		int innerX = x + 1;
		int innerY = y + 1;
		int innerWidth = width - 2;
		int innerHeight = height - 2;

		graphics.fill(x, y, x + width, y + 1, EDGE_DARK);
		graphics.fill(x, y + 1, x + 1, y + height - 1, EDGE_DARK);
		graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, EDGE_DARK);
		graphics.fill(x, y + height - 1, x + width, y + height, EDGE_LIGHT);
		graphics.fill(innerX, innerY, innerX + innerWidth, innerY + innerHeight, EMPTY);

		int filled = (int) Math.round(Math.min(1, Math.max(0, fraction)) * innerWidth);
		if (filled <= 0)
			return;
		int color = colorFor(fraction);
		graphics.fill(innerX, innerY, innerX + filled, innerY + innerHeight, color);
		// Slightly darker last row for some depth, like Create's two-tone bars.
		if (innerHeight > 1)
			graphics.fill(innerX, innerY + innerHeight - 1, innerX + filled, innerY + innerHeight, darker(color));
	}

	private static int darker(int argb) {
		int r = (int) (((argb >> 16) & 0xFF) * 0.8);
		int g = (int) (((argb >> 8) & 0xFF) * 0.8);
		int b = (int) ((argb & 0xFF) * 0.8);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
