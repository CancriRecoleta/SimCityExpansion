# Build pack format and authoring guide (v2)

For pack authors: how to package a set of buildings into a zip that the build pack manager
installs into SimuKraft (New-Simukraft-1.21.1) with one click.

## Overview

```
my_pack.zip
├── pack.json                             manifest (required)
├── icon.png                              pack icon (optional, referenced by pack.json "icon")
├── index.json                            file manifest (auto-generated on export; size + SHA-256)
└── buildings/
    ├── residential/                      five fixed category directories (see below)
    │   ├── small_house.litematic         structure file (required per building; pick one)
    │   ├── small_house.sk                SimuKraft metadata (optional, highest priority)
    │   ├── small_house.meta.json         this mod's metadata (optional)
    │   └── small_house.json              SimuKraft native job/trade definition (optional, as-is)
    └── commercial/
        └── bakery.nbt
```

All files for one building are linked by the **same base name** and must sit directly under
`buildings/<category>/` (deeper nesting is not supported).

## pack.json

```json
{
  "format": 2,
  "id": "yourname.starter_homes",
  "name": "Starter Homes",
  "version": "1.0.0",
  "author": "YourName",
  "description": "Five small houses suited to an early game",
  "icon": "icon.png",
  "generated": "2026-06-29T12:34:56.789Z"
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `format` | Yes | Pack format version. Currently `2`; `1` is still readable (see "v1 compatibility") |
| `id` | Yes | Globally unique, suggested `author.pack_name` lowercase snake_case; the install registry and uninstall track it |
| `name` | No | Display name (falls back to id) |
| `version` / `author` / `description` | No | For display |
| `icon` | No | Pack icon file name (at the zip root, usually `icon.png`); absent means no icon |
| `generated` | No | Export timestamp (RFC 3339); informational, ignored on read |

> `format` stays `2`: `icon`, `generated`, `icon.png`, and `index.json` are all
> **backward-compatible additions** — an older manager that does not recognize them simply
> ignores them and installs as usual.

## Icon and file manifest (icon.png / index.json)

### `icon.png`

The pack icon, placed at the zip root and referenced from `pack.json` with
`"icon": "icon.png"`. The manager shows it in the info panel preview when you browse the
pack. In the export dialog:

- **Tick "Include icon" and leave the path blank** — auto-rendered from the first
  building's isometric/top-down preview.
- **Enter a local PNG absolute path** — use your own branded image (falls back to
  auto-generation when blank).

### `index.json`

A file manifest auto-generated at the zip root on export, for tracking the packed contents
and verifying integrity:

```json
{
  "kind": "simcity_expansion:pack_index",
  "format": 2,
  "packId": "export.20260629_123456",
  "generated": "2026-06-29T12:34:56.789Z",
  "icon": "icon.png",
  "totalFiles": 7,
  "files": [
    { "path": "pack.json", "type": "manifest", "size": 234, "sha256": "..." },
    { "path": "icon.png", "type": "icon", "size": 1024, "sha256": "..." },
    { "path": "buildings/residential/small_house.nbt",
      "type": "structure", "category": "residential", "size": 5012, "sha256": "..." }
  ]
}
```

`files[].type` is one of `manifest` / `icon` / `structure` / `metadata` (.sk) / `simukraft`
(native .json). The manifest lists every file except itself; `index.json` is not parsed on
install — it is there for pre-release review and integrity checks.

## Category directories

These map to the five fixed subdirectories under SimuKraft's runtime `simukraftbuilding/`
and **cannot be invented**:

`residential` . `commercial` . `industry` . `public` . `other`

## Structure files

| Extension | Notes |
| --- | --- |
| `.nbt` | Vanilla structure block template, installed as-is (old versions auto-upgraded) |
| `.litematic` | Litematica projection. Multi-region auto-merge: the union of all region bounding boxes, later regions overriding earlier ones; output includes air blocks so the interior is cleared when built |
| `.schem` | Sponge / WorldEdit schematic, v1, v2, and v3 all supported |

On install everything is converted to vanilla `.nbt` (SimuKraft's build system only parses
the vanilla format), and:

- the old `DataVersion` is upgraded to the current version through the game DataFixer
  (structures from a newer version are refused and warned about);
- block entities (chest contents, signs, and so on) are kept after coordinate remapping;
  **entities are not kept**;
- the palette is checked against the registry for missing blocks (missing entries become
  air when built, with a warning at install);
- the merged volume is capped at 16,000,000 blocks; larger is rejected.

## Metadata (pick one, by priority)

### 1. `<name>.sk` — installed as-is, highest priority

SimuKraft's line-based format, UTF-8, one `key:value` per line, `#` starts a comment:

```
name:Bakery
size:14 x 8 x 12
amount:8.64元
author:Fengye
description:Turns wheat into bread to sell to citizens
tags:price_low,material_low,stage_early
job_type:breadShopOwner
```

| Key | Notes |
| --- | --- |
| `name` | Building display name |
| `size` | `X x Y x Z` (when shipping your own .sk, match the actual structure size) |
| `amount` | Build cost, SimuKraft's built-in notation such as `8.64元` |
| `tags` | Comma-separated; common built-in values: `price_low/price_high`, `material_low/material_high`, `stage_early/stage_late` |
| `job_type` | The job id this building provides (matching the job in the native .json) |
| `poi:` lines | SimuKraft's point-of-interest extension; this mod does not generate it but **keeps it as-is** |

### 2. `<name>.meta.json` — rewritten to .sk by the manager

```json
{
  "name": "Bakery",
  "amount": "8.64元",
  "author": "Fengye",
  "description": "Turns wheat into bread",
  "tags": ["price_low", "material_low", "stage_early"],
  "job_type": "breadShopOwner"
}
```

`tags` accepts an array or a comma-separated string; `size` is not needed (computed from the
structure).

### 3. Neither provided — auto-generated

The name is taken from the file base name (litematic uses the embedded name), the size is
computed, and the rest is left blank.

## SimuKraft native `<name>.json` (job / trade / container)

Installed as-is into the building directory; the schema is defined by SimuKraft (refer to
its built-in buildings). Abbreviated example:

```json
{
  "id": "breadShop",
  "name": "Bakery",
  "job": { "id": "bread_shop_owner", "name": "Bakery Owner", "heldItem": "minecraft:bread" },
  "containers": { "input": { "type": "structure_pos", "positions": [[4,1,3]] } },
  "offers": [
    { "id": "shop_sells_bread", "visibleTo": "mixed",
      "cost": [{ "money": 0.25 }], "result": [{ "item": "minecraft:bread", "count": 1 }],
      "stock": { "item": "minecraft:bread", "max": 64,
                 "materials": [{ "item": "minecraft:wheat", "count": 3 }] } }
  ]
}
```

> `containers.positions` are structure-local coordinates — if the structure is transformed
> (litematic multi-region merge shifts it to the merged bounding-box origin), verify against
> the **converted .nbt**: install once with the manager, then adjust against
> `simukraftbuilding/<category>/<name>.nbt`.

## v1 compatibility

`format: 1` packs still install. In old packs the meaning of `<name>.json` is ambiguous and
is decided heuristically by content: if it has any of `offers` / `containers` / `job` it is
treated as a SimuKraft native definition, otherwise as metadata. New packs should use
`format: 2` and the explicit naming above rather than relying on the heuristic.

## Install output and uninstall

Each building lands under `simukraftbuilding/<category>/` as:

```
<name>.nbt          the converted structure
<name>.sk           metadata (first line carries a "# generated by SimCity Expansion" marker)
<name>.json         (if the pack provides a native definition)
```

Name collisions auto-append `_2/_3`. The whole-pack install manifest is written to
`simcity_expansion/installed_packs.json`, and uninstall removes files precisely by manifest:

```json
{ "packs": [ { "id": "yourname.starter_homes", "name": "Starter Homes",
  "version": "1.0.0", "installedAt": 1760000000000,
  "files": ["residential/small_house.nbt", "residential/small_house.sk"] } ] }
```

## Suggested workflow

1.  In-game, select a building with Litematica and export `.litematic` (or export `.nbt`
    with a structure block).
2.  Drop it into the manager's import directory, install each one and **actually let
    SimuKraft build it once**, confirming blocks, orientation, and chest contents; tune the
    price/tags/job in the form while you are at it.
3.  Installed tab -> "Export as Pack" to get a zip starting point (already containing the
    converted .nbt and .sk, plus the same-name .json).
4.  Unzip and edit `pack.json` (set your id/name/description), add native .json as needed,
    and repack.
5.  Put the finished zip back into the import directory and run a full
    install -> build -> uninstall regression test.

## Release checklist

- [ ] `pack.json` `id` is globally unique and `format: 2`
- [ ] every structure installs with no "missing block" warning on the target version
      (1.21.1), or the mod dependency is noted in the description
- [ ] buildings with trades/jobs: `job_type` (.sk) matches the job id in the native .json,
      and container coordinates are verified against the converted structure
- [ ] paths inside the zip use forward slashes `/`, file names avoid `\ / : * ? " < > |`
- [ ] tested a full SimuKraft build once
