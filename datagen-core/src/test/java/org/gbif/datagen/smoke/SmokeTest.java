package org.gbif.datagen.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;
import org.gbif.datagen.write.MapDescriptorSerializer;

/**
 * Not a JUnit suite — this sandbox has no Maven Central access to fetch junit-jupiter, so these
 * are plain assertions run via {@code main}. Equivalent JUnit 5 tests for the real repo are in
 * {@code DataPackageTest.java} alongside this file; they exercise the same properties.
 */
public final class SmokeTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) throws Exception {
    schemaDefaultsProduceValidData();
    selfReferenceIsAcyclic();
    weakRefForcesTargetFieldEvenWhenNotRequired();
    declaredFalseSuppressesDescriptorButNotData();
    cycleAcrossResourcesFailsAtBuild();
    removingForcedFieldFailsAtBuild();
    unknownFieldNameSuggestsClosestMatch();
    termKeyedGlobalAppliesAcrossTables();
    weightedMapIsOrderIndependent();
    corruptionTripsConstraintCheckUnlessDisabled();
    cardinalityDistributionVaries();
    csvQuotesEmbeddedCommaAndQuote();
    sameSeedIsByteIdentical();
    descriptorFieldOrderMatchesSchemaOrder();

    System.out.println();
    System.out.println(passed + " passed, " + failed + " failed");
    if (failed > 0) {
      System.exit(1);
    }
  }

  // ---------------------------------------------------------------- tests

  static void schemaDefaultsProduceValidData() {
    test("schema defaults produce valid data with zero config", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .resource("widget").rows(50)
          .build();
      GeneratedPackage pkg = spec.generate(1);

      for (Row row : pkg.rows("widget")) {
        require(row.get("widget_pk") != null, "widget_pk must be non-null (required+unique)");
        require(row.get("status") != null, "status is required");
        Object status = row.get("status");
        require(status.equals("active") || status.equals("retired"),
            "status must come from its enum, got " + status);
        Object weight = row.get("weight");
        if (weight != null) {
          double w = ((Number) weight).doubleValue();
          require(w >= 0 && w <= 100, "weight must respect [0,100], got " + w);
        }
      }
    });
  }

  static void selfReferenceIsAcyclic() {
    test("self-reference only ever points at an earlier row", () -> {
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
        require(parentIndex != null, "parent_fk must resolve to a widget_pk in this dataset");
        require(parentIndex < i, "row " + i + " points at row " + parentIndex + " (not earlier) — cycle risk");
      }
      require(roots > 0, "expected at least one root (row 0 always is one)");
    });
  }

  static void weakRefForcesTargetFieldEvenWhenNotRequired() {
    test("a ref target field is forced in even when not schema-required", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .resource("widget").rows(30)
          .resource("gadget")
              .rows(60)
              .ref("widgetIDRef", "widget", "widgetID").and()
          .build();

      require(spec.resource("widget").fieldNames().contains("widgetID"),
          "widget.widgetID must be forced in because gadget.widgetIDRef targets it, "
              + "even though widgetID carries no 'required' constraint");
    });
  }

  static void declaredFalseSuppressesDescriptorButNotData() {
    test("declared(false) removes the FK from the descriptor but the data still honors it", () -> {
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

      // Same seed, same shape -> byte-identical CSV data regardless of declared().
      String declaredCsv = writeAndRead(declaredPkg, "declaredFalseSuppressesDescriptorButNotData-declared");
      String undeclaredCsv = writeAndRead(undeclaredPkg, "declaredFalseSuppressesDescriptorButNotData-undeclared");
      require(declaredCsv.equals(undeclaredCsv), "declared(true) vs declared(false) must not change the data");

      Map<String, Object> declaredDescriptor = descriptorOf(declaredPkg);
      Map<String, Object> undeclaredDescriptor = descriptorOf(undeclaredPkg);
      require(fkFieldsOf(declaredDescriptor, "gadget").contains("widgetIDRef"),
          "declared(true) must emit the FK in the descriptor");
      require(!fkFieldsOf(undeclaredDescriptor, "gadget").contains("widgetIDRef"),
          "declared(false) must NOT emit the FK in the descriptor");

      // But the relation is still real in the undeclared data: every non-null widgetIDRef value
      // must actually appear in widget.widgetID.
      List<Object> widgetIds = undeclaredPkg.rows("widget").stream().map(r -> r.get("widgetID"))
          .filter(v -> v != null).map(Object.class::cast).toList();
      for (Row gadget : undeclaredPkg.rows("gadget")) {
        Object ref = gadget.get("widgetIDRef");
        if (ref != null) {
          require(widgetIds.contains(ref),
              "undeclared relation must still hold in the data: " + ref + " not found in widget.widgetID");
        }
      }
    });
  }

  static void cycleAcrossResourcesFailsAtBuild() {
    test("a ref cycle across resources is rejected at build(), not silently accepted", () -> {
      boolean threw = false;
      try {
        // "notes" and "label" carry no schema-declared FK, so this exercises TopoSort's cycle
        // detection specifically, rather than the "ref contradicts schema" guard (parent_fk
        // already has its own schema FK and would trip that check first, which is correct but
        // a different property than the one this test targets).
        Blueprint.forSchema(bundle())
            .resource("widget")
                .fields("notes")
                .rows(10)
                .ref("notes", "gadget", "gadget_pk").and()
            .resource("gadget")
                .fields("label")
                .rows(10)
                .ref("label", "widget", "widget_pk").and()
            .build();
      } catch (IllegalStateException e) {
        threw = true;
        require(e.getMessage().contains("cycle") || e.getMessage().contains("Cycle")
                || e.getMessage().contains("->"),
            "cycle error message should explain the cycle, got: " + e.getMessage());
      }
      require(threw, "expected a cycle to be rejected at build()");
    });
  }

  static void removingForcedFieldFailsAtBuild() {
    test("without() on a required or ref-target field fails at build()", () -> {
      boolean threw = false;
      try {
        Blueprint.forSchema(bundle())
            .resource("widget").allFields().without("status").rows(5)
            .build();
      } catch (IllegalStateException e) {
        threw = true;
      }
      require(threw, "removing a required field must fail loudly");
    });
  }

  static void unknownFieldNameSuggestsClosestMatch() {
    test("an unknown field name gets a 'did you mean' suggestion", () -> {
      boolean threw = false;
      try {
        Blueprint.forSchema(bundle())
            .resource("widget").fields("staus").rows(5) // typo for "status"
            .build();
      } catch (IllegalStateException e) {
        threw = true;
        require(e.getMessage().contains("status"), "expected a suggestion mentioning 'status', got: "
            + e.getMessage());
      }
      require(threw, "an unknown field name must fail at build()");
    });
  }

  static void termKeyedGlobalAppliesAcrossTables() {
    test("a term-keyed global overrides the schema default", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .global(Key.term(FixtureSchemaBundle.WIDGET_ID_TERM), Gen.constant("FIXED-ID"))
          .resource("widget")
              .fields("widgetID")
              .rows(10)
          .build();
      GeneratedPackage pkg = spec.generate(9);
      for (Row row : pkg.rows("widget")) {
        require("FIXED-ID".equals(row.get("widgetID")), "term-keyed global should have won over the schema default");
      }
    });
  }

  static void weightedMapIsOrderIndependent() {
    test("weighted(Map) is deterministic regardless of insertion order", () -> {
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
        require(rowsA.get(i).get("status").equals(rowsB.get(i).get("status")),
            "row " + i + " differs between insertion orders: " + rowsA.get(i).get("status")
                + " vs " + rowsB.get(i).get("status"));
      }
    });
  }

  static void corruptionTripsConstraintCheckUnlessDisabled() {
    test("corrupt(OUT_OF_RANGE) trips the constraint check unless disabled", () -> {
      boolean threw = false;
      try {
        Blueprint.forSchema(bundle())
            .resource("widget")
                .fields("weight")
                .rows(20)
                .field("weight", Gen.between(0, 100).corrupt(1.0, org.gbif.datagen.gen.Corruption.OUT_OF_RANGE))
            .build()
            .generate(1);
      } catch (IllegalStateException e) {
        threw = true;
      }
      require(threw, "a value forced out of range should trip the constraint check by default");

      // Same recipe, checking disabled -> must not throw.
      GeneratedPackage pkg = Blueprint.forSchema(bundle())
          .checkConstraints(false)
          .resource("widget")
              .fields("weight")
              .rows(20)
              .field("weight", Gen.between(0, 100).corrupt(1.0, org.gbif.datagen.gen.Corruption.OUT_OF_RANGE))
          .build()
          .generate(1);
      boolean anyOutOfRange = pkg.rows("widget").stream()
          .anyMatch(r -> ((Number) r.get("weight")).doubleValue() > 100);
      require(anyOutOfRange, "with checking disabled, the corrupted value should actually be out of range");
    });
  }

  static void cardinalityDistributionVaries() {
    test("zipf cardinality gives varied fan-out, not a flat count", () -> {
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
      require(distinctCounts > 1, "expected varied per-widget counts under zipf, saw only: "
          + countsByWidget.values().stream().distinct().toList());
    });
  }

  static void csvQuotesEmbeddedCommaAndQuote() throws Exception {
    test("CSV writer quotes embedded commas and doubles embedded quotes", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .resource("widget")
              .fields("notes")
              .rows(1)
              .field("notes", Gen.constant("has, a comma and a \"quote\""))
          .build();
      GeneratedPackage pkg = spec.generate(1);
      Path dir = Files.createTempDirectory("datagen-smoke-csv");
      pkg.writeTo(dir, new MapDescriptorSerializer(), "datagen-smoke-csv");
      String csv = Files.readString(dir.resolve("widget.csv"));
      require(csv.contains("\"has, a comma and a \"\"quote\"\"\""),
          "expected RFC4180 quoting, got: " + csv);
    });
  }

  static void sameSeedIsByteIdentical() throws Exception {
    test("same spec + same seed produces byte-identical output", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .resource("widget").allFields().rows(25)
          .resource("gadget").allFields().rows(50)
              .ref("widget_fk", "widget", "widget_pk").and()
          .build();
      String a = writeAndRead(spec.generate(42), "sameSeedIsByteIdentical-a");
      String b = writeAndRead(spec.generate(42), "sameSeedIsByteIdentical-b");
      require(a.equals(b), "identical spec + seed must produce identical CSV bytes");
    });
  }

  static void descriptorFieldOrderMatchesSchemaOrder() {
    test("descriptor field order follows schema order, not recipe declaration order", () -> {
      DataPackageSpec spec = Blueprint.forSchema(bundle())
          .resource("widget")
              // Declared out of schema order (schema: widget_pk, widgetID, parent_fk, status, weight, notes)
              .fields("notes", "widget_pk", "status")
              .rows(5)
          .build();
      Map<String, Object> descriptor = descriptorOf(spec.generate(1));
      List<String> fieldNames = fieldNamesOf(descriptor, "widget");
      require(fieldNames.equals(List.of("widget_pk", "status", "notes")),
          "expected schema order [widget_pk, status, notes], got " + fieldNames);
    });
  }

  // ---------------------------------------------------------------- helpers

  private static SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> descriptorOf(GeneratedPackage pkg) {
    try {
      Path dir = Files.createTempDirectory("datagen-smoke-descriptor");
      pkg.writeTo(dir, new MapDescriptorSerializer(),  "datagen-smoke-descriptor");
      return JsonSupport.readObject(Files.readString(dir.resolve("datapackage.json")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> fkFieldsOf(Map<String, Object> descriptor, String resourceName) {
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
  private static List<String> fieldNamesOf(Map<String, Object> descriptor, String resourceName) {
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

  private static String writeAndRead(GeneratedPackage pkg, String label) throws Exception {
    Path dir = Files.createTempDirectory("datagen-smoke-" + label);
    pkg.writeTo(dir, new MapDescriptorSerializer(),   "datagen-smoke-" + label);
    StringBuilder sb = new StringBuilder();
    for (String resource : pkg.spec().generationOrder()) {
      sb.append(Files.readString(dir.resolve(resource + ".csv")));
    }
    return sb.toString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private interface CheckedRunnable {
    void run() throws Exception;
  }

  private static void test(String name, CheckedRunnable runnable) {
    try {
      runnable.run();
      System.out.println("PASS  " + name);
      passed++;
    } catch (Throwable t) {
      System.out.println("FAIL  " + name);
      System.out.println("      " + t);
      failed++;
    }
  }
}
