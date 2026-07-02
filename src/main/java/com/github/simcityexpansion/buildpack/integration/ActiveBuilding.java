package com.github.simcityexpansion.buildpack.integration;

/**
 * Plain description of a build-pack building made available to SimuKraft <b>virtually</b> — i.e.
 * without installing anything into SimuKraft's {@code simukraftbuilding/} directory. The building's
 * files live in this mod's cache <b>zip package</b> (kept per pack by {@link ActivePackProvider});
 * a mixin splices that package into SimuKraft 2.0's package scan, so SimuKraft reads the entries
 * through its own zip pipeline. This record carries only the display metadata needed for the
 * client build menu (including remote, server-pushed entries) and command output.
 *
 * <p>Intentionally free of any SimuKraft reference so it (and {@link ActivePackProvider}) load even
 * when SimuKraft is not installed.
 *
 * @param packId id of the pack this building belongs to
 * @param category normalized lowercase category (residential/commercial/industry/public/other)
 * @param displayName name shown in SimuKraft's build menu
 * @param size human-readable size string (e.g. {@code 9 x 5 x 9}); SimuKraft only displays it
 * @param amount construction price string SimuKraft parses (e.g. {@code 8.64元})
 * @param author author credit
 * @param description short description shown in the build menu
 * @param metaFileName the {@code .sk} entry name SimuKraft keys the building by (must be unique)
 * @param structureFileName the structure entry name (e.g. {@code <packId>__<name>.nbt})
 */
public record ActiveBuilding(
    String packId,
    String category,
    String displayName,
    String size,
    String amount,
    String author,
    String description,
    String metaFileName,
    String structureFileName) {}
