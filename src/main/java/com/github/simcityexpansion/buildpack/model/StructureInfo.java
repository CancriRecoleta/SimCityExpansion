package com.github.simcityexpansion.buildpack.model;

import org.jetbrains.annotations.Nullable;

/**
 * Read-only parsed summary of a structure file, for display in the details panel and form prefill.
 *
 * @param name embedded structure name (litematic Metadata.Name; null when .nbt has no embedded name)
 * @param author author (litematic Metadata.Author; may be null)
 * @param sizeX bounding size X
 * @param sizeY bounding size Y
 * @param sizeZ bounding size Z
 * @param totalBlocks total non-air block count
 * @param totalVolume bounding volume
 * @param regionCount region count (always 1 for vanilla .nbt)
 * @param dataVersion Minecraft DataVersion at the time the structure was saved
 * @param timeCreated structure creation time (epoch milliseconds; provided by litematic metadata, 0 for unknown in other formats)
 * @param previewArgb litematic embedded preview image (square ARGB pixels; null if absent)
 * @param previewSize side length of the preview image (0 if no preview)
 */
public record StructureInfo(
    @Nullable String name,
    @Nullable String author,
    int sizeX,
    int sizeY,
    int sizeZ,
    long totalBlocks,
    long totalVolume,
    int regionCount,
    int dataVersion,
    long timeCreated,
    @Nullable int[] previewArgb,
    int previewSize) {

  /** Returns the size display string: "X x Y x Z". */
  public String sizeString() {
    return sizeX + " x " + sizeY + " x " + sizeZ;
  }
}
