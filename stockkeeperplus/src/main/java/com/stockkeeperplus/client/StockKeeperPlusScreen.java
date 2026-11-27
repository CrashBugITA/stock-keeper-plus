package com.stockkeeperplus.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.mixin.CategoryEntryAccessor;
import com.stockkeeperplus.mixin.StockKeeperRequestScreenAccessor;
import com.stockkeeperplus.mixin.StockTickerBlockEntityAccessor;

import net.createmod.catnip.data.Couple;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Drop-in replacement for Create's Stock Keeper request screen, opened by {@link ScreenSwapHandler}.
 * <p>
 * It extends {@link StockKeeperRequestScreen} so that everything which checks
 * {@code instanceof StockKeeperRequestScreen} keeps working: Create's JEI recipe transfer handler,
 * its JEI hovered-ingredient handler, and the stock response packets that go through
 * {@code menu.screenReference}.
 */
public class StockKeeperPlusScreen extends StockKeeperRequestScreen implements ItemGridCover {
	/** Entry whose tooltip is being rendered right now, or null. Only set during renderForeground. */
	@Nullable
	private BigItemStack tooltipEntry;
	private boolean tooltipEntryIsOrder;

	public StockKeeperPlusScreen(StockKeeperRequestMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		Favorites.loadIfNeeded(registries());
		if (Favorites.isCollapsed())
			accessor().stockkeeperplus$getHiddenCategories()
				.add(FAVORITES_CATEGORY);
		restoreCart();
		gaugeTab = new GaugeTab(this);
		gaugeMode = lastTabWasGauges;
	}

	// ---------------------------------------------------------------------------------------------
	// Cart kept between openings
	// ---------------------------------------------------------------------------------------------
	// Not used while programming a Redstone Requester / Table Cloth (the order is their configuration)
	// or in clipboard (schematic list) mode, where Create fills the order from the clipboard.
	// Create re-checks the restored amounts against the stock as soon as it arrives (revalidateOrders),
	// so items that ran out are reduced or dropped.

	private boolean usesSavedCart() {
		return !isSchematicListMode() && !accessor().stockkeeperplus$isEncodingRequester();
	}

	private void restoreCart() {
		if (!usesSavedCart())
			return;
		List<BigItemStack> saved = CartStore.load(menu.contentHolder.getLevel()
			.dimension(), menu.contentHolder.getBlockPos(), registries());
		// Create allows at most one row (9 different items) in the order.
		for (BigItemStack entry : saved)
			if (itemsToOrder.size() < COLUMNS)
				itemsToOrder.add(entry);
	}

	private void saveCart() {
		if (!usesSavedCart())
			return;
		CartStore.save(menu.contentHolder.getLevel()
			.dimension(), menu.contentHolder.getBlockPos(), itemsToOrder, registries());
	}

	@Override
	protected void init() {
		super.init();
		layoutSearchBox();
		// Keep JEI's side panels away from the tabs.
		getExtraAreas().add(new Rect2i(tabX(), tabY(0), TAB_WIDTH, TAB_SPACING + TAB_HEIGHT));
		if (gaugeMode)
			gaugeTab.refreshNow();
		StockKeeperPlus.LOGGER.debug("Stock Keeper Plus screen initialised for ticker at {}",
			menu.contentHolder.getBlockPos());
	}

	private StockKeeperRequestScreenAccessor accessor() {
		return (StockKeeperRequestScreenAccessor) this;
	}

	// ---------------------------------------------------------------------------------------------
	// Sorting and the Favorites category
	// ---------------------------------------------------------------------------------------------
	// Create rebuilds displayedItems and categories (always as new lists) inside containerTick whenever
	// the stock or the search changes. Right after that we post-process them:
	//  1. sort the items of every category;
	//  2. put a "Favorites" category on top with the favorite items that pass the search (they also
	//     stay in their own category);
	//  3. recompute the header positions the same way Create does.
	// Everything Create does afterwards (drawing, hovering, clicking, collapsing, JEI) reads these
	// two lists in parallel, so the extra category behaves like a normal one.

	/** targetBECategory of the Favorites header. Never sent to the server (see removed()). */
	private static final int FAVORITES_CATEGORY = -2;

	@Nullable
	private List<List<BigItemStack>> lastProcessedItems;

	@Override
	protected void containerTick() {
		super.containerTick();
		if (displayedItems != lastProcessedItems)
			postProcessList();
		if (gaugeMode) {
			// The search row scrolls with Create's item list; keep it in place under the gauge tab.
			if (itemScroll.getChaseTarget() != 0)
				itemScroll.startWithValue(0);
			gaugeTab.tick();
		}
	}

	/** Asks Create to rebuild the list now (e.g. after a sort or favorite change), then post-processes it. */
	private void rebuildList() {
		accessor().stockkeeperplus$refreshSearchResults(false);
		postProcessList();
	}

	private void postProcessList() {
		SortMode mode = ClientConfig.sortMode();
		List<List<BigItemStack>> lists = new ArrayList<>(displayedItems.size() + 1);
		for (List<BigItemStack> category : displayedItems)
			lists.add(mode.sorted(category));

		List<BigItemStack> favorites = collectFavorites(lists, mode);
		if (!favorites.isEmpty() && !isSchematicListMode()) {
			if (categories.isEmpty())
				createMissingCategoryEntries(lists.size());
			CategoryEntry header = new CategoryEntry(FAVORITES_CATEGORY,
				Component.translatable("stockkeeperplus.gui.favorites")
					.getString(),
				0);
			lists.add(0, favorites);
			categories.add(0, header);
			layoutCategories(lists);
		}

		displayedItems = lists;
		lastProcessedItems = lists;
	}

	private static List<BigItemStack> collectFavorites(List<List<BigItemStack>> lists, SortMode mode) {
		if (Favorites.isEmpty())
			return List.of();
		List<BigItemStack> favorites = new ArrayList<>();
		for (List<BigItemStack> category : lists)
			for (BigItemStack entry : category)
				if (Favorites.isFavorite(entry.stack))
					favorites.add(entry);
		return mode.sorted(favorites);
	}

	/**
	 * Create drops all headers when only "Unsorted" has items. With a Favorites header on top the
	 * other headers are needed again, so rebuild them the way Create names them.
	 */
	private void createMissingCategoryEntries(int count) {
		List<ItemStack> filters = ((StockTickerBlockEntityAccessor) menu.contentHolder).stockkeeperplus$getCategories();
		for (int i = 0; i < count; i++) {
			boolean unsorted = i == count - 1;
			String name;
			if (unsorted)
				name = Component.translatable("create.gui.stock_keeper.unsorted_category")
					.getString();
			else if (i < filters.size() && !filters.get(i)
				.isEmpty())
				name = filters.get(i)
					.getHoverName()
					.getString();
			else
				name = "";
			categories.add(new CategoryEntry(unsorted ? -1 : i, name, 0));
		}
	}

	/** Same header layout as Create's refreshSearchResults. */
	private void layoutCategories(List<List<BigItemStack>> lists) {
		Set<Integer> hidden = accessor().stockkeeperplus$getHiddenCategories();
		int y = 0;
		for (int i = 0; i < lists.size() && i < categories.size(); i++) {
			CategoryEntryAccessor entry = (CategoryEntryAccessor) categories.get(i);
			entry.stockkeeperplus$setHidden(hidden.contains(entry.stockkeeperplus$getTargetBECategory()));
			entry.stockkeeperplus$setY(y);
			List<BigItemStack> items = lists.get(i);
			if (items.isEmpty())
				continue;
			y += ROW_HEIGHT;
			if (!entry.stockkeeperplus$isHidden())
				y += Mth.ceil(items.size() / (float) COLUMNS) * ROW_HEIGHT;
		}
	}

	private boolean toggleFavoriteUnderMouse(double mouseX, double mouseY) {
		Couple<Integer> hovered = accessor().stockkeeperplus$getHoveredSlot((int) mouseX, (int) mouseY);
		if (hovered == accessor().stockkeeperplus$getNoneHovered() || hovered.getFirst() < 0)
			return false;
		List<BigItemStack> category = displayedItems.get(hovered.getFirst());
		if (hovered.getSecond() >= category.size())
			return false;
		boolean nowFavorite = Favorites.toggle(category.get(hovered.getSecond()).stack, registries());
		rebuildList();
		playUiSound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, nowFavorite ? 1.5f : 0.675f);
		return true;
	}

	HolderLookup.Provider registries() {
		return menu.player.level()
			.registryAccess();
	}

	@Override
	public void removed() {
		// Remember the Favorites collapse state locally and keep our fake index out of the list
		// of hidden categories that Create sends to the server.
		Set<Integer> hidden = accessor().stockkeeperplus$getHiddenCategories();
		Favorites.setCollapsed(hidden.remove(FAVORITES_CATEGORY), registries());
		saveCart();
		GaugeTracker.save(registries());
		lastTabWasGauges = gaugeMode;
		super.removed();
	}

	private void renderFavoriteStars(GuiGraphics graphics, float scroll) {
		if (Favorites.isEmpty())
			return;
		int itemsX = accessor().stockkeeperplus$getItemsX();
		int itemsY = accessor().stockkeeperplus$getItemsY();
		int top = getGuiTop();
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(0, 0, 250); // above the item icons and their count numbers
		for (int c = 0; c < displayedItems.size(); c++) {
			List<BigItemStack> category = displayedItems.get(c);
			CategoryEntryAccessor entry = categories.isEmpty() ? null : (CategoryEntryAccessor) categories.get(c);
			if (category.isEmpty() || entry != null && entry.stockkeeperplus$isHidden())
				continue;
			int categoryY = entry == null ? 0 : entry.stockkeeperplus$getY();
			for (int index = 0; index < category.size(); index++) {
				// Same position and culling as Create's item loop.
				int pY = itemsY + categoryY + (entry == null ? 4 : ROW_HEIGHT) + (index / COLUMNS) * ROW_HEIGHT;
				float cullY = pY - scroll * ROW_HEIGHT;
				if (cullY < top)
					continue;
				if (cullY > top + imageHeight - 72)
					break;
				if (Favorites.isFavorite(category.get(index).stack))
					graphics.blit(BUTTONS, itemsX + (index % COLUMNS) * 20 + STAR_OFFSET, pY + STAR_OFFSET, STAR_U,
						STAR_V, STAR_SIZE, STAR_SIZE, BUTTONS_TEXTURE_WIDTH, BUTTONS_TEXTURE_HEIGHT);
			}
		}
		graphics.pose()
			.popPose();
	}

	// Sort button: same 15x16 style and row as Create's search-sync button, placed after the dot to
	// the right of the search bar. That spot belongs to the network lock when the player is an admin;
	// in that case the search bar is drawn 17 px shorter so both buttons fit:
	//   [sync] • [search          ] • [sort] [lock]
	// Like Create's buttons in this row, it scrolls with the item list and only works at the top.

	private static final ResourceLocation BUTTONS =
		ResourceLocation.fromNamespaceAndPath(StockKeeperPlus.MOD_ID, "textures/gui/buttons.png");
	private static final ResourceLocation CREATE_STOCK_KEEPER =
		ResourceLocation.fromNamespaceAndPath("create", "textures/gui/stock_keeper.png");
	private static final int BUTTONS_TEXTURE_WIDTH = 64;
	private static final int BUTTONS_TEXTURE_HEIGHT = 64;
	private static final int STAR_U = 0;
	private static final int STAR_V = 16;
	private static final int STAR_SIZE = 5;
	/** The star sits just inside the slot's top-left corner. */
	private static final int STAR_OFFSET = 1;
	private static final int BUTTON_WIDTH = 15;
	private static final int BUTTON_HEIGHT = 16;

	/** How much shorter the search bar gets when the lock button is shown. */
	private static final int SEARCH_BAR_SHRINK = BUTTON_WIDTH + 2;
	/** Create's search text field is 100 px wide. */
	private static final int SEARCH_BOX_WIDTH = 100;

	// Coordinates in Create's stock_keeper.png. The header is drawn at guiLeft - 15, so a texture
	// column t lands on screen at guiLeft - 15 + t; rows map 1:1 onto guiTop + row.
	private static final int TEX_OFFSET_X = -15;
	private static final int TEX_ROW_TOP = 17; // search bar rows 17..34
	private static final int TEX_ROWS = 18;
	private static final int TEX_BAR_END_FROM = 186; // last 8 columns of the bar, its right edge at 191
	private static final int TEX_BAR_END_WIDTH = 8;
	private static final int TEX_DOT_FROM = 194; // the dot right of the bar, 4 px wide
	private static final int TEX_DOT_WIDTH = 4;
	private static final int TEX_PLAIN_COLUMN = 198; // a plain wood column between dot and lock
	private static final int TEX_PATCH_FROM = 175; // first column that moves when the bar shrinks
	private static final int TEX_PATCH_TO = 200;
	private static final int TEX_LOCK_X = 201; // Create's lock button (lockX = guiLeft + 186)

	private boolean isAdmin() {
		return accessor().stockkeeperplus$isAdmin();
	}

	private int sortButtonX() {
		int texX = isAdmin() ? TEX_LOCK_X - SEARCH_BAR_SHRINK : TEX_LOCK_X;
		return getGuiLeft() + TEX_OFFSET_X + texX;
	}

	/** Same row as Create's sync and lock buttons (besideSearchButtonY = guiTop + 18). */
	private int sortButtonY() {
		return getGuiTop() + 18;
	}

	private void layoutSearchBox() {
		searchBox.setWidth(isAdmin() ? SEARCH_BOX_WIDTH - SEARCH_BAR_SHRINK + 3 : SEARCH_BOX_WIDTH);
	}

	/** Same hit test Create uses for its sync and lock buttons. */
	private boolean isSortButtonHovered(double mouseX, double mouseY) {
		int x = sortButtonX();
		int y = sortButtonY();
		return mouseX > x && mouseX <= x + BUTTON_WIDTH && mouseY > y && mouseY <= y + BUTTON_HEIGHT - 1;
	}

	/** Draws the shortened search bar end, the sort button and the stars inside Create's scrolling item window. */
	private void renderSearchRow(GuiGraphics graphics, float scroll) {
		int x = getGuiLeft();
		int y = getGuiTop();
		// Same scissor and scroll offset Create uses for its search bar and buttons.
		graphics.enableScissor(x + 16, y + 17, x + 215, y + imageHeight - 80);
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(0, -scroll * ROW_HEIGHT, 0);

		if (isAdmin())
			renderShortSearchBarEnd(graphics, x, y);
		renderFavoriteStars(graphics, scroll);

		if (!gaugeMode) { // sorting only applies to the item list
			int u = ClientConfig.sortMode()
				.ordinal() * 16;
			graphics.blit(BUTTONS, sortButtonX(), sortButtonY(), u, 0, BUTTON_WIDTH, BUTTON_HEIGHT,
				BUTTONS_TEXTURE_WIDTH, BUTTONS_TEXTURE_HEIGHT);
		}

		graphics.pose()
			.popPose();
		graphics.disableScissor();
	}

	private void renderShortSearchBarEnd(GuiGraphics graphics, int x, int y) {
		int screenY = y + TEX_ROW_TOP;
		// 1. Cover the old bar end and dot with plain wood.
		for (int t = TEX_PATCH_FROM; t <= TEX_PATCH_TO; t++)
			blitCreate(graphics, x + TEX_OFFSET_X + t, screenY, TEX_PLAIN_COLUMN, 1);
		// 2. Redraw the bar's right end and the dot, shifted left.
		blitCreate(graphics, x + TEX_OFFSET_X + TEX_BAR_END_FROM - SEARCH_BAR_SHRINK, screenY, TEX_BAR_END_FROM,
			TEX_BAR_END_WIDTH);
		blitCreate(graphics, x + TEX_OFFSET_X + TEX_DOT_FROM - SEARCH_BAR_SHRINK, screenY, TEX_DOT_FROM, TEX_DOT_WIDTH);
	}

	private void blitCreate(GuiGraphics graphics, int screenX, int screenY, int texX, int width) {
		graphics.blit(CREATE_STOCK_KEEPER, screenX, screenY, texX, TEX_ROW_TOP, width, TEX_ROWS, 256, 256);
	}

	private void renderSortTooltip(GuiGraphics graphics, int mouseX, int mouseY, float scroll) {
		// Create shows its own button tooltips while the scroll is below 1 row.
		if (scroll >= 1 || !isSortButtonHovered(mouseX, mouseY))
			return;
		SortMode mode = ClientConfig.sortMode();
		graphics.renderComponentTooltip(font, List.of(
			Component.translatable("stockkeeperplus.sort.title", Component.translatable(mode.translationKey())),
			Component.translatable("stockkeeperplus.sort.hint")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)),
			mouseX, mouseY);
	}

	private boolean clickSortButton(double mouseX, double mouseY, int button) {
		if (itemScroll.getChaseTarget() != 0 || !isSortButtonHovered(mouseX, mouseY))
			return false;
		boolean back = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
		if (!back && button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return false;
		SortMode current = ClientConfig.sortMode();
		ClientConfig.setSortMode(back ? current.previous() : current.next());
		rebuildList();
		playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1, 1);
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Exact counts in tooltips
	// ---------------------------------------------------------------------------------------------

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		findTooltipEntry(mouseX, mouseY);
		try {
			super.renderForeground(graphics, mouseX, mouseY, partialTicks);
		} finally {
			tooltipEntry = null;
		}
		if (renderTabTooltip(graphics, mouseX, mouseY))
			return;
		if (gaugeMode) {
			gaugeTab.renderTooltip(graphics, mouseX, mouseY);
			return;
		}
		renderCategoryButtonTooltip(graphics, mouseX, mouseY, partialTicks);
		renderSortTooltip(graphics, mouseX, mouseY, itemScroll.getValue(partialTicks));
	}

	private void findTooltipEntry(int mouseX, int mouseY) {
		tooltipEntry = null;
		Couple<Integer> hovered = accessor().stockkeeperplus$getHoveredSlot(mouseX, mouseY);
		if (hovered == accessor().stockkeeperplus$getNoneHovered())
			return;

		int category = hovered.getFirst();
		int slot = hovered.getSecond();
		if (category == -1) {
			if (slot >= 0 && slot < itemsToOrder.size()) {
				tooltipEntry = itemsToOrder.get(slot);
				tooltipEntryIsOrder = true;
			}
		} else if (category >= 0 && category < displayedItems.size()) {
			List<BigItemStack> items = displayedItems.get(category);
			if (slot >= 0 && slot < items.size()) {
				tooltipEntry = items.get(slot);
				tooltipEntryIsOrder = false;
			}
		}
		// category == -2 is the recipe (blueprint) row: Create already shows its own tooltip there.
	}

	/** Called from {@link TooltipHandler} while Create renders the tooltip of a hovered entry. */
	void appendCountTooltip(ItemStack stack, List<Component> lines) {
		BigItemStack entry = tooltipEntry;
		// Create passes entry.stack itself to renderTooltip, so identity tells us it is our entry.
		if (entry == null || entry.stack != stack || lines.isEmpty())
			return;

		int maxStack = stack.getMaxStackSize();
		long inStock = tooltipEntryIsOrder
			? menu.contentHolder.getLastClientsideStockSnapshotAsSummary()
				.getCountOf(stack)
			: entry.count;
		long inCart = tooltipEntryIsOrder ? entry.count : countInCart(stack);

		int insertAt = 1; // right below the item name
		lines.add(insertAt++, countLine("stockkeeperplus.tooltip.in_stock", inStock, maxStack));
		if (inCart > 0)
			lines.add(insertAt, countLine("stockkeeperplus.tooltip.in_cart", inCart, maxStack));
	}

	private long countInCart(ItemStack stack) {
		for (BigItemStack ordered : itemsToOrder)
			if (ItemStack.isSameItemSameComponents(ordered.stack, stack))
				return ordered.count;
		return 0;
	}

	private static Component countLine(String key, long count, int maxStack) {
		Component value = Component.literal(CountFormat.exactWithStacks(count, maxStack))
			.withStyle(ChatFormatting.WHITE);
		return Component.translatable(key, value)
			.withStyle(ChatFormatting.GRAY);
	}

	// ---------------------------------------------------------------------------------------------
	// Keyboard shortcuts
	// ---------------------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (KeyBindings.matches(KeyBindings.FOCUS_SEARCH, keyCode, scanCode)) {
			focusSearch();
			return true;
		}

		// Esc clears a non-empty search first; a second Esc closes the screen as usual.
		// While the address box is focused, Esc keeps its normal job (closing the suggestions).
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && !addressBox.isFocused() && !searchBox.getValue()
			.isEmpty()) {
			clearSearch();
			return true;
		}

		// Inside a text box Ctrl+Delete deletes a word, so leave it to the text box there.
		if (!searchBox.isFocused() && !addressBox.isFocused()
			&& KeyBindings.matches(KeyBindings.CLEAR_CART, keyCode, scanCode)) {
			clearCart();
			return true;
		}

		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void focusSearch() {
		addressBox.setFocused(false);
		searchBox.setFocused(true);
		searchBox.moveCursorToEnd(false);
		searchBox.setHighlightPos(0); // select everything so typing replaces the old search
		accessor().stockkeeperplus$setIgnoreTextInput(true); // swallow a stray 'f' character
	}

	private void clearSearch() {
		searchBox.setValue("");
		refreshSearchNextTick = true;
		moveToTopNextTick = true;
		accessor().stockkeeperplus$syncJEI(false);
	}

	private void clearCart() {
		if (itemsToOrder.isEmpty() && recipesToOrder.isEmpty())
			return;
		itemsToOrder.clear();
		recipesToOrder.clear();
		accessor().stockkeeperplus$updateCraftableAmounts();
		// Same sounds Create plays when an item is removed from the order.
		playUiSound(SoundEvents.WOOL_STEP, 0.75f, 1.8f);
		playUiSound(SoundEvents.BAMBOO_WOOD_STEP, 0.75f, 1.8f);
	}

	// ---------------------------------------------------------------------------------------------
	// Collapse all / expand all categories
	// ---------------------------------------------------------------------------------------------
	// Two small buttons on the right side of the first category header. Like Create's own header
	// buttons (JEI sync, lock), they scroll together with the item list.

	private static final int ROW_HEIGHT = 20; // Create's rowHeight
	private static final int COLUMNS = 9; // Create's cols
	/** Two 8px triangles drawn 3px apart, to read as "all". */
	private static final int DOUBLE_ICON_WIDTH = 11;
	private static final int ICON_HEIGHT = 8;
	private static final int BUTTON_GAP = 3;
	private static final int BUTTON_PADDING = 1;

	private enum CategoryButton {
		COLLAPSE_ALL, EXPAND_ALL
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		if (this == minecraft.screen)
			renderTabs(graphics); // behind the window, so its left edge overlaps the tabs
		super.renderBg(graphics, partialTicks, mouseX, mouseY);
		if (this != minecraft.screen)
			return; // same guard as Create: nothing is drawn during the Ponder fade-out
		float scroll = itemScroll.getValue(partialTicks);
		renderCategoryButtons(graphics, mouseX, mouseY, scroll);
		renderSearchRow(graphics, scroll);
		if (gaugeMode)
			gaugeTab.render(graphics, mouseX, mouseY);
	}

	/**
	 * Screen Y of the first visible category header icon at the given scroll, or null if there are no
	 * categories or the header is scrolled out of the item window.
	 */
	@Nullable
	private Integer firstCategoryHeaderY(float scroll) {
		if (categories.isEmpty())
			return null;
		for (int i = 0; i < displayedItems.size() && i < categories.size(); i++) {
			if (displayedItems.get(i)
				.isEmpty())
				continue;
			CategoryEntryAccessor entry = (CategoryEntryAccessor) categories.get(i);
			// Create draws the header icon at itemsY + categoryY + 6, shifted up by the scroll.
			int y = Math.round(accessor().stockkeeperplus$getItemsY() + entry.stockkeeperplus$getY() + 6
				- scroll * ROW_HEIGHT);
			// Same item window as Create's scissor: from guiTop + 17 to guiTop + windowHeight - 80.
			if (y < getGuiTop() + 17 || y + ICON_HEIGHT > getGuiTop() + imageHeight - 80)
				return null;
			return y;
		}
		return null;
	}

	private int categoryButtonX(CategoryButton button) {
		int right = accessor().stockkeeperplus$getItemsX() + COLUMNS * 20 - 3;
		int expandX = right - DOUBLE_ICON_WIDTH;
		return button == CategoryButton.EXPAND_ALL ? expandX : expandX - BUTTON_GAP - DOUBLE_ICON_WIDTH;
	}

	@Nullable
	private CategoryButton categoryButtonAt(double mouseX, double mouseY, float scroll) {
		Integer y = firstCategoryHeaderY(scroll);
		if (y == null)
			return null;
		if (mouseY < y - BUTTON_PADDING || mouseY >= y + ICON_HEIGHT + BUTTON_PADDING)
			return null;
		for (CategoryButton button : CategoryButton.values()) {
			int x = categoryButtonX(button);
			if (mouseX >= x - BUTTON_PADDING && mouseX < x + DOUBLE_ICON_WIDTH + BUTTON_PADDING)
				return button;
		}
		return null;
	}

	private void renderCategoryButtons(GuiGraphics graphics, int mouseX, int mouseY, float scroll) {
		Integer y = firstCategoryHeaderY(scroll);
		if (y == null)
			return;
		CategoryButton hovered = categoryButtonAt(mouseX, mouseY, scroll);
		for (CategoryButton button : CategoryButton.values()) {
			int x = categoryButtonX(button);
			if (button == hovered)
				graphics.fill(x - BUTTON_PADDING, y - BUTTON_PADDING, x + DOUBLE_ICON_WIDTH + BUTTON_PADDING,
					y + ICON_HEIGHT + BUTTON_PADDING, 0x40FFFFFF);
			AllGuiTextures icon = button == CategoryButton.COLLAPSE_ALL ? AllGuiTextures.STOCK_KEEPER_CATEGORY_HIDDEN
				: AllGuiTextures.STOCK_KEEPER_CATEGORY_SHOWN;
			icon.render(graphics, x, y);
			icon.render(graphics, x + DOUBLE_ICON_WIDTH - 8, y);
		}
	}

	private void renderCategoryButtonTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		CategoryButton hovered = categoryButtonAt(mouseX, mouseY, itemScroll.getValue(partialTicks));
		if (hovered == null)
			return;
		String key = hovered == CategoryButton.COLLAPSE_ALL ? "stockkeeperplus.gui.collapse_all"
			: "stockkeeperplus.gui.expand_all";
		graphics.renderComponentTooltip(font, List.of(Component.translatable(key)), mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickTab(mouseX, mouseY))
			return true;
		if (gaugeMode) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && gaugeTab.mouseClicked(mouseX, mouseY))
				return true;
			if (gaugeTab.isOverPanel(mouseX, mouseY))
				return true; // nothing of the hidden item grid may react
			return super.mouseClicked(mouseX, mouseY, button);
		}
		if (clickSortButton(mouseX, mouseY, button))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hasAltDown() && toggleFavoriteUnderMouse(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && itemScroll.settled()) {
			CategoryButton clicked = categoryButtonAt(mouseX, mouseY, itemScroll.getChaseTarget());
			if (clicked != null) {
				setAllCategoriesCollapsed(clicked == CategoryButton.COLLAPSE_ALL);
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private void setAllCategoriesCollapsed(boolean collapse) {
		Set<Integer> hidden = accessor().stockkeeperplus$getHiddenCategories();
		if (collapse) {
			for (CategoryEntry entry : categories)
				hidden.add(((CategoryEntryAccessor) entry).stockkeeperplus$getTargetBECategory());
		} else {
			hidden.clear();
		}
		// Create rebuilds the category list (and saves the hidden set when the screen closes).
		refreshSearchNextTick = true;
		moveToTopNextTick = false;
		// Same sounds Create plays when a single category is collapsed / expanded.
		playUiSound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, collapse ? 1.5f : 0.675f);
	}

	// ---------------------------------------------------------------------------------------------
	// Side tabs: Stock | Factory Gauges
	// ---------------------------------------------------------------------------------------------
	// Two pixel-art tabs sticking out of the window's left edge, below the title banner. The window is
	// drawn over their right end, so they look attached to it. The last used tab is remembered while
	// the game runs.

	private static final int TAB_WIDTH = 24;
	private static final int TAB_HEIGHT = 22;
	private static final int TAB_SPACING = 24;
	private static final int TAB_V = 32;
	private static final int TAB_INACTIVE_U = 0;
	private static final int TAB_ACTIVE_U = 24;

	private static boolean lastTabWasGauges;

	private final GaugeTab gaugeTab;
	private boolean gaugeMode;

	private int tabX() {
		return getGuiLeft() - 14;
	}

	private int tabY(int index) {
		return getGuiTop() + 22 + index * TAB_SPACING;
	}

	/** 0 = Stock, 1 = Factory Gauges, -1 = none. */
	private int tabAt(double mouseX, double mouseY) {
		// The rightmost column is hidden under the window's edge.
		if (mouseX < tabX() || mouseX >= tabX() + TAB_WIDTH - 1)
			return -1;
		for (int i = 0; i < 2; i++)
			if (mouseY >= tabY(i) && mouseY < tabY(i) + TAB_HEIGHT)
				return i;
		return -1;
	}

	private void renderTabs(GuiGraphics graphics) {
		for (int i = 0; i < 2; i++) {
			boolean active = (i == 1) == gaugeMode;
			graphics.blit(BUTTONS, tabX(), tabY(i), active ? TAB_ACTIVE_U : TAB_INACTIVE_U, TAB_V, TAB_WIDTH, TAB_HEIGHT,
				BUTTONS_TEXTURE_WIDTH, BUTTONS_TEXTURE_HEIGHT);
			graphics.renderItem(i == 0 ? AllBlocks.ITEM_VAULT.asStack() : AllBlocks.FACTORY_GAUGE.asStack(), tabX() + 4,
				tabY(i) + 3);
		}
	}

	private boolean renderTabTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		int tab = tabAt(mouseX, mouseY);
		if (tab < 0)
			return false;
		graphics.renderComponentTooltip(font, List.of(Component.translatable(
			tab == 0 ? "stockkeeperplus.tab.stock" : "stockkeeperplus.tab.gauges")), mouseX, mouseY);
		return true;
	}

	private boolean clickTab(double mouseX, double mouseY) {
		int tab = tabAt(mouseX, mouseY);
		if (tab < 0)
			return false;
		setGaugeMode(tab == 1);
		return true;
	}

	private void setGaugeMode(boolean gauges) {
		if (gaugeMode == gauges)
			return;
		gaugeMode = gauges;
		if (gauges) {
			itemScroll.startWithValue(0);
			gaugeTab.refreshNow();
		}
		playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1, 1);
	}

	@Override
	public boolean stockkeeperplus$coversItemGrid() {
		return gaugeMode;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (gaugeMode) {
			if (gaugeTab.mouseScrolled(mouseX, mouseY, scrollY))
				return true;
			// Outside the gauge list only the order row and the address box react to the wheel.
			Couple<Integer> hovered = accessor().stockkeeperplus$getHoveredSlot((int) mouseX, (int) mouseY);
			if (hovered == accessor().stockkeeperplus$getNoneHovered())
				return addressBox.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// --- Called by GaugeTab ---------------------------------------------------------------------

	String searchText() {
		return searchBox.getValue();
	}

	@Nullable
	UUID tickerNetwork() {
		return menu.contentHolder.behaviour == null ? null : menu.contentHolder.behaviour.freqId;
	}

	int windowWidth() {
		return imageWidth;
	}

	int windowHeight() {
		return imageHeight;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (gaugeMode && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && gaugeTab.mouseDragged(mouseX, mouseY))
			return true;
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && gaugeTab.mouseReleased())
			return true;
		return super.mouseReleased(mouseX, mouseY, button);
	}
}
