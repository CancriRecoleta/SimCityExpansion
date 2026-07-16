# Build pack manager — usage guide

Building expansion-pack support for
[New-Simukraft-1.21.1](https://github.com/New-Sim-U-Kraft/New-Simukraft-1.21.1): import
vanilla structures, Litematica projections, or WorldEdit schematics as buildable SimuKraft
buildings, and install, uninstall, and export zip packs with one click.

## Requirements

| Dependency | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233+ |
| SimuKraft (New-Simukraft) | 2.0.0-pre-5+ |

The manager is built on Minecraft's own GUI toolkit, so it needs no extra UI library.
Integration with SimuKraft is a file-system convention with no compile-time dependency.

## Quick start

1.  Press **`\`** (backslash) in a world to open the manager (rebindable under
    **Controls -> SimCity Expansion**). You can also develop packs **without entering a
    save**: on the title screen open **Mods**, select this mod, and click **Config** —
    importing, editing, packaging, and commerce/industry definitions all work offline
    (only "Capture selection" needs a loaded world).
2.  **Drag building files straight into the window**, or click "Open folder" and drop them
    into `<game dir>/simcity_expansion/import/` (subdirectories are shown as a tree).
3.  On the **Import Files** tab, select a file, fill in the install info on the right
    (category, price, and so on), then click "Install into SimuKraft".
4.  Enter SimuKraft's build flow (the build box) to find and construct the building.

## Supported structure formats

| Format | Source | Notes |
| --- | --- | --- |
| `.nbt` | Vanilla structure block export | Installed as-is; old versions auto-upgraded |
| `.litematic` | Litematica (projection) | Multi-region merge into one bounding box; reads embedded name / author / creation time / thumbnail |
| `.schem` | WorldEdit / Sponge (v1/v2/v3) | Converted automatically |
| `.schematic` | MCEdit era | **Not supported** (numeric block IDs are obsolete) |

Every non-vanilla format is **converted to vanilla `.nbt`** on install — SimuKraft's build
system only reads the vanilla format.

Automatic processing on install:

- **Version upgrade** — older structures (for example 1.18/1.20) are upgraded to the
  current version through the game's own DataFixer without losing block states; structures
  from a newer version cannot be downgraded and produce a warning.
- **Missing-block detection** — when a structure uses blocks from a mod you do not have,
  the status bar lists the missing entries; those positions become air when built and are
  marked in magenta in the top-down preview.
- **structure_void stripped** — SimuKraft places structure void as a real block (invisible
  but occupying, replacing terrain), so install/activate strips it automatically and says so.
- **Block entities preserved** — chest contents, sign text, and the like are kept with the
  structure file (for litematic / Create round-trips); note that SimuKraft **discards** them
  when building (chests come out empty). **Entities (mobs, armor stands, item frames) are
  now preserved through editing and transforms** as well.
- Structures over 16,000,000 blocks after merging are rejected; over 2,000,000 blocks warn
  that building will take a while.
- **Install check-up** — every install runs the doctor (next section); error/warning counts
  show in the status bar.

## The interface

```
Build Pack Manager
[Import Files] [Packs] [Installed]           <- tabs (hover for a tooltip)
[Search......] [Sort v]    | name / author / size / block count ...
v directory tree           | [preview: embedded thumbnail or top-down]
   house.litematic         | - install form (category/name/price/author/desc/tags/job) -
   shop.nbt                | - material list (counted by block type, missing in red) -
[Install][Install All][Uninstall][Delete File][Export as Pack]
[Refresh][Open Folder]   count . message              [Close]
```

- **Three tabs** — Import Files (loose files), Packs (zip), Installed (the current state of
  the `simukraftbuilding/*.zip` building packages, including SimuKraft's official package;
  since SimuKraft 2.0 only zip packages are read).
- With the Create mod installed (or a `schematics/` directory present), the Import tab
  gains a **Create schematics** branch listing the blueprints under `schematics/`
  (including server uploads in `uploaded/`), ready to preview and install.
- **Install form** — each row has a tooltip describing the field's format; size is computed
  automatically; author defaults to the current player name; ticking "overwrite same-name
  building" replaces a same-name file, otherwise it is auto-renamed `_2`.
- **Install All** — batch-installs every file in the current (search-filtered) list into
  the category currently selected in the form.
- **Delete File** — click twice to confirm; only affects loose files in the import
  directory.
- **Open Folder** — context-aware: the Installed tab opens `simukraftbuilding/`, the others
  open the import directory.
- **Sort** — by name, by modified time (newest first), or by size (largest first).

## Zip build packs

- **Browse directly** — on the Packs tab a zip is an expandable tree (pack -> category ->
  building); selecting any building inside reads it **straight from the zip** (no
  extraction) and shows its summary, preview, material list, and metadata.
- **Install the whole pack** — select the pack node, then Install. Every building is
  normalized (structures converted to `.nbt`, a `.sk` guaranteed) into
  `simukraftbuilding/sce_pack_<packid>.zip`, and the install manifest is recorded in
  `simcity_expansion/installed_packs.json`.
- **Install one building** — select a single building inside the pack and Install just that
  one (written into this mod's managed `simukraftbuilding/sce_local.zip`; it can be
  uninstalled individually from the Installed tab).
- **Uninstall** — select an installed pack, then Uninstall; the pack's
  `sce_pack_<packid>.zip` is deleted (legacy installs migrated into `sce_local.zip` have
  their entries removed by the manifest).
- **Export** — Installed tab -> "Export as Pack" packages the listed buildings (respecting
  the search filter) into `<game dir>/simcity_expansion/export/`, ready to share; the
  recipient drops it back into the import directory to install. You can optionally
  **include an icon** (tick the box and leave the path blank to auto-generate from the
  first building's preview, or enter a local PNG absolute path), and an `index.json` file
  manifest (each file's size and SHA-256) is written alongside. A pack's icon is shown in
  the preview area when you browse it.

See [pack-format.en.md](pack-format.en.md) for the pack specification.

## Check-up and layout preview

With any structure-bearing entry selected, the "**Check-up**" button on the right audits the
building against SimuKraft's silent-failure rules and opens a report:

- residential buildings without **red bed** heads (only red beds count as homes upstream);
- `amount` parsing traps (`1,000` silently becomes `1.0` upstream; garbage becomes 0 = free);
- explicit air clears terrain when built; structure_void is placed as a real block (the
  upstream preview filters both, so you cannot see it there);
- chest/sign contents are discarded by the builder;
- `poi:` line syntax (unknown types silently become OTHER; a non-integer capacity drops the
  line) plus a **landing simulation** (block-name match → control box → origin);
- `size` field mismatches, missing blocks, and the mod-dependency list.

The report's "**Layout preview**" highlights red-bed homes, POI landings, the control box,
and the definition's points/containers/output containers as colored boxes in 3D (with a
legend). The definition editor has the same button, and its "Validate" now also checks that
container coordinates point at actual container blocks and that `type` is `structure_pos`.

The install form gains a "**POI**" row for editing `poi: TYPE, capacity[, id]` declarations
visually — no more hand-editing .sk text inside the zip.

## New editor tools

- **Family swap** — replace a word root across block ids (`oak → spruce` converts planks,
  stairs, doors, and fences in one step, keeping orientation properties; `dark_oak` is safe
  from `oak`), optionally scoped to the selection.
- **Props…** — edit the paint block's state properties (facing/half/shape/waterlogged);
  hotkey `5` cycles the paint facing. Previously only the eyedropper could produce
  non-default states.
- **Array…** — clone the selection N times at a fixed offset (walls, floors, window columns);
  the default offset equals the selection width.
- **Alt+Arrows/PgUp/PgDn** — move the selection *contents* (plain arrows move only the box).
- **2D layer view** — top-down per-layer editing (scroll changes layer, Ctrl+scroll zooms,
  right-drag pans, left click applies the current tool); ideal for interiors and very large
  structures.
- Copy/cut/paste and the symmetry axis now have buttons (previously hotkey-only); all
  transforms (rotate/mirror/crop/…) now **carry entities along** (positions, yaw, hanging
  entity tiles).

## Test placement and iteration

- **`/buildpack testbuild <source> [pos]`** places a structure straight into the world for a
  visual check (source is an import file or `installed/<category>/<base>`), with SimuKraft's
  exact semantics (air clears terrain, missing blocks skipped, chests empty).
  **`/buildpack testbuild undo`** restores the covered terrain (one undo slot per player).
  Capped at 1,000,000 blocks.
- **Update structure from selection** — after tweaking a building in the world, select the
  new bounds with `[` `]` and right-click the building on the Installed tab → "Update
  structure from selection": only the .nbt is replaced (and the size line rewritten); the
  .sk metadata and definitions stay untouched, with a hot reload at the end.

## Create schematic interop

Create schematics are plain vanilla structure NBT (`.nbt`) — the same format SimuKraft
builds from — so conversion works both ways (pure file-system convention, Create is not a
dependency):

- **Import** — blueprints under `schematics/` appear in the Import tab's "Create
  schematics" branch and install like any structure file.
- **Export** —
  - right-click any structure file on the Import tab (`.litematic`/`.schem` are converted
    to `.nbt` first) or any installed building with a structure → "Export as Create
    schematic";
  - the structure editor's "Export Create schematic" button;
  - `/buildpack capture <from> <to> <name> create` writes the selection straight into
    `schematics/`.
- Exports replace `structure_void` with air (matching Create's own schematic saves), so
  the result is ready for the schematic table and schematicannon.

## Source labels on the Installed tab

| Label | Meaning |
| --- | --- |
| Package: xxx.zip | The zip building package the building lives in |
| Source: installed by this mod | Lives in one of this mod's managed `sce_*.zip` packages; removable/recategorizable |
| Source: pack xxx | Installed as part of a zip build pack (tracked in the registry); removable |
| Source: external (read-only) | Lives in SimuKraft's official package or a hand-placed zip; this mod will not modify it |

## Multiplayer and server commands

The interface's file operations happen in **your local game directory**: they take effect
directly in single-player and for a LAN host. When connected to a dedicated server the
interface warns you that buildings must be installed **on the server**.

Install the mod on a dedicated server (the interface code simply does not load there) and
an administrator can install directly with the `/buildpack` command — put files into the
**server's** `simcity_expansion/import/` directory, then:

| Command | Effect |
| --- | --- |
| `/buildpack list` | List the structure files and zip packs in the server import directory |
| `/buildpack install <file> [category] [name]` | Install a loose file (category defaults to `other`; quote names with spaces; a `schematics/` prefix installs a Create blueprint directly) |
| `/buildpack installpack <zip>` | Install a zip pack |
| `/buildpack packs` | List installed packs |
| `/buildpack uninstallpack <id>` | Uninstall a pack by id from the registry |
| `/buildpack migrate` | Pack legacy (pre-SimuKraft-2.0) loose files under `simukraftbuilding/<category>/` into `sce_local.zip` (originals backed up to `legacy_backup/`; also runs automatically on server start and when the manager opens) |
| `/buildpack testbuild <source> [pos]` | Test placement: put the structure into the world to see the real result (import files and `installed/<category>/<base>`) |
| `/buildpack testbuild undo` | Restore the terrain covered by the last test placement (one slot per player) |

- Requires operator permission level 2; file names, categories, and pack ids all have
  tab-completion.
- Uses the **exact same** conversion pipeline as the client interface (format conversion,
  DataVersion upgrade, missing-block warnings).
- The commands also work in single-player.
- If you would rather not install the server mod: install locally, then copy the
  `sce_*.zip` packages under `simukraftbuilding/` to the same directory on the server
  (SimuKraft 2.0 reads zip packages directly; run `/simukraft reload` on the server to
  pick them up).

## Troubleshooting

- **Nobody moves into my residential building** — homes only count **red bed**
  (`minecraft:red_bed`) heads; other bed colors are decoration. The check-up reports this.
- **Built with missing blocks / all air** — check for a "missing N block types" warning at
  install time; install the corresponding mod and reinstall.
- **"Invalid build pack"** — the zip is missing `pack.json`, its format version is
  unsupported, or there are no structure files under `buildings/`; the status bar gives the
  specific reason.
- **"Structure is from a newer game version"** — higher-version structures cannot be
  downgraded; re-export them on the matching version.
- **The interface will not open** — make sure the key is not in conflict (search "build" in
  Controls) and check the log for errors.
