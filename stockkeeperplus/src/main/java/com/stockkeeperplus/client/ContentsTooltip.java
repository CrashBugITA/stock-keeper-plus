package com.stockkeeperplus.client;

import java.util.List;

import com.stockkeeperplus.network.Payloads.ItemCount;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Tooltip part showing what a container holds as a grid of item icons with their amounts, like an
 * inventory. Items are drawn by Minecraft's own item renderer, so items from Create, vanilla and
 * any other mod look as they do everywhere else.
 */
public record ContentsTooltip(List<ItemCount> items) implements TooltipComponent {
	private static final int COLUMNS = 9;
	private static final int MAX_ROWS = 4;
	private static final int CELL = 18;

	/** Client-side renderer, registered in StockKeeperPlusClient. */
	public static final class Renderer implements ClientTooltipComponent {
		private final List<ItemCount> items;

		public Renderer(ContentsTooltip tooltip) {
			this.items = tooltip.items();
		}

		/** Cells used: all items, or the first ones plus a "+N" cell when they do not fit. */
		private int cells() {
			return Math.min(items.size(), COLUMNS * MAX_ROWS);
		}

		private boolean overflows() {
			return items.size() > COLUMNS * MAX_ROWS;
		}

		@Override
		public int getHeight() {
			int rows = (cells() + COLUMNS - 1) / COLUMNS;
			return rows * CELL + 2;
		}

		@Override
		public int getWidth(Font font) {
			return Math.min(cells(), COLUMNS) * CELL;
		}

		@Override
		public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
			int shown = overflows() ? cells() - 1 : cells();
			for (int i = 0; i < shown; i++) {
				ItemCount item = items.get(i);
				int cellX = x + (i % COLUMNS) * CELL + 1;
				int cellY = y + (i / COLUMNS) * CELL + 1;
				graphics.renderItem(item.item(), cellX, cellY);
				graphics.renderItemDecorations(font, item.item(), cellX, cellY,
					item.count() > 1 ? CountFormat.compact(item.count()) : null);
			}
			if (overflows()) {
				int i = shown;
				String more = "+" + CountFormat.compact(items.size() - shown);
				int cellX = x + (i % COLUMNS) * CELL;
				int cellY = y + (i / COLUMNS) * CELL;
				graphics.drawString(font, more, cellX + (CELL - font.width(more)) / 2, cellY + 5, 0xFFAAAAAA, true);
			}
		}
	}
}
