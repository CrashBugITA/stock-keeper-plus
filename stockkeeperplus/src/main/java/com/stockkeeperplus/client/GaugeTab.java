package com.stockkeeperplus.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.stockkeeperplus.client.GaugeTracker.Entry;
import com.stockkeeperplus.common.GaugeData.Gauge;
import com.stockkeeperplus.common.GaugeData.Source;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The "Factory Gauges" tab of the Stock Keeper: a flat map of the gauge walls of the ticker's
 * network, drawn over the item grid.
 * <p>
 * Every panel sits where it is on its wall (one cell per panel, i.e. per half block), drawn with
 * Create's own gauge texture, with its item and its level below it in the colors Create uses on the
 * gauge. Arrows between gauges are drawn like Create's in-world arrows, in the same colors.
 * Separate groups of gauges are stacked top to bottom. Drag to move around, scroll to zoom.
 */
class GaugeTab {
	private static final ResourceLocation PANEL_TEXTURE =
		ResourceLocation.fromNamespaceAndPath("create", "textures/block/factory_panel.png");
	// Create's gauge model (models/block/factory_gauge/panel.json) shows a 6x6 texel panel: the frame
	// at u 1..7, v 1..7 (with a 4x4 hole in the middle) over a white paper at u 9..15, v 1..7.
	private static final int TEXEL = 4; // drawn 4 times larger
	private static final int PANEL_TEXELS = 6;
	private static final int FRAME_U = 1;
	private static final int PAPER_U = 9;
	private static final int PANEL_V = 1;
	private static final int PANEL_SIZE = PANEL_TEXELS * TEXEL;
	/**
	 * Cell size: a panel plus room for its level below and a gap. Multiples of 4, so that at every
	 * zoom step (multiples of 0.25) panels land on whole pixels and textures stay sharp.
	 */
	private static final int PITCH_X = 36;
	private static final int PITCH_Y = 36;
	/** Space between two groups of gauges stacked on the map. */
	private static final int WALL_GAP = 2 * PITCH_Y;
	private static final int PAN_MARGIN = 8;
	/** Zoom steps of the mouse wheel (1 = panels 24 px wide); each texel stays a whole number of pixels. */
	private static final double[] ZOOM_LEVELS = { 0.25, 0.5, 0.75, 1, 1.5, 2 };
	private static final double DEFAULT_ZOOM = 1;
	/** Below this zoom the levels under the panels would be unreadable, so they are hidden. */
	private static final double MIN_ZOOM_WITH_LABELS = 0.5;
	/** The fit-everything zoom is rounded down to a multiple of this, to keep textures crisp. */
	private static final double FIT_STEP = 1 / 16d;
	private static final int UPDATE_INTERVAL_TICKS = 10;

	// Create's gauge text colors (FactoryPanelBehaviour.getCountLabelForValueBox)
	private static final int COLOR_SATISFIED = 0xD7FFA8;
	private static final int COLOR_INCOMING = 0xFFCD75;
	private static final int COLOR_MISSING = 0xFFBFA8;
	private static final int COLOR_NEUTRAL = 0xF1EFE8;
	// Create's Stock Keeper text colors
	private static final int COLOR_TEXT = 0xF8F8EC;
	private static final int COLOR_SHADOW = 0x4A2D31;
	/** Laid over panels that are not up to date or do not match the search. */
	private static final int DIM_OVERLAY = 0x99302020;

	/** Where the view was left (pan x, pan y, zoom level), per network, while the game runs. */
	private static final Map<UUID, double[]> VIEW_BY_NETWORK = new HashMap<>();

	private record Placed(Entry entry, int x, int y) {}

	private final StockKeeperPlusScreen screen;
	private List<Entry> entries = List.of();
	private Map<FactoryPanelPosition, Placed> placed = Map.of();
	private int contentWidth;
	private int contentHeight;
	/** Map coordinates (unzoomed pixels) shown at the view's top-left corner. */
	private double panX;
	private double panY;
	private double zoom = DEFAULT_ZOOM;
	private boolean panInitialised;
	private int ticksUntilUpdate;

	private boolean dragging;
	private double lastDragX;
	private double lastDragY;

	GaugeTab(StockKeeperPlusScreen screen) {
		this.screen = screen;
	}

	// ---------------------------------------------------------------------------------------------
	// Data and layout
	// ---------------------------------------------------------------------------------------------

	void tick() {
		ServerLink.pollGauges(); // no-op unless the server has the mod
		if (--ticksUntilUpdate <= 0) {
			ticksUntilUpdate = UPDATE_INTERVAL_TICKS;
			refresh();
		}
	}

	void refreshNow() {
		ticksUntilUpdate = 0;
		tick();
	}

	private void refresh() {
		UUID network = screen.tickerNetwork();
		entries = network == null ? List.of() : GaugeTracker.update(network, screen.registries());
		layout();
		if (!panInitialised && network != null) {
			double[] saved = VIEW_BY_NETWORK.get(network);
			if (saved != null) {
				panX = saved[0];
				panY = saved[1];
				zoom = saved[2];
			} else {
				// Small maps are centered, large ones start at their top-left corner.
				panX = Math.min(-PAN_MARGIN, (contentWidth - viewWidth() / zoom()) / 2);
				panY = Math.min(-PAN_MARGIN, (contentHeight - viewHeight() / zoom()) / 2);
			}
			panInitialised = !entries.isEmpty();
		}
		clampPan();
	}

	/** Panels closer than this (in panels, either direction) belong to the same group on a wall. */
	private static final int CLUSTER_DISTANCE = 4;

	/**
	 * Places every panel: each group of nearby gauges keeps its shape from the wall, and the groups
	 * are stacked top to bottom (the Stock Keeper window is tall, not wide). The order only depends
	 * on where the groups are, so it does not change when more gauges are discovered.
	 */
	private void layout() {
		Map<String, List<Entry>> walls = new LinkedHashMap<>();
		for (Entry entry : entries)
			walls.computeIfAbsent(entry.gauge()
				.plane(), p -> new ArrayList<>())
				.add(entry);

		List<List<Entry>> clusters = new ArrayList<>();
		for (List<Entry> wall : walls.values())
			clusters.addAll(clusters(wall));
		clusters.sort(Comparator.comparing((List<Entry> c) -> c.get(0)
				.gauge()
				.plane())
			.thenComparingInt(c -> minOf(c, true))
			.thenComparingInt(c -> minOf(c, false)));

		Map<FactoryPanelPosition, Placed> result = new HashMap<>();
		int cursorY = 0;
		int width = 0;
		for (List<Entry> cluster : clusters) {
			int minColumn = minOf(cluster, false);
			int minRow = minOf(cluster, true);
			int maxColumn = Integer.MIN_VALUE, maxRow = Integer.MIN_VALUE;
			for (Entry entry : cluster) {
				maxColumn = Math.max(maxColumn, entry.gauge()
					.column());
				maxRow = Math.max(maxRow, entry.gauge()
					.row());
			}
			for (Entry entry : cluster) {
				Gauge gauge = entry.gauge();
				result.put(gauge.position(), new Placed(entry, (gauge.column() - minColumn) * PITCH_X,
					cursorY + (gauge.row() - minRow) * PITCH_Y));
			}
			cursorY += (maxRow - minRow + 1) * PITCH_Y + WALL_GAP;
			width = Math.max(width, (maxColumn - minColumn + 1) * PITCH_X);
		}
		placed = result;
		contentWidth = width;
		contentHeight = Math.max(0, cursorY - WALL_GAP);
	}

	private static int minOf(List<Entry> cluster, boolean rows) {
		int min = Integer.MAX_VALUE;
		for (Entry entry : cluster)
			min = Math.min(min, rows ? entry.gauge()
				.row() : entry.gauge()
				.column());
		return min;
	}

	/** Splits the gauges of one wall into groups of panels that are near each other or connected. */
	private static List<List<Entry>> clusters(List<Entry> wall) {
		int n = wall.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++)
			parent[i] = i;
		for (int i = 0; i < n; i++) {
			Gauge a = wall.get(i)
				.gauge();
			for (int j = i + 1; j < n; j++) {
				Gauge b = wall.get(j)
					.gauge();
				if (Math.abs(a.column() - b.column()) <= CLUSTER_DISTANCE
					&& Math.abs(a.row() - b.row()) <= CLUSTER_DISTANCE)
					parent[find(parent, i)] = find(parent, j);
			}
		}
		// Gauges joined by an arrow stay together however far apart they are, so arrows stay whole.
		Map<FactoryPanelPosition, Integer> indexOf = new HashMap<>();
		for (int i = 0; i < n; i++)
			indexOf.put(wall.get(i)
				.gauge()
				.position(), i);
		for (int i = 0; i < n; i++)
			for (Source source : wall.get(i)
				.gauge()
				.sources()) {
				Integer j = indexOf.get(source.from());
				if (j != null)
					parent[find(parent, i)] = find(parent, j);
			}
		Map<Integer, List<Entry>> groups = new LinkedHashMap<>();
		for (int i = 0; i < n; i++)
			groups.computeIfAbsent(find(parent, i), r -> new ArrayList<>())
				.add(wall.get(i));
		return new ArrayList<>(groups.values());
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i)
			i = parent[i] = parent[parent[i]];
		return i;
	}

	// ---------------------------------------------------------------------------------------------
	// View geometry
	// ---------------------------------------------------------------------------------------------

	private int left() {
		return screen.getGuiLeft();
	}

	private int top() {
		return screen.getGuiTop();
	}

	/** Area covered by the tab: the item window below the search row. */
	private int coverTop() {
		return top() + 36;
	}

	private int coverBottom() {
		return top() + screen.windowHeight() - 80;
	}

	/** The map is shown inside the item background (Create's item columns plus the scroll bar lane). */
	private int viewLeft() {
		return left() + 22;
	}

	private int viewRight() {
		return left() + 210;
	}

	private int viewTop() {
		return coverTop() + 2;
	}

	private int viewBottom() {
		return coverBottom() - 2;
	}

	private int viewWidth() {
		return viewRight() - viewLeft();
	}

	private int viewHeight() {
		return viewBottom() - viewTop();
	}

	private double zoom() {
		return zoom;
	}

	/**
	 * The farthest the wheel can zoom out: just enough to see every gauge (capped at 1 for walls that
	 * already fit).
	 */
	private double minZoom() {
		if (contentWidth <= 0 || contentHeight <= 0)
			return ZOOM_LEVELS[0];
		double fit = Math.min((viewWidth() - 2.0 * PAN_MARGIN) / contentWidth,
			(viewHeight() - 2.0 * PAN_MARGIN) / contentHeight);
		fit = Math.max(FIT_STEP, Math.floor(fit / FIT_STEP) * FIT_STEP);
		return Math.min(fit, 1);
	}

	/** Keeps the map from being dragged out of sight; a map smaller than the view stays centered. */
	private void clampPan() {
		double visibleWidth = viewWidth() / zoom();
		double visibleHeight = viewHeight() / zoom();
		panX = Mth.clamp(panX, Math.min(-PAN_MARGIN, (contentWidth - visibleWidth) / 2),
			Math.max(-PAN_MARGIN, contentWidth - visibleWidth + PAN_MARGIN));
		panY = Mth.clamp(panY, Math.min(-PAN_MARGIN, (contentHeight - visibleHeight) / 2),
			Math.max(-PAN_MARGIN, contentHeight - visibleHeight + PAN_MARGIN));
		UUID network = screen.tickerNetwork();
		if (network != null && panInitialised)
			VIEW_BY_NETWORK.put(network, new double[] { panX, panY, zoom });
	}

	boolean isOverPanel(double mouseX, double mouseY) {
		return mouseX >= left() + 16 && mouseX < left() + 215 && mouseY >= coverTop() && mouseY < coverBottom();
	}

	private boolean isOverView(double mouseX, double mouseY) {
		return mouseX >= viewLeft() && mouseX < viewRight() && mouseY >= viewTop() && mouseY < viewBottom();
	}

	/** Screen position of the map's origin, on a whole pixel so textures are not blurred or bleed. */
	private int originX() {
		return (int) Math.round(viewLeft() - panX * zoom());
	}

	private int originY() {
		return (int) Math.round(viewTop() - panY * zoom());
	}

	/** Map x (unzoomed pixels) under a screen x. */
	private double mapX(double screenX) {
		return (screenX - originX()) / zoom();
	}

	private double mapY(double screenY) {
		return (screenY - originY()) / zoom();
	}

	@Nullable
	private Placed panelAt(double mouseX, double mouseY) {
		if (!isOverView(mouseX, mouseY))
			return null;
		double x = mapX(mouseX);
		double y = mapY(mouseY);
		for (Placed p : placed.values())
			if (x >= p.x() && x < p.x() + PANEL_SIZE && y >= p.y() && y < p.y() + PANEL_SIZE)
				return p;
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Drawing
	// ---------------------------------------------------------------------------------------------

	void render(GuiGraphics graphics, int mouseX, int mouseY) {
		PoseStack ms = graphics.pose();
		Font font = Minecraft.getInstance().font;
		int x = left();
		int y = top();

		ms.pushPose();
		ms.translate(0, 0, 300); // above the item grid Create has drawn underneath
		renderCover(graphics, x, y, coverTop(), coverBottom());

		if (placed.isEmpty()) {
			renderEmptyMessage(graphics, font);
			ms.popPose();
			return;
		}

		graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
		String search = screen.searchText()
			.toLowerCase(Locale.ROOT)
			.trim();
		Placed hovered = panelAt(mouseX, mouseY);

		// From here on everything is drawn in map coordinates.
		ms.pushPose();
		ms.translate(originX(), originY(), 0);
		ms.scale((float) zoom(), (float) zoom(), 1);
		for (Placed target : placed.values())
			renderArrows(graphics, target);
		for (Placed p : placed.values())
			renderPanel(graphics, font, p, p == hovered, matches(p.entry()
				.gauge(), search));
		ms.popPose();

		graphics.disableScissor();
		ms.popPose();
	}

	/**
	 * Covers Create's item grid (below the search row) with the same background Create draws there.
	 * Shared by the tabs that replace the item list.
	 */
	static void renderCover(GuiGraphics graphics, int guiLeft, int guiTop, int coverTop, int coverBottom) {
		graphics.enableScissor(guiLeft - 15, coverTop, guiLeft + 241, coverBottom);
		for (int bodyY = guiTop + 36; bodyY < coverBottom; bodyY += AllGuiTextures.STOCK_KEEPER_REQUEST_BODY.getHeight())
			AllGuiTextures.STOCK_KEEPER_REQUEST_BODY.render(graphics, guiLeft - 15, bodyY);
		for (int sliceY = guiTop + 16; sliceY < coverBottom; sliceY += AllGuiTextures.STOCK_KEEPER_REQUEST_BG.getHeight())
			AllGuiTextures.STOCK_KEEPER_REQUEST_BG.render(graphics, guiLeft + 22, sliceY);
		graphics.disableScissor();
	}

	private static boolean matches(Gauge gauge, String search) {
		return search.isEmpty() || gauge.item()
			.getHoverName()
			.getString()
			.toLowerCase(Locale.ROOT)
			.contains(search) || gauge.address()
			.toLowerCase(Locale.ROOT)
			.contains(search);
	}

	private void renderPanel(GuiGraphics graphics, Font font, Placed p, boolean hovered, boolean matchesSearch) {
		Gauge gauge = p.entry()
			.gauge();
		int x = p.x();
		int y = p.y();
		if (x + PITCH_X < mapX(viewLeft()) || x > mapX(viewRight()) || y + PITCH_Y < mapY(viewTop())
			|| y > mapY(viewBottom()))
			return; // outside the view

		// Create's gauge: white paper with the golden frame on top
		graphics.blit(PANEL_TEXTURE, x, y, PANEL_SIZE, PANEL_SIZE, PAPER_U, PANEL_V, PANEL_TEXELS, PANEL_TEXELS, 16, 16);
		graphics.blit(PANEL_TEXTURE, x, y, PANEL_SIZE, PANEL_SIZE, FRAME_U, PANEL_V, PANEL_TEXELS, PANEL_TEXELS, 16, 16);
		graphics.renderItem(gauge.item(), x + TEXEL, y + TEXEL); // fills the frame's 4x4 texel hole

		PoseStack ms = graphics.pose();
		ms.pushPose();
		ms.translate(0, 0, 200); // above the item
		if (gauge.targetCount() > 0) // Create only shows the bulb on gauges with a target amount
			renderBulb(graphics, p.entry(), x, y);
		if (zoom >= MIN_ZOOM_WITH_LABELS) {
			String label = label(gauge);
			int labelX = x + PANEL_SIZE / 2 - font.width(label) / 2;
			graphics.drawString(font, label, labelX + 1, y + PANEL_SIZE + 1, COLOR_SHADOW, false);
			graphics.drawString(font, label, labelX, y + PANEL_SIZE, labelColor(gauge), false);
		}

		if (!p.entry()
			.live() || !matchesSearch)
			graphics.fill(x, y, x + PANEL_SIZE, y + PANEL_SIZE + 9, DIM_OVERLAY);
		if (hovered)
			graphics.renderOutline(x - 1, y - 1, PANEL_SIZE + 2, PANEL_SIZE + 2, 0xFFF8F8EC);
		ms.popPose();
	}

	/**
	 * "current/needed" in the gauge's unit, like "1/2▤" (whole stacks) or "40/64" (items).
	 * Gauges without a target show only the current amount.
	 */
	private static String label(Gauge gauge) {
		String current = CountFormat.isInfinite(gauge.level()) ? CountFormat.INFINITE
			: CountFormat.compact(gauge.upTo() ? gauge.level() : gauge.level() / gauge.stackSize());
		String symbol = gauge.upTo() ? "" : CountFormat.STACK_SYMBOL;
		if (gauge.targetCount() == 0)
			return current + symbol;
		return current + "/" + CountFormat.compact(gauge.targetCount()) + symbol;
	}

	private static int labelColor(Gauge gauge) {
		if (gauge.targetCount() == 0 || gauge.waiting())
			return COLOR_NEUTRAL;
		if (gauge.satisfied())
			return COLOR_SATISFIED;
		if (gauge.promisedSatisfied())
			return COLOR_INCOMING;
		return COLOR_MISSING;
	}

	// Bulb textures in factory_panel.png (2x2 texels each)
	private static final int GREEN_BULB_U = 9;
	private static final int RED_BULB_U = 11;
	private static final int BULB_V = 8;
	// Colors Create blends the arrows towards right after a request (FactoryPanelRenderer)
	private static final int REQUEST_SUCCESS = 0xEAF2EC;
	private static final int REQUEST_FAILURE = 0xE5654B;
	// Moving dashes on arrows that are waiting for ingredients
	private static final int FLOW_PERIOD = 3 * TEXEL;
	private static final int FLOW_DASH = TEXEL;
	private static final float FLOW_SPEED = 0.5f; // pixels per tick
	private static final float FLOW_HIGHLIGHT = 0.35f;

	/** Width of an arrow line: one texel of the panel. */
	private static final int LINE_WIDTH = TEXEL;
	/** Arrow head: 3 texels wide at its base, narrowing to a point on the panel's edge. */
	private static final int HEAD_WIDTH = 3 * TEXEL;
	private static final int HEAD_DEPTH = HEAD_WIDTH / 2;

	/**
	 * Arrows into this gauge, following the route Create computed for each of them in the world
	 * (FactoryPanelRenderer.renderPath): starting at the receiving gauge, one half-block step at a
	 * time, with the arrow head on the step next to the receiving gauge. Colored like Create's.
	 */
	private void renderArrows(GuiGraphics graphics, Placed target) {
		Gauge gauge = target.entry()
			.gauge();
		FactoryPanelBehaviour panel = target.entry()
			.panel();
		int baseColor = panel != null ? panel.getIngredientStatusColor() : gauge.arrowColor();
		float glow = glow(target.entry());
		boolean satisfied = panel != null ? panel.satisfied : gauge.satisfied();
		boolean redstone = panel != null ? panel.redstonePowered : gauge.redstone();
		boolean waiting = panel != null ? panel.waitingForNetwork : gauge.waiting();
		boolean missingAddress = panel != null ? panel.isMissingAddress() : gauge.address()
			.isBlank();
		// Same rule as Create's arrows: they flow while the gauge is still asking for its ingredients.
		boolean flowing = !missingAddress && !waiting && !satisfied && !redstone;
		int startX = target.x() + PANEL_SIZE / 2;
		int startY = target.y() + PANEL_SIZE / 2;

		for (Source source : gauge.sources()) {
			String path = source.path();
			if (path.isEmpty())
				continue;

			int color = baseColor;
			if (!redstone && !waiting && glow > 0 && !satisfied) {
				// Right after a request Create blends the arrow towards white (sent) or red (failed).
				FactoryPanelConnection connection = panel == null ? null : panel.targetedBy.get(source.from());
				boolean success = connection == null || connection.success;
				float p = 1 - (1 - glow) * (1 - glow);
				color = Color.mixColors(color, success ? REQUEST_SUCCESS : REQUEST_FAILURE, p);
			}
			color |= 0xFF000000;

			int[] first = gridStep(path.charAt(0));
			// The line starts at the base of the arrow head, so the head keeps its point.
			int x = startX + first[0] * (PANEL_SIZE / 2 + HEAD_DEPTH);
			int y = startY + first[1] * (PANEL_SIZE / 2 + HEAD_DEPTH);
			int cellX = startX;
			int cellY = startY;
			int distance = 0; // along the line, from the arrow head outwards
			int dashColor = 0xFF000000 | Color.mixColors(color, 0xFFFFFF, FLOW_HIGHLIGHT);
			for (int i = 0; i < path.length(); i++) {
				int[] step = gridStep(path.charAt(i));
				cellX += step[0] * PITCH_X;
				cellY += step[1] * PITCH_Y;
				segment(graphics, x, y, cellX, cellY, color);
				if (flowing)
					flowDashes(graphics, x, y, cellX, cellY, distance, dashColor);
				distance += Math.abs(cellX - x) + Math.abs(cellY - y);
				x = cellX;
				y = cellY;
			}
			arrowHead(graphics, startX, startY, first[0], first[1], color);
		}
	}

	/** Brightness of the gauge's bulb, 0..1, as Create animates it (1 right after a request). */
	private static float glow(Entry entry) {
		if (entry.panel() != null)
			return entry.panel().bulb.getValue(AnimationTickHolder.getPartialTicksUI());
		Gauge gauge = entry.gauge();
		return gauge.satisfied() || gauge.redstone() ? 1 : 0;
	}

	/**
	 * Lighter dashes moving along a line segment towards the receiving gauge, like the animated
	 * texture Create uses on arrows that are still waiting for their ingredients.
	 */
	private static void flowDashes(GuiGraphics graphics, int x1, int y1, int x2, int y2, int distanceFromHead,
		int color) {
		int length = Math.abs(x2 - x1) + Math.abs(y2 - y1);
		if (length == 0)
			return;
		int dx = Integer.signum(x2 - x1);
		int dy = Integer.signum(y2 - y1);
		float time = AnimationTickHolder.getRenderTime() * FLOW_SPEED;
		// A dash sits where (distance + time) is a multiple of the period, so it moves towards the head.
		int start = Math.floorMod((int) -time - distanceFromHead, FLOW_PERIOD);
		int half = LINE_WIDTH / 2;
		for (int t = start; t < length; t += FLOW_PERIOD) {
			int end = Math.min(length, t + FLOW_DASH);
			int ax = x1 + dx * t, ay = y1 + dy * t;
			int bx = x1 + dx * end, by = y1 + dy * end;
			graphics.fill(Math.min(ax, bx) - (dy != 0 ? half : 0), Math.min(ay, by) - (dx != 0 ? half : 0),
				Math.max(ax, bx) + (dy != 0 ? half : 0), Math.max(ay, by) + (dx != 0 ? half : 0), color);
		}
	}

	/**
	 * The gauge's bulb in its top-right corner (Create's bulb_light / bulb_red models: 2x2 texels,
	 * half outside the panel), red when paused by redstone or missing its address. It lights up with
	 * the bulb's glow: steady when the gauge is satisfied, a flash fading out after each request.
	 */
	private static void renderBulb(GuiGraphics graphics, Entry entry, int x, int y) {
		Gauge gauge = entry.gauge();
		FactoryPanelBehaviour panel = entry.panel();
		boolean red = panel != null ? panel.redstonePowered || panel.isMissingAddress()
			: gauge.redstone() || gauge.address()
				.isBlank() && !gauge.sources()
				.isEmpty();
		int size = 2 * TEXEL;
		int bulbX = x + PANEL_SIZE - TEXEL;
		int bulbY = y - TEXEL;
		graphics.blit(PANEL_TEXTURE, bulbX, bulbY, size, size, red ? RED_BULB_U : GREEN_BULB_U, BULB_V, 2, 2, 16, 16);
		float glow = glow(entry);
		if (glow > 0.5f)
			graphics.fill(bulbX, bulbY, bulbX + size, bulbY + size, ((int) ((glow - 0.5f) * 2 * 150) << 24) | 0xFFFFFF);
		else
			graphics.fill(bulbX, bulbY, bulbX + size, bulbY + size, ((int) ((0.5f - glow) * 2 * 120) << 24));
	}

	/**
	 * One step of Create's path as (columns, rows) on the map. In the panel's flat frame +x points to
	 * the panel's left and +z to its top, so both flip on screen (see GaugeTracker#read).
	 */
	private static int[] gridStep(char step) {
		return switch (step) {
			case 'N' -> new int[] { 0, 1 }; // -z
			case 'S' -> new int[] { 0, -1 }; // +z
			case 'W' -> new int[] { 1, 0 }; // -x
			case 'E' -> new int[] { -1, 0 }; // +x
			default -> new int[] { 0, 0 };
		};
	}

	/** A straight horizontal or vertical line between two cell centers. */
	private static void segment(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
		int half = LINE_WIDTH / 2;
		graphics.fill(Math.min(x1, x2) - half, Math.min(y1, y2) - half, Math.max(x1, x2) + half,
			Math.max(y1, y2) + half, color);
	}

	/**
	 * Arrow head on the receiving panel's edge, on the side the path leaves from, pointing into the
	 * panel. (dx, dy) is the direction of the path's first step away from the panel.
	 */
	private static void arrowHead(GuiGraphics graphics, int centerX, int centerY, int dx, int dy, int color) {
		int half = PANEL_SIZE / 2;
		// The tip touches the panel's edge; the base is HEAD_DEPTH further out.
		int tipX = centerX + dx * half;
		int tipY = centerY + dy * half;
		for (int d = 0; d < HEAD_DEPTH; d++) {
			// 2 px wide at the tip, growing evenly to HEAD_WIDTH at the base
			int halfWidth = Math.max(1, Math.round((HEAD_WIDTH / 2f) * (d + 1) / HEAD_DEPTH));
			if (dy != 0) {
				int rowY = tipY + dy * (d + 1);
				graphics.fill(tipX - halfWidth, Math.min(rowY, rowY - dy), tipX + halfWidth, Math.max(rowY, rowY - dy), color);
			} else {
				int colX = tipX + dx * (d + 1);
				graphics.fill(Math.min(colX, colX - dx), tipY - halfWidth, Math.max(colX, colX - dx), tipY + halfWidth, color);
			}
		}
	}

	private void renderEmptyMessage(GuiGraphics graphics, Font font) {
		Component message = Component.translatable(screen.tickerNetwork() == null ? "stockkeeperplus.gauges.no_network"
			: "stockkeeperplus.gauges.none");
		List<FormattedCharSequence> lines = font.split(message, 160);
		int centerX = left() + screen.windowWidth() / 2;
		int y = viewTop() + 20;
		for (FormattedCharSequence line : lines) {
			int width = font.width(line);
			graphics.drawString(font, line, centerX - width / 2 + 1, y + 1, COLOR_SHADOW, false);
			graphics.drawString(font, line, centerX - width / 2, y, COLOR_TEXT, false);
			y += font.lineHeight + 1;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Tooltip
	// ---------------------------------------------------------------------------------------------

	boolean renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (dragging)
			return false;
		Placed p = panelAt(mouseX, mouseY);
		if (p == null)
			return false;
		Entry entry = p.entry();
		Gauge gauge = entry.gauge();
		List<Component> lines = new ArrayList<>();
		lines.add(gauge.item()
			.getHoverName());

		lines.add(line("stockkeeperplus.gauges.tooltip.in_storage",
			CountFormat.exactWithStacks(gauge.level(), gauge.stackSize()), ChatFormatting.WHITE));
		if (gauge.targetCount() > 0)
			lines.add(line("stockkeeperplus.gauges.tooltip.target",
				CountFormat.exactWithStacks(gauge.targetItems(), gauge.stackSize()), ChatFormatting.WHITE));
		if (gauge.promised() > 0)
			lines.add(line("stockkeeperplus.gauges.tooltip.incoming",
				CountFormat.exactWithStacks(gauge.promised(), gauge.stackSize()), ChatFormatting.GOLD));
		if (gauge.targetCount() > 0 && gauge.missingItems() > 0)
			lines.add(line("stockkeeperplus.gauges.tooltip.missing",
				CountFormat.exactWithStacks(gauge.missingItems(), gauge.stackSize()), ChatFormatting.RED));

		if (!gauge.address()
			.isBlank())
			lines.add(line("stockkeeperplus.gauges.tooltip.address", gauge.address(), ChatFormatting.WHITE));
		if (!gauge.sources()
			.isEmpty()) {
			lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.ingredients")
				.withStyle(ChatFormatting.GRAY));
			for (Source source : gauge.sources()) {
				Placed from = placed.get(source.from());
				Component name = from == null ? Component.translatable("stockkeeperplus.gauges.tooltip.unknown_gauge")
					: from.entry()
					.gauge()
					.item()
					.getHoverName();
				lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.ingredient", source.amount(), name)
					.withStyle(ChatFormatting.WHITE));
			}
		}
		switch (gauge.status()) {
			case WAITING -> lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.waiting")
				.withStyle(ChatFormatting.GRAY));
			case PAUSED -> lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.paused")
				.withStyle(ChatFormatting.GRAY));
			default -> {
			}
		}
		if (gauge.restocker())
			lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.restocker")
				.withStyle(ChatFormatting.GRAY));

		if (!entry.live())
			lines.add(Component.translatable("stockkeeperplus.gauges.tooltip.stale", ago(gauge.lastSeen()))
				.withStyle(ChatFormatting.DARK_GRAY));

		graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
		return true;
	}

	private static Component line(String key, String value, ChatFormatting valueColor) {
		MutableComponent v = Component.literal(value)
			.withStyle(valueColor);
		return Component.translatable(key, v)
			.withStyle(ChatFormatting.GRAY);
	}

	static Component ago(long lastSeen) {
		long minutes = Math.max(0, (System.currentTimeMillis() - lastSeen) / 60_000);
		if (minutes < 1)
			return Component.translatable("stockkeeperplus.gauges.time.now");
		if (minutes < 60)
			return Component.translatable("stockkeeperplus.gauges.time.minutes", minutes);
		long hours = minutes / 60;
		if (hours < 48)
			return Component.translatable("stockkeeperplus.gauges.time.hours", hours);
		return Component.translatable("stockkeeperplus.gauges.time.days", hours / 24);
	}

	// ---------------------------------------------------------------------------------------------
	// Moving around: drag with the left mouse button; the wheel zooms around the mouse
	// ---------------------------------------------------------------------------------------------

	/** Returns true if the click was inside the tab (even if it did nothing). */
	boolean mouseClicked(double mouseX, double mouseY) {
		if (!isOverPanel(mouseX, mouseY))
			return false;
		dragging = true;
		lastDragX = mouseX;
		lastDragY = mouseY;
		return true;
	}

	boolean mouseDragged(double mouseX, double mouseY) {
		if (!dragging)
			return false;
		panX -= (mouseX - lastDragX) / zoom();
		panY -= (mouseY - lastDragY) / zoom();
		lastDragX = mouseX;
		lastDragY = mouseY;
		clampPan();
		return true;
	}

	boolean mouseReleased() {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!isOverPanel(mouseX, mouseY))
			return false;
		double newZoom = scrollY > 0 ? zoomIn() : zoomOut();
		if (newZoom == zoom)
			return true;
		// Keep the point under the mouse where it is.
		double anchorX = mapX(mouseX);
		double anchorY = mapY(mouseY);
		zoom = newZoom;
		panX = anchorX - (mouseX - viewLeft()) / zoom();
		panY = anchorY - (mouseY - viewTop()) / zoom();
		clampPan();
		return true;
	}

	private double zoomIn() {
		for (double level : ZOOM_LEVELS)
			if (level > zoom + 1e-6)
				return level;
		return zoom;
	}

	/** One step out, but never farther than needed to see every gauge. */
	private double zoomOut() {
		double min = minZoom();
		double next = min;
		for (double level : ZOOM_LEVELS)
			if (level < zoom - 1e-6)
				next = level;
		return Math.max(next, Math.min(min, zoom));
	}
}
