package com.github.simcityexpansion.buildpack.ui.preview;

/**
 * An in-memory ARGB pixel image produced by a preview renderer (top-down or isometric). Exposes the
 * raw pixels so they can be reused beyond on-screen display, e.g. encoded to a PNG pack icon.
 *
 * @param argb row-major ARGB pixels, length {@code width * height}
 * @param width image width in pixels
 * @param height image height in pixels
 */
public record PixelImage(int[] argb, int width, int height) {}
