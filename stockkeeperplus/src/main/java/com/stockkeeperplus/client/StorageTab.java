package com.stockkeeperplus.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.stockkeeperplus.client.StorageTracker.Entry;
import com.stockkeeperplus.client.StorageTracker.Summary;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The "Storage" tab: how full the network's storage is, and which containers it is made of.
 * <p>
 * Top: slots used and items stored against what the known containers can hold, and the number of
 * different items. Below: the containers, grouped by block, with how many slots they add.
 */
class StorageTab {
	private static final int ROW_HEIGHT = 20;
	private static final int SUMMARY_ROW = 14;
	private static final int BAR_HEIGHT = 8;

	// Create's Stock Keeper text colors
	private static final int COLOR_TEXT = 0xF8F8EC;
	private static final int COLOR_SHADOW = 0x4A2D31;
	private static final int COLOR_DIM = 0xCDBCA8;
	private static final int COLOR_STALE = 0xA89B8C;

	/** All containers of one block type. */
	private record Group(ItemStack icon, Component name, int count, long slots, boolean allLive) {}

	private final StockKeeperPlusScreen screen;
	private int scroll;

	StorageTab(StockKeeperPlusScreen screen) {
		this.screen = screen;
	}

	// ---------------------------------------------------------------------------------------------
	// Layout
	// ---------------------------------------------------------------------------------------------

	private int left() {
		return screen.getGuiLeft();
	}

	private int coverTop() {
		return screen.getGuiTop() + 36;
	}

	private int coverBottom() {
		return screen.getGuiTop() + screen.windowHeight() - 80;
	}

	private int summaryTop() {
		return coverTop() + 4;
	}

	private int listTop() {
		return summaryTop() + 3 * SUMMARY_ROW + 4;
	}

	private int rowX() {
		return left() + 24; // Create's itemsX
	}

	private int rowRight() {
		return left() + 202;
	}

	private int visibleRows() {
		return Math.max(0, (coverBottom() - 2 - listTop()) / ROW_HEIGHT);
	}

	boolean isOverPanel(double mouseX, double mouseY) {
		return mouseX >= left() + 16 && mouseX < left() + 215 && mouseY >= coverTop() && mouseY < coverBottom();
	}

	/** 0 = slots row, 1 = items row, 2 = types row, -1 = none. */
	private int summaryRowAt(double mouseX, double mouseY) {
		if (mouseX < rowX() || mouseX >= rowRight() || mouseY < summaryTop())
			return -1;
		int row = (int) ((mouseY - summaryTop()) / SUMMARY_ROW);
		return row < 3 ? row : -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Data
	// ---------------------------------------------------------------------------------------------

	private List<Group> groups() {
		Map<ResourceLocation, List<Entry>> byBlock = new LinkedHashMap<>();
		for (Entry entry : screen.storageEntries())
			byBlock.computeIfAbsent(entry.container()
				.block(), b -> new ArrayList<>())
				.add(entry);
		List<Group> groups = new ArrayList<>();
		for (List<Entry> entries : byBlock.values()) {
			ItemStack icon = entries.get(0)
				.container()
				.icon();
			long slots = 0;
			boolean allLive = true;
			for (Entry entry : entries) {
				slots += entry.container()
					.slots();
				allLive &= entry.live();
			}
			groups.add(new Group(icon, icon.getHoverName(), entries.size(), slots, allLive));
		}
		groups.sort(Comparator.comparingLong((Group g) -> -g.slots()));
		return groups;
	}

	// ---------------------------------------------------------------------------------------------
	// Drawing
	// ---------------------------------------------------------------------------------------------

	void render(GuiGraphics graphics, int mouseX, int mouseY) {
		PoseStack ms = graphics.pose();
		Font font = Minecraft.getInstance().font;
		ms.pushPose();
		ms.translate(0, 0, 300); // above the item grid Create has drawn underneath
		GaugeTab.renderCover(graphics, left(), screen.getGuiTop(), coverTop(), coverBottom());

		Summary summary = screen.storageSummary();
		int labelX = rowX() + 2;
		int barX = rowX() + 40;
		int barWidth = 104;
		int y = summaryTop();

		text(graphics, font, Component.translatable("stockkeeperplus.storage.slots"), labelX, y + 1, COLOR_TEXT);
		FillBar.render(graphics, barX, y, barWidth, BAR_HEIGHT, summary.slotFill());
		text(graphics, font, Component.literal(percent(summary.known(), summary.slotFill())), barX + barWidth + 4, y + 1,
			COLOR_TEXT);

		y += SUMMARY_ROW;
		text(graphics, font, Component.translatable("stockkeeperplus.storage.items"), labelX, y + 1, COLOR_TEXT);
		FillBar.render(graphics, barX, y, barWidth, BAR_HEIGHT, summary.itemFill());
		text(graphics, font, Component.literal(percent(summary.itemCapacity() > 0, summary.itemFill())),
			barX + barWidth + 4, y + 1, COLOR_TEXT);

		y += SUMMARY_ROW;
		text(graphics, font, Component.translatable("stockkeeperplus.storage.types"), labelX, y + 1, COLOR_TEXT);
		text(graphics, font, Component.literal(CountFormat.exact(summary.types())), barX, y + 1, COLOR_TEXT);

		List<Group> groups = groups();
		if (groups.isEmpty()) {
			renderEmptyMessage(graphics, font);
		} else {
			scroll = Mth.clamp(scroll, 0, Math.max(0, groups.size() - visibleRows()));
			graphics.enableScissor(left() + 16, listTop(), left() + 215, coverBottom());
			for (int row = 0; row < visibleRows() && scroll + row < groups.size(); row++)
				renderGroup(graphics, font, groups.get(scroll + row), listTop() + row * ROW_HEIGHT);
			graphics.disableScissor();
		}
		ms.popPose();
	}

	private void renderGroup(GuiGraphics graphics, Font font, Group group, int y) {
		int x = rowX();
		AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, x, y + 1);
		graphics.renderItem(group.icon(), x + 1, y + 2);

		int textX = x + 22;
		String slots = Component.translatable("stockkeeperplus.storage.slot_count", CountFormat.exact(group.slots()))
			.getString();
		int slotsX = rowRight() - 2 - font.width(slots);
		String name = group.name()
			.getString() + (group.count() > 1 ? " x" + group.count() : "");
		name = fit(font, name, slotsX - textX - 6);
		int color = group.allLive() ? COLOR_TEXT : COLOR_STALE;
		text(graphics, font, Component.literal(name), textX, y + 6, color);
		graphics.drawString(font, slots, slotsX, y + 6, group.allLive() ? COLOR_DIM : COLOR_STALE, false);
	}

	private void renderEmptyMessage(GuiGraphics graphics, Font font) {
		Component message = Component.translatable(screen.tickerNetwork() == null ? "stockkeeperplus.gauges.no_network"
			: "stockkeeperplus.storage.none");
		List<FormattedCharSequence> lines = font.split(message, 160);
		int centerX = left() + screen.windowWidth() / 2;
		int y = listTop() + 8;
		for (FormattedCharSequence line : lines) {
			int width = font.width(line);
			graphics.drawString(font, line, centerX - width / 2 + 1, y + 1, COLOR_SHADOW, false);
			graphics.drawString(font, line, centerX - width / 2, y, COLOR_TEXT, false);
			y += font.lineHeight + 1;
		}
	}

	/** Text in the style of Create's category headers: light with a dark shadow one pixel down-right. */
	private static void text(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
		graphics.drawString(font, text, x + 1, y + 1, COLOR_SHADOW, false);
		graphics.drawString(font, text, x, y, color, false);
	}

	private static String percent(boolean known, double fraction) {
		return known ? Math.round(fraction * 100) + "%" : "-";
	}

	private static String fit(Font font, String text, int width) {
		if (font.width(text) <= width)
			return text;
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}

	// ---------------------------------------------------------------------------------------------
	// Tooltip and input
	// ---------------------------------------------------------------------------------------------

	boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!isOverPanel(mouseX, mouseY))
			return false;
		int row = summaryRowAt(mouseX, mouseY);
		if (row < 0)
			return false;
		graphics.renderComponentTooltip(Minecraft.getInstance().font, summaryTooltip(screen.storageSummary()),
			mouseX, mouseY);
		return true;
	}

	/** The exact numbers; also used by the bar on the order shelf. */
	static List<Component> summaryTooltip(Summary summary) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("stockkeeperplus.storage.title"));
		if (summary.known()) {
			lines.add(line("stockkeeperplus.storage.tooltip.slots", CountFormat.exact(summary.usedSlots()) + " / "
				+ CountFormat.exact(summary.totalSlots()) + " (" + percent(true, summary.slotFill()) + ")"));
			lines.add(line("stockkeeperplus.storage.tooltip.items", CountFormat.exact(summary.items())
				+ (summary.infiniteItems() ? "+" : "") + " / " + CountFormat.exact(summary.itemCapacity()) + " ("
				+ percent(true, summary.itemFill()) + ")"));
		}
		lines.add(line("stockkeeperplus.storage.tooltip.types", CountFormat.exact(summary.types())));
		if (summary.known())
			lines.add(Component.translatable("stockkeeperplus.storage.tooltip.containers", summary.containers())
				.withStyle(ChatFormatting.DARK_GRAY));
		else
			lines.add(Component.translatable("stockkeeperplus.storage.none")
				.withStyle(ChatFormatting.DARK_GRAY));
		if (summary.notLoaded() > 0)
			lines.add(Component.translatable("stockkeeperplus.storage.tooltip.not_loaded", summary.notLoaded())
				.withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	private static Component line(String key, String value) {
		return Component.translatable(key, Component.literal(value)
				.withStyle(ChatFormatting.WHITE))
			.withStyle(ChatFormatting.GRAY);
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!isOverPanel(mouseX, mouseY))
			return false;
		scroll -= (int) Math.signum(scrollY);
		if (scroll < 0)
			scroll = 0;
		return true;
	}
}
