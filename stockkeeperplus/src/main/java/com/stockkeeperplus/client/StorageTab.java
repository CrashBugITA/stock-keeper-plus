package com.stockkeeperplus.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.client.StorageTracker.Entry;
import com.stockkeeperplus.client.StorageTracker.Summary;
import com.stockkeeperplus.common.ContainerData;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * The "Storage" tab: how full the network's storage is, and the containers it is made of.
 * <p>
 * Top: slots used and items stored against what the known containers can hold, and the number of
 * different items (estimated from the stock, works everywhere). Below: one row per container,
 * except containers the player put in the same group, which share one row. The tag button on a
 * row puts it in a group (or renames the group). Hovering a row shows what is inside when the
 * server has the mod.
 */
class StorageTab {
	private static final int ROW_HEIGHT = 20;
	private static final int SUMMARY_ROW = 14;
	private static final int BAR_HEIGHT = 8;
	private static final int ROW_BAR_WIDTH = 30;
	private static final int ROW_BAR_HEIGHT = 5;

	private static final ResourceLocation BUTTONS =
		ResourceLocation.fromNamespaceAndPath(StockKeeperPlus.MOD_ID, "textures/gui/buttons.png");
	private static final int TAG_U = 48;
	private static final int TAG_V = 32;
	private static final int TAG_WIDTH = 9;
	private static final int TAG_HEIGHT = 6;

	// Create's Stock Keeper text colors
	private static final int COLOR_TEXT = 0xF8F8EC;
	private static final int COLOR_SHADOW = 0x4A2D31;
	private static final int COLOR_DIM = 0xCDBCA8;
	private static final int COLOR_STALE = 0xA89B8C;

	/** One row: a container on its own, or all containers of a group. */
	private record Row(@Nullable String group, List<Entry> entries) {
		ContainerData first() {
			return entries.get(0)
				.container();
		}

		ItemStack icon() {
			return first().icon();
		}

		Component name() {
			return group != null ? Component.literal(group) : icon().getHoverName();
		}

		long slots() {
			long total = 0;
			for (Entry entry : entries)
				total += entry.container()
					.slots();
			return total;
		}

		/** Slots in use, or -1 if not known for every container of the row. */
		long usedSlots() {
			long used = 0;
			for (Entry entry : entries) {
				if (!entry.container()
					.fillKnown())
					return -1;
				used += entry.container()
					.usedSlots();
			}
			return used;
		}

		boolean allLive() {
			for (Entry entry : entries)
				if (!entry.live())
					return false;
			return true;
		}

		long oldestSeen() {
			long oldest = Long.MAX_VALUE;
			for (Entry entry : entries)
				oldest = Math.min(oldest, entry.container()
					.lastSeen());
			return oldest;
		}

		List<GlobalPos> positions() {
			List<GlobalPos> positions = new ArrayList<>();
			for (Entry entry : entries) {
				ContainerData c = entry.container();
				ResourceLocation dimension = ResourceLocation.tryParse(c.dimension());
				if (dimension != null)
					positions.add(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), c.pos()));
			}
			return positions;
		}
	}

	private final StockKeeperPlusScreen screen;
	private int scroll;

	/** The row whose group is being edited, and the text field for it. */
	@Nullable
	private Row editing;
	@Nullable
	private EditBox nameBox;

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

	private int tagX() {
		return rowRight() - TAG_WIDTH - 1;
	}

	private int tagY(int rowY) {
		return rowY + (ROW_HEIGHT - TAG_HEIGHT) / 2;
	}

	boolean isOverPanel(double mouseX, double mouseY) {
		return mouseX >= left() + 16 && mouseX < left() + 215 && mouseY >= coverTop() && mouseY < coverBottom();
	}

	/** 0 = slots row, 1 = items row, 2 = different items row, -1 = none. */
	private int summaryRowAt(double mouseX, double mouseY) {
		if (mouseX < rowX() || mouseX >= rowRight() || mouseY < summaryTop() || mouseY >= listTop())
			return -1;
		int row = (int) ((mouseY - summaryTop()) / SUMMARY_ROW);
		return row < 3 ? row : -1;
	}

	/** Index in the row list under the mouse, or -1. */
	private int rowIndexAt(List<Row> rows, double mouseX, double mouseY) {
		if (mouseX < rowX() || mouseX >= rowRight() || mouseY < listTop())
			return -1;
		int visible = (int) ((mouseY - listTop()) / ROW_HEIGHT);
		if (visible >= visibleRows())
			return -1;
		int index = scroll + visible;
		return index < rows.size() ? index : -1;
	}

	private int rowY(int index) {
		return listTop() + (index - scroll) * ROW_HEIGHT;
	}

	private boolean isOverTag(int index, double mouseX, double mouseY) {
		int y = tagY(rowY(index));
		return mouseX >= tagX() - 1 && mouseX < tagX() + TAG_WIDTH + 1 && mouseY >= y - 1 && mouseY < y + TAG_HEIGHT + 1;
	}

	// ---------------------------------------------------------------------------------------------
	// Data
	// ---------------------------------------------------------------------------------------------

	/** Groups first (by name), then single containers (largest first). */
	private List<Row> rows() {
		Map<String, List<Entry>> groups = new LinkedHashMap<>();
		List<Row> singles = new ArrayList<>();
		for (Entry entry : screen.storageEntries()) {
			String group = StorageGroups.groupOf(entry.container());
			if (group == null)
				singles.add(new Row(null, List.of(entry)));
			else
				groups.computeIfAbsent(group, g -> new ArrayList<>())
					.add(entry);
		}
		List<Row> rows = new ArrayList<>();
		groups.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
			.forEach(e -> rows.add(new Row(e.getKey(), e.getValue())));
		singles.sort(Comparator.comparingLong((Row r) -> -r.slots()));
		rows.addAll(singles);
		return rows;
	}

	// ---------------------------------------------------------------------------------------------
	// Drawing
	// ---------------------------------------------------------------------------------------------

	void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		PoseStack ms = graphics.pose();
		Font font = Minecraft.getInstance().font;
		ms.pushPose();
		ms.translate(0, 0, 300); // above the item grid Create has drawn underneath
		GaugeTab.renderCover(graphics, left(), screen.getGuiTop(), coverTop(), coverBottom());

		renderSummary(graphics, font);

		List<Row> rows = rows();
		if (rows.isEmpty()) {
			renderEmptyMessage(graphics, font);
		} else {
			scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows()));
			graphics.enableScissor(left() + 16, listTop(), left() + 215, coverBottom());
			int hovered = rowIndexAt(rows, mouseX, mouseY);
			for (int index = scroll; index < rows.size() && index < scroll + visibleRows(); index++)
				renderRow(graphics, font, rows.get(index), rowY(index), index == hovered,
					index == hovered && isOverTag(index, mouseX, mouseY));
			graphics.disableScissor();
		}

		if (nameBox != null)
			nameBox.render(graphics, mouseX, mouseY, partialTicks);
		ms.popPose();
	}

	private void renderSummary(GuiGraphics graphics, Font font) {
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
		Component typesLabel = Component.translatable("stockkeeperplus.storage.types");
		text(graphics, font, typesLabel, labelX, y + 1, COLOR_TEXT);
		text(graphics, font, Component.literal(CountFormat.exact(summary.types())), labelX + font.width(typesLabel) + 6,
			y + 1, COLOR_TEXT);
	}

	private void renderRow(GuiGraphics graphics, Font font, Row row, int y, boolean hovered, boolean tagHovered) {
		int x = rowX();
		if (hovered)
			graphics.fill(x - 1, y, rowRight(), y + ROW_HEIGHT, 0x20FFFFFF);
		AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(graphics, x, y + 1);
		graphics.renderItem(row.icon(), x + 1, y + 2);

		boolean live = row.allLive();
		int textX = x + 22;
		int right = tagX() - 4;

		// Right side: how full the row is (when the server tells us), and its size.
		long used = row.usedSlots();
		String size = used >= 0 ? CountFormat.exact(used) + "/" + CountFormat.exact(row.slots())
			: Component.translatable("stockkeeperplus.storage.slot_count", CountFormat.exact(row.slots()))
				.getString();
		int sizeX = right - font.width(size);
		graphics.drawString(font, size, sizeX, y + 6, live ? COLOR_DIM : COLOR_STALE, false);
		if (used >= 0) {
			sizeX -= ROW_BAR_WIDTH + 4;
			FillBar.render(graphics, sizeX, y + 7, ROW_BAR_WIDTH, ROW_BAR_HEIGHT,
				row.slots() == 0 ? 0 : (double) used / row.slots());
		}

		String name = row.name()
			.getString() + (row.entries()
			.size() > 1 ? " x" + row.entries()
			.size() : "");
		if (row != editing) {
			name = fit(font, name, sizeX - textX - 4);
			text(graphics, font, Component.literal(name), textX, y + 6, live ? COLOR_TEXT : COLOR_STALE);
		}

		// Tag button: put this container in a group, or rename the group
		if (tagHovered)
			graphics.fill(tagX() - 1, tagY(y) - 1, tagX() + TAG_WIDTH + 1, tagY(y) + TAG_HEIGHT + 1, 0x40FFFFFF);
		graphics.blit(BUTTONS, tagX(), tagY(y), TAG_U, TAG_V, TAG_WIDTH, TAG_HEIGHT, 64, 64);
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
	// Tooltips
	// ---------------------------------------------------------------------------------------------

	boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!isOverPanel(mouseX, mouseY) || nameBox != null)
			return false;
		Font font = Minecraft.getInstance().font;
		if (summaryRowAt(mouseX, mouseY) >= 0) {
			graphics.renderComponentTooltip(font, summaryTooltip(screen.storageSummary()), mouseX, mouseY);
			return true;
		}
		List<Row> rows = rows();
		int index = rowIndexAt(rows, mouseX, mouseY);
		if (index < 0)
			return false;
		Row row = rows.get(index);
		if (isOverTag(index, mouseX, mouseY)) {
			graphics.renderComponentTooltip(font, List.of(Component.translatable(row.group() != null
				? "stockkeeperplus.storage.rename_group" : "stockkeeperplus.storage.set_group")), mouseX, mouseY);
			return true;
		}
		Optional<TooltipComponent> contents = Optional.empty();
		List<Component> lines = rowTooltip(row);
		ServerLink.Contents known = ServerLink.available() ? ServerLink.contents(row.positions()) : null;
		if (known != null && !known.items()
			.isEmpty())
			contents = Optional.of(new ContentsTooltip(known.items()));
		graphics.renderTooltip(font, lines, contents, mouseX, mouseY);
		return true;
	}

	private List<Component> rowTooltip(Row row) {
		List<Component> lines = new ArrayList<>();
		lines.add(row.name());
		if (row.group() != null)
			lines.add(Component.translatable("stockkeeperplus.storage.tooltip.group_size", row.entries()
					.size())
				.withStyle(ChatFormatting.GRAY));
		long used = row.usedSlots();
		if (used >= 0)
			lines.add(line("stockkeeperplus.storage.tooltip.slots", CountFormat.exact(used) + " / "
				+ CountFormat.exact(row.slots()) + " (" + percent(true, row.slots() == 0 ? 0 : (double) used / row.slots())
				+ ")"));
		else // without the server part only the size is known
			lines.add(line("stockkeeperplus.storage.tooltip.size", CountFormat.exact(row.slots())));
		if (!row.allLive())
			lines.add(Component.translatable("stockkeeperplus.storage.tooltip.stale", GaugeTab.ago(row.oldestSeen()))
				.withStyle(ChatFormatting.DARK_GRAY));

		// What is inside: asked from the server while hovering (only if it has the mod).
		if (!ServerLink.available()) {
			lines.add(Component.translatable("stockkeeperplus.storage.tooltip.no_server_mod")
				.withStyle(ChatFormatting.DARK_GRAY));
		} else {
			ServerLink.Contents contents = ServerLink.contents(row.positions());
			if (contents == null) {
				lines.add(Component.translatable("stockkeeperplus.storage.tooltip.loading")
					.withStyle(ChatFormatting.DARK_GRAY));
			} else if (contents.items()
				.isEmpty()) {
				lines.add(Component.translatable("stockkeeperplus.storage.tooltip.empty")
					.withStyle(ChatFormatting.DARK_GRAY));
			}
			// Otherwise the contents are drawn as a grid of item icons below these lines (ContentsTooltip).
		}
		return lines;
	}

	/** The exact numbers of the whole network; also used by the bar on the order shelf. */
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
		if (!summary.known())
			lines.add(Component.translatable("stockkeeperplus.storage.none")
				.withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	private static Component line(String key, String value) {
		return Component.translatable(key, Component.literal(value)
				.withStyle(ChatFormatting.WHITE))
			.withStyle(ChatFormatting.GRAY);
	}

	// ---------------------------------------------------------------------------------------------
	// Input
	// ---------------------------------------------------------------------------------------------

	/** Returns true if the click was inside the tab (even if it did nothing). */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (nameBox != null) {
			if (nameBox.isMouseOver(mouseX, mouseY))
				return nameBox.mouseClicked(mouseX, mouseY, button);
			commitEdit(); // clicking elsewhere confirms what was typed
		}
		if (!isOverPanel(mouseX, mouseY))
			return false;
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return true;
		List<Row> rows = rows();
		int index = rowIndexAt(rows, mouseX, mouseY);
		if (index >= 0 && isOverTag(index, mouseX, mouseY))
			startEdit(rows.get(index), rowY(index));
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!isOverPanel(mouseX, mouseY))
			return false;
		if (nameBox == null)
			scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
		return true;
	}

	boolean isEditing() {
		return nameBox != null;
	}

	private void startEdit(Row row, int rowY) {
		Font font = Minecraft.getInstance().font;
		int x = rowX() + 22;
		nameBox = new EditBox(font, x, rowY + 5, tagX() - 6 - x, 10, Component.empty());
		nameBox.setMaxLength(40);
		nameBox.setValue(row.group() != null ? row.group() : "");
		nameBox.setHint(Component.translatable("stockkeeperplus.storage.group_hint")
			.withStyle(ChatFormatting.DARK_GRAY));
		nameBox.setFocused(true);
		editing = row;
	}

	private void commitEdit() {
		if (nameBox == null || editing == null)
			return;
		String name = nameBox.getValue();
		if (editing.group() != null)
			StorageGroups.renameGroup(editing.group(), name);
		else
			StorageGroups.setGroup(editing.first(), name);
		cancelEdit();
	}

	private void cancelEdit() {
		nameBox = null;
		editing = null;
	}

	/** Keys go to the name field while it is open: Enter confirms, Esc cancels, Tab completes. */
	boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (nameBox == null)
			return false;
		switch (keyCode) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitEdit();
			case GLFW.GLFW_KEY_ESCAPE -> cancelEdit();
			case GLFW.GLFW_KEY_TAB -> complete();
			default -> nameBox.keyPressed(keyCode, scanCode, modifiers);
		}
		return true;
	}

	boolean charTyped(char codePoint, int modifiers) {
		if (nameBox == null)
			return false;
		nameBox.charTyped(codePoint, modifiers);
		return true;
	}

	/** Completes the typed text with the first existing group that starts with it. */
	private void complete() {
		if (nameBox == null)
			return;
		String typed = nameBox.getValue()
			.toLowerCase();
		for (String name : StorageGroups.names())
			if (name.toLowerCase()
				.startsWith(typed)) {
				nameBox.setValue(name);
				return;
			}
	}
}
