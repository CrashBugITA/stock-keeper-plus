# Stock Keeper Plus

Quality-of-life improvements for **Create**'s Stock Keeper screen: sorting, favorites, a saved order,
exact counts, keyboard shortcuts, a map of the network's Factory Gauges and an overview of its storage.

**Create: Remote Terminal** opens the same screen, so everything below works there too.

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.219 or newer |
| Requires | Create 6.0.10 (6.0.x) |
| Works with | Create: Remote Terminal, JEI |
| Side | Client; optional on the server |
| Languages | English, Italian |

## Download

Get the jar from the [Releases](../../releases) page and put it in the `mods` folder. What changed in each version is in the [changelog](CHANGELOG.md).

- **Client only:** everything works except reading what is inside containers. Gauges and containers are read
  from what your client has loaded and remembered between visits.
- **Client and server** (single player counts as both): also shows the contents of each container on hover,
  and the gauges and containers the server has loaded, even far away from you.

The mod is optional on both sides: a client with it can join a server without it, and the other way round.

## Features

### Stock tab

- **Sorting** – a button next to the search bar cycles the order of the items in each category:
  quantity high to low, quantity low to high, name, mod then name. Right click goes back.
- **Favorites** – `Alt` + click an item to pin it. Favorites get their own category at the top of the list
  and a small star on their slot.
- **Saved order** – the items in the order (cart) are kept when you close the screen, separately for every
  Stock Ticker. They are still there next time you open it.
- **Saved search** – the search text is kept too, so the list opens filtered the way you left it.
- **Exact counts** – item tooltips show the exact amount in stock and in the order, with stacks the way
  Create writes them on clipboards: `12,345 | 192▤ +57`.
- **Collapse / expand all** – two buttons close or open every category at once.

### Factory Gauges tab

A 2D view of the Factory Gauge walls connected to the network, drawn like the real gauges:

- gauges with their item, the amount in storage and the target (`1/2 stacks`);
- Create's arrows between gauges, following the same routes as in the world;
- the status color and bulb of each gauge, a flash when a gauge sends a request, and moving arrows while it
  is waiting for ingredients;
- drag to move, mouse wheel to zoom; zooming out goes as far as needed to see every gauge;
- tooltips with amounts, target, incoming items, recipe address and ingredients.

Gauges that are not loaded keep the values from the last time they were seen.

### Storage tab

- One row per container on the network (Item Vaults, chests, barrels, any modded inventory behind a Stock
  Link), with its size and how full it is.
- **Groups** – the tag button on a row puts the container in a named group. Containers in the same group
  are shown as one row. Rename a group from the same button; an empty name dissolves it.
- **Contents** – hovering a row shows what is inside as item icons with amounts (needs the mod on the
  server).
- A totals header: slots used, items stored and different items.

A thin bar under the order slots shows how full the network's storage is, on every tab.

### Keys

| Key | Action |
|---|---|
| `Ctrl` + `F` | Focus the search box |
| `Ctrl` + `Delete` | Clear the order |
| Numpad `Enter` | Send the order (same as Create's `Shift` + `Enter`) |
| `Alt` + click | Add or remove a favorite |

`Ctrl+F` and `Ctrl+Delete` can be changed in Minecraft's Controls menu.

### JEI

The recipe row that JEI's `+` button adds above the order works on every tab.

## Configuration

Open it from the Mods list (Stock Keeper Plus > Config) or edit `config/stockkeeperplus-client.toml`.
Changes apply right away.

| Option | Default | What it does |
|---|---|---|
| Sort order | Quantity, high to low | Order of the items in each category (also the sort button) |
| Remember the order | On | Keep the order when the screen closes, per Stock Ticker |
| Remember the search | On | Keep the search text when the screen closes, per Stock Ticker |
| Favorites | On | `Alt` + click favorites and the Favorites category |
| Exact counts | On | Amounts in stock and in the order in item tooltips |
| Numpad Enter sends | On | Numpad `Enter` sends the order |
| Factory Gauges tab | On | Show the gauge map tab |
| Storage tab | On | Show the containers tab |
| Storage bar | On | Show the fill bar under the order slots |
| Animations | On | Request flashes and moving arrows on the gauge map |

Saved orders, searches, favorites, groups and the remembered gauges and containers are stored per world in
`config/stockkeeperplus/`.
## Building

See [stockkeeperplus/README.md](stockkeeperplus/README.md).

```
cd stockkeeperplus
./gradlew build
```

The jar ends up in `stockkeeperplus/build/libs/`.

## License

MIT, see [LICENSE](LICENSE). Parts of the code follow Create's own code (MIT, Copyright simibubi and
contributors). Create, Create: Remote Terminal and JEI belong to their authors.
