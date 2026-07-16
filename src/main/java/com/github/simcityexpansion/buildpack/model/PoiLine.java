package com.github.simcityexpansion.buildpack.model;

import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * One {@code poi:} line from a .sk metadata file, kept as raw strings so the UI can hold
 * intermediate (possibly invalid) input and validation can report on it.
 *
 * <p>SimuKraft's format is {@code poi: TYPE, capacity[, id]}: the type is matched against
 * {@link #KNOWN_TYPES} (unknown values silently become OTHER upstream), the capacity must parse as
 * an integer (otherwise the whole line is dropped upstream), and the id defaults to the lowercase
 * type name. POI lines carry no coordinates — upstream derives the position from the placed blocks
 * (first block whose registry name contains the id, else a {@code control_box} block, else the
 * building origin); RESIDENTIAL housing ignores poi lines entirely and scans red bed heads.
 */
public record PoiLine(String type, String capacity, String id) {

  /** POI types accepted by SimuKraft's {@code CityPoiType} enum. */
  public static final List<String> KNOWN_TYPES = List.of(
      "RESIDENTIAL", "COMMERCIAL", "INDUSTRIAL", "OTHER", "GATHERING",
      "STORAGE", "LOGISTICS", "FARMLAND", "DEFENSE");

  /** Parses the value part of a {@code poi:} line ({@code "TYPE, capacity[, id]"}). */
  public static PoiLine parse(String value) {
    String[] parts = value.split(",");
    String type = parts.length > 0 ? parts[0].trim() : "";
    String capacity = parts.length > 1 ? parts[1].trim() : "";
    String id = parts.length > 2 ? parts[2].trim() : "";
    return new PoiLine(type, capacity, id);
  }

  /** Serializes back to the .sk value format ({@code "TYPE, capacity[, id]"}). */
  public String format() {
    String base = type.trim() + ", " + capacity.trim();
    return id.isBlank() ? base : base + ", " + id.trim();
  }

  /** Whether the type is one SimuKraft recognizes (case-insensitive). */
  public boolean typeKnown() {
    return KNOWN_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
  }

  /** The capacity as an integer, or null when it does not parse (upstream drops such lines). */
  @Nullable
  public Integer capacityValue() {
    try {
      return Integer.parseInt(capacity.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The effective POI id upstream matches block registry names against (defaults to the lowercase type). */
  public String effectiveId() {
    return id.isBlank() ? type.trim().toLowerCase(Locale.ROOT) : id.trim();
  }

  /** Whether this line declares RESIDENTIAL (which triggers red-bed housing registration upstream). */
  public boolean isResidential() {
    return "RESIDENTIAL".equalsIgnoreCase(type.trim());
  }
}
