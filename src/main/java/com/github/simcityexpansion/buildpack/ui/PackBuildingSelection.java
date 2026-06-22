package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;

/**
 * A <b>building inside a pack</b> selected on the "build pack" tab (read directly from the zip, not extracted).
 *
 * @param pack the build pack this building belongs to
 * @param entry the building entry inside the pack
 */
public record PackBuildingSelection(PackArchive pack, PackBuildingEntry entry) {}
