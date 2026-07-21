package org.gbif.datagen.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Corruption;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.smoke.FixtureSchemaBundle;
import org.gbif.datagen.write.MapDescriptorSerializer;

import org.junit.jupiter.api.Test;

/**
 * Exercises the properties {@code DataPackage}, {@code FieldSelection}, {@code TopoSort}, and the
 * engine depend on: self-reference acyclicity, weak-ref field forcing, {@code declared(false)}
 * descriptor suppression without affecting data, cross-resource cycle detection, term-key
 * portability, weighted-map order independence, corruption tripping constraint checks, and
 * cardinality distribution shape.
 *
 * <p>These bodies were first verified as plain assertions in {@code org.gbif.datagen.smoke}
 * (this sandbox had no Maven Central access to fetch junit-jupiter for a direct compile check) —
 * the logic itself already ran and passed; this is the JUnit 5 form for CI.
 */
class BlueprintTest {

  private SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  @Test
  void schemaDefaultsProduceValidDataWithZeroConfig() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget").rows(50)
        .build();
    GeneratedPackage pkg = spec.generate(1);

    for (Row row : pkg.rows("widget")) {
      assertTrue(row.get("widget_pk") != null, "widget_pk must be non-null (required+unique)");
      Object status = row.get("status");
      assertTrue(status != null, "status is required");
      assertTrue(status.equals("active") || status.equals("retired"),
          "status must come from its enum, got " + status);
      Object weight = row.get("weight");
      if (weight != null) {
        double w = ((Number) weight).doubleValue();
        assertTrue(w >= 0 && w <= 100, "weight must respect [0,100], got " + w);
      }
    }
  }

  @Test
  void selfReferenceOnlyPointsAtEarlierRow() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget")
            .fields("parent_fk")
            .rows(200)
            .selfRef("parent_fk", "widget_pk").roots(0.15)
        .build();
    GeneratedPackage pkg = spec.generate(7);

    List<Row> rows = pkg.rows("widget");
    Map<String, Integer> indexOf = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      indexOf.put((String) rows.get(i).get("widget_pk"), i);
    }
    int roots = 0;
    for (int i = 0; i < rows.size(); i++) {
      Object parent = rows.get(i).get("parent_fk");
      if (parent == null) {
        roots++;
        continue;
      }
      Integer parentIndex = indexOf.get(parent);
      assertTrue(parentIndex != null, "parent_fk must resolve to a widget_pk in this dataset");
      assertTrue(parentIndex < i, "row " + i + " points at row " + parentIndex + " (not earlier) — cycle risk");
    }
    assertTrue(roots > 0, "expected at least one root (row 0 always is one)");
  }

  @Test
  void refTargetFieldIsForcedInEvenWhenNotRequired() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget").rows(30)
        .resource("gadget")
            .rows(60)
            .ref("widgetIDRef", "widget", "widgetID").and()
        .build();

    assertTrue(spec.resource("widget").fieldNames().contains("widgetID"),
        "widget.widgetID must be forced in because gadget.widgetIDRef targets it, even though"
            + " widgetID carries no 'required' constraint");
  }

  @Test
  void declaredFalseSuppressesDescriptorButNotData() throws Exception {
    DataPackageSpec declaredSpec = Blueprint.forSchema(bundle())
        .resource("widget").rows(20)
        .resource("gadget")
            .rows(40)
            .ref("widgetIDRef", "widget", "widgetID").and()
        .build();
    DataPackageSpec undeclaredSpec = Blueprint.forSchema(bundle())
        .resource("widget").rows(20)
        .resource("gadget")
            .rows(40)
            .ref("widgetIDRef", "widget", "widgetID").declared(false).and()
        .build();

    GeneratedPackage declaredPkg = declaredSpec.generate(3);
    GeneratedPackage undeclaredPkg = undeclaredSpec.generate(3);

    String declaredCsv = writeAndRead(declaredPkg);
    String undeclaredCsv = writeAndRead(undeclaredPkg);
    assertEquals(declaredCsv, undeclaredCsv, "declared(true) vs declared(false) must not change the data");

    Map<String, Object> declaredDescriptor = descriptorOf(declaredPkg);
    Map<String, Object> undeclaredDescriptor = descriptorOf(undeclaredPkg);
    assertTrue(fkFieldsOf(declaredDescriptor, "gadget").contains("widgetIDRef"),
        "declared(true) must emit the FK in the descriptor");
    assertFalse(fkFieldsOf(undeclaredDescriptor, "gadget").contains("widgetIDRef"),
        "declared(false) must NOT emit the FK in the descriptor");

    List<Object> widgetIds = undeclaredPkg.rows("widget").stream().map(r -> r.get("widgetID"))
        .filter(v -> v != null).map(Object.class::cast).toList();
    for (Row gadget : undeclaredPkg.rows("gadget")) {
      Object ref = gadget.get("widgetIDRef");
      if (ref != null) {
        assertTrue(widgetIds.contains(ref),
            "undeclared relation must still hold in the data: " + ref + " not found in widget.widgetID");
      }
    }
  }

  @Test
  void cycleAcrossResourcesFailsAtBuild() {
    // "notes"/"label" carry no schema-declared FK, so this exercises TopoSort's cycle detection
    // specifically rather than the "ref contradicts schema" guard.
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
        Blueprint.forSchema(bundle())
            .resource("widget")
                .fields("notes")
                .rows(10)
                .ref("notes", "gadget", "gadget_pk").and()
            .resource("gadget")
                .fields("label")
                .rows(10)
                .ref("label", "widget", "widget_pk").and()
            .build());
    assertTrue(ex.getMessage().contains("->") || ex.getMessage().toLowerCase().contains("cycle"),
        "cycle error message should explain the cycle, got: " + ex.getMessage());
  }

  @Test
  void removingForcedFieldFailsAtBuild() {
    assertThrows(IllegalStateException.class, () ->
        Blueprint.forSchema(bundle())
            .resource("widget").allFields().without("status").rows(5)
            .build());
  }

  @Test
  void unknownFieldNameSuggestsClosestMatch() {
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
        Blueprint.forSchema(bundle())
            .resource("widget").fields("staus").rows(5) // typo for "status"
            .build());
    assertTrue(ex.getMessage().contains("status"), "expected a suggestion mentioning 'status', got: "
        + ex.getMessage());
  }

  @Test
  void termKeyedGlobalOverridesSchemaDefault() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .global(Key.term(FixtureSchemaBundle.WIDGET_ID_TERM), Gen.constant("FIXED-ID"))
        .resource("widget")
            .fields("widgetID")
            .rows(10)
        .build();
    GeneratedPackage pkg = spec.generate(9);
    for (Row row : pkg.rows("widget")) {
      assertEquals("FIXED-ID", row.get("widgetID"), "term-keyed global should have won over the schema default");
    }
  }

  @Test
  void weightedMapIsOrderIndependent() {
    Map<String, Double> a = new LinkedHashMap<>();
    a.put("active", 0.9);
    a.put("retired", 0.1);
    Map<String, Double> b = new LinkedHashMap<>();
    b.put("retired", 0.1);
    b.put("active", 0.9);

    DataPackageSpec specA = Blueprint.forSchema(bundle())
        .resource("widget").fields("status").rows(20).field("status", Gen.weighted(a)).build();
    DataPackageSpec specB = Blueprint.forSchema(bundle())
        .resource("widget").fields("status").rows(20).field("status", Gen.weighted(b)).build();

    List<Row> rowsA = specA.generate(5).rows("widget");
    List<Row> rowsB = specB.generate(5).rows("widget");
    for (int i = 0; i < rowsA.size(); i++) {
      assertEquals(rowsA.get(i).get("status"), rowsB.get(i).get("status"),
          "row " + i + " differs between insertion orders");
    }
  }

  @Test
  void corruptionTripsConstraintCheckUnlessDisabled() {
    assertThrows(IllegalStateException.class, () ->
        Blueprint.forSchema(bundle())
            .resource("widget")
                .fields("weight")
                .rows(20)
                .field("weight", Gen.between(0, 100).corrupt(1.0, Corruption.OUT_OF_RANGE))
            .build()
            .generate(1));

    GeneratedPackage pkg = Blueprint.forSchema(bundle())
        .checkConstraints(false)
        .resource("widget")
            .fields("weight")
            .rows(20)
            .field("weight", Gen.between(0, 100).corrupt(1.0, Corruption.OUT_OF_RANGE))
        .build()
        .generate(1);
    boolean anyOutOfRange = pkg.rows("widget").stream()
        .anyMatch(r -> ((Number) r.get("weight")).doubleValue() > 100);
    assertTrue(anyOutOfRange, "with checking disabled, the corrupted value should actually be out of range");
  }

  @Test
  void zipfCardinalityGivesVariedFanOut() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget").rows(30)
        .resource("gadget")
            .fields("widget_fk")
            .per("widget", Dist.zipf(1.2).max(50))
            .ref("widget_fk", "widget", "widget_pk").and()
        .build();
    GeneratedPackage pkg = spec.generate(11);

    Map<Object, Long> countsByWidget = new LinkedHashMap<>();
    for (Row gadget : pkg.rows("gadget")) {
      countsByWidget.merge(gadget.get("widget_fk"), 1L, Long::sum);
    }
    long distinctCounts = countsByWidget.values().stream().distinct().count();
    assertTrue(distinctCounts > 1, "expected varied per-widget counts under zipf, saw only: "
        + countsByWidget.values().stream().distinct().toList());
  }

  @Test
  void csvWriterQuotesEmbeddedCommaAndQuote() throws Exception {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget")
            .fields("notes")
            .rows(1)
            .field("notes", Gen.constant("has, a comma and a \"quote\""))
        .build();
    GeneratedPackage pkg = spec.generate(1);
    Path dir = Files.createTempDirectory("datagen-test-csv");
    pkg.writeTo(dir, new MapDescriptorSerializer());
    String csv = Files.readString(dir.resolve("widget.csv"));
    assertTrue(csv.contains("\"has, a comma and a \"\"quote\"\"\""), "expected RFC4180 quoting, got: " + csv);
  }

  @Test
  void sameSeedProducesByteIdenticalOutput() throws Exception {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        .resource("widget").allFields().rows(25)
        .resource("gadget").allFields().rows(50)
            .ref("widget_fk", "widget", "widget_pk").and()
        .build();
    String a = writeAndRead(spec.generate(42));
    String b = writeAndRead(spec.generate(42));
    assertEquals(a, b, "identical spec + seed must produce identical CSV bytes");
  }

  @Test
  void descriptorFieldOrderFollowsSchemaOrder() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
        // Declared out of schema order (schema: widget_pk, widgetID, parent_fk, status, weight, notes)
        .resource("widget")
            .fields("notes", "widget_pk", "status")
            .rows(5)
        .build();
    Map<String, Object> descriptor = descriptorOf(spec.generate(1));
    List<String> fieldNames = fieldNamesOf(descriptor, "widget");
    assertEquals(List.of("widget_pk", "status", "notes"), fieldNames);
  }

  // ---------------------------------------------------------------- helpers

  @SuppressWarnings("unchecked")
  private Map<String, Object> descriptorOf(GeneratedPackage pkg) {
    try {
      Path dir = Files.createTempDirectory("datagen-test-descriptor");
      pkg.writeTo(dir, new MapDescriptorSerializer());
      return JsonSupport.readObject(Files.readString(dir.resolve("datapackage.json")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> fkFieldsOf(Map<String, Object> descriptor, String resourceName) {
    List<String> result = new java.util.ArrayList<>();
    for (Object r : (List<Object>) descriptor.get("resources")) {
      Map<String, Object> res = (Map<String, Object>) r;
      if (!resourceName.equals(res.get("name"))) {
        continue;
      }
      Map<String, Object> schema = (Map<String, Object>) res.get("schema");
      Object fks = schema.get("foreignKeys");
      if (fks instanceof List) {
        for (Object fk : (List<Object>) fks) {
          result.add(String.valueOf(((Map<String, Object>) fk).get("fields")));
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<String> fieldNamesOf(Map<String, Object> descriptor, String resourceName) {
    List<String> result = new java.util.ArrayList<>();
    for (Object r : (List<Object>) descriptor.get("resources")) {
      Map<String, Object> res = (Map<String, Object>) r;
      if (!resourceName.equals(res.get("name"))) {
        continue;
      }
      Map<String, Object> schema = (Map<String, Object>) res.get("schema");
      for (Object f : (List<Object>) schema.get("fields")) {
        result.add(String.valueOf(((Map<String, Object>) f).get("name")));
      }
    }
    return result;
  }

  private String writeAndRead(GeneratedPackage pkg) throws Exception {
    Path dir = Files.createTempDirectory("datagen-test-writeread");
    pkg.writeTo(dir, new MapDescriptorSerializer());
    StringBuilder sb = new StringBuilder();
    for (String resource : pkg.spec().generationOrder()) {
      sb.append(Files.readString(dir.resolve(resource + ".csv")));
    }
    return sb.toString();
  }
}
