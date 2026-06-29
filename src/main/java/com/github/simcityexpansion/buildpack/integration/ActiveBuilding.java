package com.github.simcityexpansion.buildpack.integration;

import java.nio.file.Path;

/**
 * Plain description of a build-pack building made available to SimuKraft <b>virtually</b> — i.e.
 * without copying any file into SimuKraft's {@code simukraftbuilding/} directory. The fields mirror
 * what SimuKraft's {@code BuildingCatalog.BuildingDefinition} (server) and
 * {@code BuildingCacheService.BuildingMeta} (client) need; {@code metaPath}/{@code structurePath}
 * point at this mod's own managed cache.
 *
 * <p>Intentionally free of any SimuKraft reference so it (and {@link ActivePackProvider}) load even
 * when SimuKraft is not installed.
 *
 * @param category normalized lowercase category (residential/commercial/industry/public/other)
 * @param displayName name shown in SimuKraft's build menu
 * @param size human-readable size string (e.g. {@code 9 x 5 x 9}); SimuKraft only displays it
 * @param amount construction price string SimuKraft parses (e.g. {@code 8.64元})
 * @param author author credit
 * @param metaFileName the {@code .sk} file name SimuKraft keys the building by (must be unique)
 * @param structureFileName the structure file name (must be unique; e.g. {@code <packId>__<name>.nbt})
 * @param metaPath on-disk path to the {@code .sk} in this mod's cache
 * @param structurePath on-disk path to the converted {@code .nbt} in this mod's cache
 */
public record ActiveBuilding(
    String category,
    String displayName,
    String size,
    String amount,
    String author,
    String metaFileName,
    String structureFileName,
    Path metaPath,
    Path structurePath) {}
