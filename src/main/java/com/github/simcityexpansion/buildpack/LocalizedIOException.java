package com.github.simcityexpansion.buildpack;

import java.io.IOException;

import net.minecraft.network.chat.Component;

/**
 * An IO exception that carries a localizable message: parse/install failure reasons must be
 * displayed in the status bar via translation keys rather than hard-coded text.
 */
public class LocalizedIOException extends IOException {

  private final Component component;

  public LocalizedIOException(Component component) {
    super(component.getString());
    this.component = component;
  }

  /** The localized failure reason. */
  public Component component() {
    return component;
  }

  /** Extracts a display message from any exception: uses the translation component for localized exceptions, falls back to the raw message text for others. */
  public static Component messageOf(Throwable e) {
    if (e instanceof LocalizedIOException localized) {
      return localized.component();
    }
    return Component.literal(String.valueOf(e.getMessage()));
  }
}
