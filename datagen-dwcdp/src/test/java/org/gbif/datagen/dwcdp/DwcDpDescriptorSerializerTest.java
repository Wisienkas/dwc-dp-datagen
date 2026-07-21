package org.gbif.datagen.dwcdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DwcDpDescriptorSerializer} against the fixture bundle, which includes a
 * "material" table declaring {@code weakForeignKeys} — the highest-risk untested surface from
 * this session, since the serializer was written without compiling. Each assertion targets a
 * specific wire-shape decision flagged as unverified: single-field arrays unwrapped to a bare
 * string, weakForeignKeys emitted separately from foreignKeys, a self-reference's
 * {@code reference.resource} left empty, and an undeclared ref actually absent from output.
 */
class DwcDpDescriptorSerializerTest {

  private SchemaBundle bundle() {
    return DwcDpSchemaBundle.load("1.0_TEST", getClass().getClassLoader());
  }

  private Map<String, Object> descriptorOf(GeneratedPackage pkg) {
    String json = new DwcDpDescriptorSerializer().serialize(pkg.spec(), "test-id", "2026-01-01T00:00:00Z");
    return JsonSupport.readObject(json);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> schemaOf(Map<String, Object> descriptor, String resourceName) {
    for (Object r : (List<Object>) descriptor.get("resources")) {
      Map<String, Object> res = (Map<String, Object>) r;
      if (resourceName.equals(res.get("name"))) {
        return (Map<String, Object>) res.get("schema");
      }
    }
    throw new AssertionError("no resource named " + resourceName + " in descriptor");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstForeignKey(Map<String, Object> schema, String key) {
    List<Object> fks = (List<Object>) schema.get(key);
    return (Map<String, Object>) fks.get(0);
  }

  @Test
  void selfReferenceEmitsBareStringFieldsAndEmptyResource() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("event")
      .allFields()
      .rows(30)
      .selfRef("parentEvent_fk", "event_pk").roots(0.2)
      .build();
    GeneratedPackage pkg = spec.generate(1);

    Map<String, Object> schema = schemaOf(descriptorOf(pkg), "event");
    Map<String, Object> fk = firstForeignKey(schema, "foreignKeys");

    assertEquals("parentEvent_fk", fk.get("fields"),
                 "single-field 'fields' must be a bare string, not a one-element array");

    @SuppressWarnings("unchecked")
    Map<String, Object> reference = (Map<String, Object>) fk.get("reference");
    assertEquals("", reference.get("resource"),
                 "a self-reference's reference.resource must be empty, not the table's own name");
    assertEquals("event_pk", reference.get("fields"));
  }

  @Test
  void weakForeignKeysAreEmittedSeparatelyFromForeignKeys() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("event").rows(10)
      .resource("occurrence").rows(20).ref("event_fk", "event", "event_pk").and()
      .resource("material").allFields().rows(15) // derivedFromOccurrenceID isn't required —
      // must be explicitly selected, same lesson as WeakForeignKeyTest: schema-FK forcing
      // (strong or weak) never pulls a column in unless the column itself is already selected.
      .build();
    GeneratedPackage pkg = spec.generate(2);

    Map<String, Object> materialSchema = schemaOf(descriptorOf(pkg), "material");

    assertTrue(materialSchema.containsKey("weakForeignKeys"),
               "material's derivedFromOccurrenceID relation must appear under weakForeignKeys");
    assertFalse(materialSchema.containsKey("foreignKeys"),
                "material has no strong foreignKeys in the fixture schema, so the key should be absent"
                + " rather than an empty array");

    Map<String, Object> weakFk = firstForeignKey(materialSchema, "weakForeignKeys");
    assertEquals("derivedFromOccurrenceID", weakFk.get("fields"));
    @SuppressWarnings("unchecked")
    Map<String, Object> reference = (Map<String, Object>) weakFk.get("reference");
    assertEquals("occurrence", reference.get("resource"));
    assertEquals("occurrenceID", reference.get("fields"));
  }

  @Test
  void missingValuesSerializeAsBareStrings() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("event").rows(5)
      .build();
    GeneratedPackage pkg = spec.generate(3);

    Map<String, Object> schema = schemaOf(descriptorOf(pkg), "event");
    @SuppressWarnings("unchecked")
    List<Object> fields = (List<Object>) schema.get("fields");
    @SuppressWarnings("unchecked")
    Map<String, Object> firstField = (Map<String, Object>) fields.get(0);

    Object missingValues = firstField.get("missingValues");
    assertTrue(missingValues instanceof List, "missingValues must be a list");
    Object firstEntry = ((List<?>) missingValues).get(0);
    assertTrue(firstEntry instanceof String,
               "each missingValues entry must serialize as a bare string, not an object with a 'source'"
               + " property — 'source' is our own bookkeeping, not part of the Frictionless wire"
               + " format, got: " + firstEntry.getClass());
  }

  @Test
  void undeclaredRefIsAbsentFromOutputButStillPresentInData() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("event").rows(10)
      .resource("occurrence")
      .rows(20)
      .ref("event_fk", "event", "event_pk").declared(false).and()
      .build();
    GeneratedPackage pkg = spec.generate(4);

    Map<String, Object> occurrenceSchema = schemaOf(descriptorOf(pkg), "occurrence");
    assertFalse(occurrenceSchema.containsKey("foreignKeys"),
                "declared(false) must suppress the FK from the descriptor entirely");

    List<Object> eventPks = pkg.rows("event").stream()
      .map(r -> r.get("event_pk")).map(Object.class::cast).toList();
    for (Row occ : pkg.rows("occurrence")) {
      assertTrue(eventPks.contains(occ.get("event_fk")),
                 "the relation must still hold in the data even though it's absent from the descriptor");
    }
  }
}
