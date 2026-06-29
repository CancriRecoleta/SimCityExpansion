package com.github.simcityexpansion.buildpack.model;

/**
 * Mutable form model for a building's .sk metadata (intended for two-way UI binding).
 *
 * <p>Fields correspond one-to-one with SimuKraft's .sk keys: name / size / amount / author /
 * description / tags / job_type. The size is computed automatically from the structure file
 * and is read-only in the UI.
 */
public final class BuildingMetadata {
  public String name = "";
  public String amount = "";
  public String author = "";
  public String description = "";
  /** Comma-separated tag string, e.g. {@code price_low,material_low,stage_early}. */
  public String tags = "";
  public String jobType = "";
  public BuildingCategory category = BuildingCategory.OTHER;

  public int sizeX;
  public int sizeY;
  public int sizeZ;

  /** Returns the size in the .sk field format: "X x Y x Z". */
  public String sizeString() {
    return sizeX + " x " + sizeY + " x " + sizeZ;
  }

  /**
   * Returns an independent copy of this model. Used to snapshot the live UI form before handing the
   * metadata to a background install thread, so later edits (or the installer writing back the
   * computed size) cannot race with the form.
   */
  public BuildingMetadata copy() {
    BuildingMetadata copy = new BuildingMetadata();
    copy.name = name;
    copy.amount = amount;
    copy.author = author;
    copy.description = description;
    copy.tags = tags;
    copy.jobType = jobType;
    copy.category = category;
    copy.sizeX = sizeX;
    copy.sizeY = sizeY;
    copy.sizeZ = sizeZ;
    return copy;
  }

  /** Pre-fills the form from a parsed structure summary (name and author are only filled when blank, to avoid overwriting user input). */
  public void prefill(StructureInfo info, String fallbackName) {
    if (name.isBlank()) {
      name = info.name() == null || info.name().isBlank() ? fallbackName : info.name();
    }
    if (author.isBlank() && info.author() != null) {
      author = info.author();
    }
    sizeX = info.sizeX();
    sizeY = info.sizeY();
    sizeZ = info.sizeZ();
  }
}
