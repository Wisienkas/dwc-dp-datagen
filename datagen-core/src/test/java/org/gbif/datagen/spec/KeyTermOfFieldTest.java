package org.gbif.datagen.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.smoke.FixtureSchemaBundle;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link Key#termOfField} resolves a term URI from a live field instead of a
 * hand-maintained constant, fails clearly when a field has no term, and — the actual point of
 * term-keying — produces a {@link Key} that matches every field sharing that term across
 * different tables, not just the one it was resolved from.
 */
class KeyTermOfFieldTest {

  private SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  @Test
  void resolvesTheDeclaredTermUri() {
    Key key = Key.termOfField(bundle(), "widget", "widgetID");
    assertTrue(key instanceof Key.TermKey, "expected a TermKey, got " + key.getClass());
    assertEquals(FixtureSchemaBundle.WIDGET_ID_TERM, ((Key.TermKey) key).termUri());
  }

  @Test
  void throwsWhenFieldHasNoTerm() {
    // widget.status has no dcterms:isVersionOf in the fixture.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                               () -> Key.termOfField(bundle(), "widget", "status"));
    assertTrue(ex.getMessage().contains("status"), "error should name the field, got: " + ex.getMessage());
  }

  @Test
  void throwsWhenFieldDoesNotExist() {
    assertThrows(IllegalArgumentException.class,
                 () -> Key.termOfField(bundle(), "widget", "doesNotExist"));
  }

  @Test
  void termKeyPortabilityAcrossTables() {
    // widget.widgetID and gadget.widgetIDRef share a term URI on purpose in the fixture — this
    // is the property that lets a term-keyed global survive a field rename across schema
    // versions, and it needs to actually be proven, not just asserted in a comment.
    SchemaBundle bundle = bundle();
    Key key = Key.termOfField(bundle, "widget", "widgetID");

    Field widgetIdField = bundle.requireSchema("widget").field("widgetID").orElseThrow();
    Field gadgetRefField = bundle.requireSchema("gadget").field("widgetIDRef").orElseThrow();
    Field unrelatedField = bundle.requireSchema("widget").field("status").orElseThrow();

    assertTrue(key.matches(widgetIdField), "key should match the field it was resolved from");
    assertTrue(key.matches(gadgetRefField),
               "key should match a field on a DIFFERENT table sharing the same term URI — this is the"
               + " whole point of term-keying");
    assertFalse(key.matches(unrelatedField), "key must not match a field with an unrelated term");
  }
}
