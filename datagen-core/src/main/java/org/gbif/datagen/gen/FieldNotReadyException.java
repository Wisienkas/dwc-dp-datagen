package org.gbif.datagen.gen;

/**
 * Thrown by a {@link Generator} to mean "not yet — retry me once more of this row has been
 * generated," as opposed to a terminal failure.
 *
 * <p>The engine catches this specifically and retries the field on a later pass within the same
 * row. It is deliberately narrow: a generator that will <em>never</em> resolve (e.g. a template
 * referencing a field whose generator already ran and returned null) must not throw this — it
 * should throw a plain exception instead, so the failure is reported against the actual missing
 * value rather than surfacing later as a vague "made no progress across the whole row" error.
 */
public final class FieldNotReadyException extends RuntimeException {

  private final String awaitedField;

  public FieldNotReadyException(String awaitedField) {
    super("field '" + awaitedField + "' has not been generated yet on this row");
    this.awaitedField = awaitedField;
  }

  public String awaitedField() {
    return awaitedField;
  }
}
