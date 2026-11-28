# Changelog

All notable changes to Stock Keeper Plus. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- The search text is kept when the screen closes, separately for every Stock Ticker, like the order.
- Config options (Mods list > Stock Keeper Plus > Config, or `config/stockkeeperplus-client.toml`):
  remember the order, remember the search, favorites, exact counts in tooltips, numpad Enter, the Factory Gauges
  and Storage tabs, the storage bar and the gauge map animations.
- The config screen is translated, and the sort order shows its name instead of its internal id.

### Changed
- `Esc` no longer clears the search first: it closes the screen right away, as in Create.
- Storage tab: each row shows the container name on its own line, with the fill bar and slot count below it,
  so long names are no longer cut short.

### Fixed
- An Item Vault that was only partly loaded when first read could be counted with fewer slots than it has,
  so the storage bar and the totals jumped around and could pass 100%.
- The storage bar under the order now uses the server's data on every tab, like the Storage tab, instead of
  only what the client has loaded.

## [0.2.0] - 2026-11-27

First public release, for Minecraft 1.21.1, NeoForge 21.1.219+ and Create 6.0.10.

### Added
- Sorting button next to the search bar: quantity high to low, low to high, name, mod then name.
- Favorites (`Alt` + click) with their own category at the top and a star on the slot.
- The order is saved when the screen closes, per Stock Ticker.
- Exact counts in item tooltips, with stacks written as on Create's clipboards.
- Collapse all / expand all categories.
- Factory Gauges tab: 2D map of the network's gauges with Create's arrows and request animations.
- Storage tab: one row per container or named group, fill levels, contents on hover (server side needed).
- Storage fill bar under the order slots.
- Keys: `Ctrl+F` search, `Esc` clears the search, `Ctrl+Delete` clears the order, numpad `Enter` sends it.
- JEI recipe row usable on every tab.
- Optional server part: container contents and gauges or containers outside the client's loaded chunks.
- English and Italian translations.

[Unreleased]: https://github.com/CrashBugITA/stock-keeper-plus/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/CrashBugITA/stock-keeper-plus/releases/tag/v0.2.0
