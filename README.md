# Stock Keeper Plus

Client-side improvements for the **Create 6.0.10** Stock Keeper screen (Minecraft 1.21.1, NeoForge).
Create: Remote Terminal opens the same screen, so it gets the improvements too. The server does not need this mod.

## Features

- Exact amounts in item tooltips (`12,345 | 192▤ +57`), for stock and cart.
- Shortcuts: `Ctrl+F` focuses the search, `Esc` clears it first, `Ctrl+Delete` clears the order (rebindable).
- Collapse all / expand all categories.
- Sort button next to the search bar: quantity high to low, low to high, name, mod.
- Favorites (`Alt+click`): a pinned Favorites category and a small star on the slot.
- The order (cart) is kept when the screen closes, per Stock Ticker.
- Factory Gauges tab: a 2D map of the network's gauge walls with Create's arrows; drag to move, scroll to zoom.

## Layout

| Path | What |
|---|---|
| `stockkeeperplus/` | The mod (Gradle, NeoForge ModDevGradle). See its README to build and run. |
| `modpack/StockKeeperDev-0.1.0.mrpack` | Modrinth pack with Create, Create: Remote Terminal and JEI for testing. |
| `PIANO.md` | Implementation plan and status (Italian). |

Not committed, re-creatable locally: `sources/` (clones of Create and Create: Remote Terminal for reference),
`mods/` (release jars), `stockkeeperplus/libs/` and `stockkeeperplus/run/` (downloaded by `./gradlew setupLibs`).
