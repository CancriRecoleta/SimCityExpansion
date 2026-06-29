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
    **Controls -> SimCity Expansion**).
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
- **Block entities preserved** — chest contents, sign text, and the like are kept with the
  structure; **entities (mobs, armor stands, and so on) are not**.
- Structures over 16,000,000 blocks after merging are rejected; over 2,000,000 blocks warn
  that building will take a while.

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
  `simukraftbuilding/`, including SimuKraft's built-in buildings).
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
  converted into the matching category directory and the install manifest is recorded in
  `simcity_expansion/installed_packs.json`.
- **Install one building** — select a single building inside the pack and Install just that
  one (not counted in the whole-pack manifest; its `.sk` carries this mod's marker so it
  can be uninstalled individually from the Installed tab).
- **Uninstall** — select an installed pack, then Uninstall; files are removed precisely by
  the manifest.
- **Export** — Installed tab -> "Export as Pack" packages the listed buildings (respecting
  the search filter) into `<game dir>/simcity_expansion/export/`, ready to share; the
  recipient drops it back into the import directory to install. You can optionally
  **include an icon** (tick the box and leave the path blank to auto-generate from the
  first building's preview, or enter a local PNG absolute path), and an `index.json` file
  manifest (each file's size and SHA-256) is written alongside. A pack's icon is shown in
  the preview area when you browse it.

See [pack-format.en.md](pack-format.en.md) for the pack specification.

## Source labels on the Installed tab

| Label | Meaning |
| --- | --- |
| Source: installed by this mod | Installed by this mod (`.sk` carries the generation marker); removable |
| Source: pack xxx | Installed as part of a zip pack; removable (tracked in the registry) |
| Source: external (read-only) | Shipped by SimuKraft or placed manually; this mod will not delete it |

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
| `/buildpack install <file> [category] [name]` | Install a loose file (category defaults to `other`; quote names with spaces) |
| `/buildpack installpack <zip>` | Install a zip pack |
| `/buildpack packs` | List installed packs |
| `/buildpack uninstallpack <id>` | Uninstall a pack by id from the registry |

- Requires operator permission level 2; file names, categories, and pack ids all have
  tab-completion.
- Uses the **exact same** conversion pipeline as the client interface (format conversion,
  DataVersion upgrade, missing-block warnings).
- The commands also work in single-player.
- If you would rather not install the server mod: install locally, then copy the artifacts
  under `simukraftbuilding/` to the same directory on the server (SimuKraft scans live, so
  a restart is usually unnecessary).

## Troubleshooting

- **Built with missing blocks / all air** — check for a "missing N block types" warning at
  install time; install the corresponding mod and reinstall.
- **"Invalid build pack"** — the zip is missing `pack.json`, its format version is
  unsupported, or there are no structure files under `buildings/`; the status bar gives the
  specific reason.
- **"Structure is from a newer game version"** — higher-version structures cannot be
  downgraded; re-export them on the matching version.
- **The interface will not open** — make sure the key is not in conflict (search "build" in
  Controls) and check the log for errors.
