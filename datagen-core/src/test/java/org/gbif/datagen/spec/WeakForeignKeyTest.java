package org.gbif.datagen.spec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.smoke.FixtureSchemaBundle;
import org.junit.jupiter.api.Test;

/**
 * Proves weakForeignKeys get identical treatment to foreignKeys: they drive topological
 * ordering, force their target field into the referenced resource's selection, and get a
 * default lookup generator — <em>once the referencing field is actually selected</em>.
 *
 * <p>That qualifier matters and is the thing these tests got wrong on the first pass: schema-FK
 * forcing (strong or weak) is gated behind {@code willInclude} — it only pulls a ref's target
 * field in if the ref's own source field is already going to be present (required, explicitly
 * configured, or explicitly selected). A non-required FK column under the default
 * {@code REQUIRED} selection mode is simply absent, and correctly drags nothing else in with
 * it — that gate exists specifically so declaring a resource doesn't silently pull in the
 * entire relation graph the schema happens to define. So every resource below that has a
 * non-required referencing field calls {@code .allFields()} to make that field's presence an
 * explicit premise of the test, not an accident of default selection.
 */
class WeakForeignKeyTest {

  private SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  @Test
  void weakForeignKeyDrivesGenerationOrder() {
    // Declared in reverse dependency order on purpose — proves ordering comes from the
    // relationship, not from declaration order. gizmo needs allFields() so widgetIDWeak (the
    // non-required referencing column) is actually part of the resource; otherwise there is
    // no reference in the generated data at all, and correctly no ordering constraint either.
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("gizmo").allFields().rows(20)
      .resource("widget").rows(10)
      .build();

    List<String> order = spec.generationOrder();
    assertTrue(order.indexOf("widget") < order.indexOf("gizmo"),
               "widget must be generated before gizmo, since gizmo's weakForeignKeys targets it; got order "
               + order);
  }

  @Test
  void weakForeignKeyForcesTargetFieldIn() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("widget").rows(10) // default REQUIRED-only selection on the target side
      .resource("gizmo").allFields().rows(20) // widgetIDWeak must be present to force anything
      .build();

    assertTrue(spec.resource("widget").fieldNames().contains("widgetID"),
               "widget.widgetID must be forced in because gizmo.widgetIDWeak (now selected via"
               + " allFields()) targets it, even though widgetID carries no 'required' constraint");
  }

  @Test
  void weakForeignKeyGetsDefaultLookupGeneratorWithZeroConfig() {
    // "Zero config" here means zero .field(...)/.ref(...) calls — not zero .allFields(). The
    // point under test is the *generator* default, not the *selection* default; allFields() is
    // what makes the referencing column exist in the first place, same as the two tests above.
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("widget").rows(10)
      .resource("gizmo").allFields().rows(30)
      .build();
    GeneratedPackage pkg = spec.generate(17);

    List<Object> widgetIds = pkg.rows("widget").stream()
      .map(r -> r.get("widgetID"))
      .filter(v -> v != null)
      .map(Object.class::cast)
      .toList();

    for (Row gizmo : pkg.rows("gizmo")) {
      Object ref = gizmo.get("widgetIDWeak");
      if (ref != null) {
        assertTrue(widgetIds.contains(ref),
                   "gizmo.widgetIDWeak should resolve via a real lookup into widget.widgetID with zero"
                   + " .field()/.ref() config, got a value not present in the pool: " + ref);
      }
    }
  }

  @Test
  void removingWeakRefTargetFieldFailsAtBuild() {
    assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("widget").allFields().without("widgetID").rows(10)
        .resource("gizmo").allFields().rows(20) // must select widgetIDWeak to force the conflict
        .build());
  }

  @Test
  void targetlessWeakForeignKeyParsesAndFallsThroughToSchemaDefault() {
    // "widget-tag" declares widgetIDCandidate as weakForeignKeys with no 'reference' at all —
    // this must parse cleanly (not throw) and, with nothing to look up against, fall through to
    // an ordinary schema-default generator rather than being treated as an error.
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("widget-tag").allFields().rows(15)
      .build();

    assertDoesNotThrow(() -> spec.generate(1),
                       "a targetless weakForeignKey must not throw during generation");

    GeneratedPackage pkg = spec.generate(1);
    for (Row row : pkg.rows("widget-tag")) {
      assertTrue(row.values().containsKey("widgetIDCandidate"),
                 "widgetIDCandidate must still be generated even though its weak FK has no target");
    }
  }
}
