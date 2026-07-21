package org.gbif.datagen.dwcdp;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.gbif.datagen.schema.TableSchema;

/**
 * Plain-assertion smoke test for the bundle loader (no JUnit available in this sandbox — see the
 * note in {@code datagen-core}'s SmokeTest). Exercises the fixture layout under
 * {@code src/test/resources/schemas/}, which mirrors the real dwc-dp-schemas jar exactly:
 * {@code bundles.json -> {version}/index.json -> {version}/dwc-dp-profile.json ->
 * {version}/table-schemas/{name}.json}.
 */
public final class BundleLoaderSmokeTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) throws Exception {
    loadsFixtureBundleByExactVersionString();
    profileUrlComesFromDollarIdVerbatim();
    tableNamesComeFromIndexNotFromEnumeration();
    unknownVersionListsAvailableOnes();
    versionMismatchBetweenBundlesAndIndexFailsLoudly();
    tableNameMismatchBetweenIndexAndProfileFailsLoudly();

    System.out.println();
    System.out.println(passed + " passed, " + failed + " failed");
    if (failed > 0) {
      System.exit(1);
    }
  }

  static void loadsFixtureBundleByExactVersionString() {
    test("loads the fixture bundle by its exact version string", () -> {
      DwcDpSchemaBundle bundle = DwcDpSchemaBundle.load("1.0_TEST", BundleLoaderSmokeTest.class.getClassLoader());
      require(bundle.version().equals("1.0_TEST"), "expected version 1.0_TEST, got " + bundle.version());
      require(bundle.tableNames().equals(Set.of("event", "occurrence")),
          "expected {event, occurrence}, got " + bundle.tableNames());
      require(bundle.coordinate().equals("dwc-dp:1.0_TEST"), "unexpected coordinate: " + bundle.coordinate());
    });
  }

  static void profileUrlComesFromDollarIdVerbatim() {
    test("profile URL is read from $id verbatim, never rewritten", () -> {
      DwcDpSchemaBundle bundle = DwcDpSchemaBundle.load("1.0_TEST", BundleLoaderSmokeTest.class.getClassLoader());
      String expected = "https://dwc-prerelease.rs.tdwg.org/dwc-dp/1.0_TEST/dwc-dp-profile.json";
      require(bundle.profileUrl().orElseThrow().equals(expected),
          "expected the prerelease $id verbatim, got " + bundle.profileUrl());
    });
  }

  static void tableNamesComeFromIndexNotFromEnumeration() {
    test("table schemas are loaded via index.json url, without any directory listing", () -> {
      DwcDpSchemaBundle bundle = DwcDpSchemaBundle.load("1.0_TEST", BundleLoaderSmokeTest.class.getClassLoader());
      TableSchema event = bundle.schema("event").orElseThrow();
      require(event.fields().size() == 6, "expected 6 fields on event, got " + event.fields().size());
      require(event.primaryKey().equals(java.util.List.of("event_pk")),
          "expected primary key [event_pk], got " + event.primaryKey());

      TableSchema occurrence = bundle.schema("occurrence").orElseThrow();
      require(occurrence.foreignKeys().size() == 1, "expected one FK on occurrence");
      require(occurrence.foreignKeys().get(0).refResource().equals("event"),
          "expected occurrence.event_fk to reference 'event'");
    });
  }

  static void unknownVersionListsAvailableOnes() {
    test("an unknown version name fails with the available versions listed", () -> {
      boolean threw = false;
      try {
        DwcDpSchemaBundle.load("2.0_DOES_NOT_EXIST", BundleLoaderSmokeTest.class.getClassLoader());
      } catch (IllegalArgumentException e) {
        threw = true;
        require(e.getMessage().contains("1.0_TEST"), "error should list available versions, got: " + e.getMessage());
      }
      require(threw, "expected an unknown bundle version to fail loudly");
    });
  }

  static void versionMismatchBetweenBundlesAndIndexFailsLoudly() throws Exception {
    test("a version mismatch between bundles.json and index.json fails loudly, doesn't guess", () -> {
      Path tmp = Files.createTempDirectory("dwcdp-bundle-mismatch");
      Path schemas = tmp.resolve("schemas");
      Files.createDirectories(schemas.resolve("bad/table-schemas"));
      Files.writeString(schemas.resolve("bundles.json"),
          "{\"bundles\":[{\"version\":\"bad\",\"index\":\"bad/index.json\",\"profile\":\"bad/dwc-dp-profile.json\"}]}");
      // index.json disagrees with bundles.json about the version.
      Files.writeString(schemas.resolve("bad/index.json"),
          "{\"version\":\"different\",\"tableSchemas\":[]}");
      Files.writeString(schemas.resolve("bad/dwc-dp-profile.json"),
          "{\"$id\":\"https://example.org/profile.json\"}");

      // Explicit null parent: with the default (parent-first) delegation, this loader would find
      // the *real* fixture's schemas/bundles.json already on the test classpath before ever
      // looking at this temp directory, since both use the same "schemas/..." relative path.
      URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
      boolean threw = false;
      try {
        DwcDpSchemaBundle.load("bad", loader);
      } catch (IllegalStateException e) {
        threw = true;
        require(e.getMessage().contains("bad") && e.getMessage().contains("different"),
            "expected the mismatch error to name both versions, got: " + e.getMessage());
      }
      require(threw, "a version mismatch must fail loudly rather than silently pick one");
    });
  }

  static void tableNameMismatchBetweenIndexAndProfileFailsLoudly() throws Exception {
    test("a table-name mismatch between index.json and the profile enum fails loudly", () -> {
      Path tmp = Files.createTempDirectory("dwcdp-bundle-tablemismatch");
      Path schemas = tmp.resolve("schemas");
      Files.createDirectories(schemas.resolve("bad2/table-schemas"));
      Files.writeString(schemas.resolve("bundles.json"),
          "{\"bundles\":[{\"version\":\"bad2\",\"index\":\"bad2/index.json\",\"profile\":\"bad2/dwc-dp-profile.json\"}]}");
      Files.writeString(schemas.resolve("bad2/index.json"), """
          {"version":"bad2","tableSchemas":[
            {"name":"widget","url":"table-schemas/widget.json"}
          ]}
          """);
      Files.writeString(schemas.resolve("bad2/table-schemas/widget.json"),
          "{\"fields\":[{\"name\":\"widget_pk\",\"type\":\"string\"}]}");
      // Profile enum names a different table than the index does.
      Files.writeString(schemas.resolve("bad2/dwc-dp-profile.json"), """
          {"$id":"https://example.org/profile.json",
           "$defs":{"dwc-dp-resource-names":{"enum":["gadget"]}}}
          """);

      URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
      boolean threw = false;
      try {
        DwcDpSchemaBundle.load("bad2", loader);
      } catch (IllegalStateException e) {
        threw = true;
        require(e.getMessage().contains("widget") && e.getMessage().contains("gadget"),
            "expected the mismatch error to name both tables, got: " + e.getMessage());
      }
      require(threw, "an index/profile table-name mismatch must fail loudly");
    });
  }

  // ---------------------------------------------------------------- helpers

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
