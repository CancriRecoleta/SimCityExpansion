package com.github.simcityexpansion.buildpack.model;

import org.jetbrains.annotations.Nullable;

/**
 * A single building entry inside a zip build pack ({@code buildings/<category>/<name>.*}).
 *
 * @param category category (derived from the path inside the zip)
 * @param name base name (without extension)
 * @param structureEntry entry path of the structure file inside the zip
 * @param format structure format
 * @param skEntry entry path of the same-base-name .sk file (optional; installed as-is when present, takes priority over metaJsonEntry)
 * @param metaJsonEntry entry path of this mod's metadata JSON (v2: {@code <name>.meta.json}; optional)
 * @param simukraftJsonEntry SimuKraft native profession/trade definition {@code <name>.json} (installed as-is; optional)
 */
public record PackBuildingEntry(
    BuildingCategory category,
    String name,
    String structureEntry,
    StructureFormat format,
    @Nullable String skEntry,
    @Nullable String metaJsonEntry,
    @Nullable String simukraftJsonEntry) {}
