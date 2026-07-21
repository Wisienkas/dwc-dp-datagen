package org.gbif.datagen.dwcdp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;
import org.gbif.datagen.write.MapDescriptorSerializer;

import static org.gbif.datagen.dwcdp.DwcGen.Area.DENMARK;
import static org.gbif.datagen.dwcdp.DwcGen.area;

/**
 * End-to-end proof that {@code datagen-core} and {@code datagen-dwcdp} compose: loads the fixture
 * bundle, builds an event/occurrence package using {@link DwcGen}'s date and area generators plus
 * term-keyed globals, generates, and writes real files.
 */
public final class EndToEndSmokeTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) throws Exception {
    generatesEventOccurrenceWithDwcGen();

    System.out.println();
    System.out.println(passed + " passed, " + failed + " failed");
    if (failed > 0) {
      System.exit(1);
    }
  }

  static void generatesEventOccurrenceWithDwcGen() throws Exception {
    test("event/occurrence package with DwcGen dates, area, and zipf fan-out", () -> {
      DwcDpSchemaBundle bundle = DwcDpSchemaBundle.load("1.0_TEST", EndToEndSmokeTest.class.getClassLoader());

      DataPackageSpec spec = Blueprint.forSchema(bundle)
        .global(Key.termOfField(bundle, "event", "decimalLatitude"), area(DENMARK).noise(0.01))
        .global(Key.termOfField(bundle, "event", "decimalLongitude"), area(DENMARK).noise(0.01))
        .resource("event")
              .allFields()
              .rows(40)
              .field("eventDate", DwcGen.dates().between(2015, 2025))
              .selfRef("parentEvent_fk", "event_pk").roots(0.2)
          .resource("occurrence")
              .allFields()
              .per("event", Dist.zipf(1.2).max(30))
              .field("scientificName", DwcGen.scientificName())
          .build();

      GeneratedPackage pkg = spec.generate(123);

      List<Row> events = pkg.rows("event");
      require(events.size() == 40, "expected 40 events, got " + events.size());
      for (Row e : events) {
        double lat = ((Number) e.get("decimalLatitude")).doubleValue();
        double lon = ((Number) e.get("decimalLongitude")).doubleValue();
        // Denmark bbox plus noise: generous tolerance, just proving the area binding actually fired.
        require(lat > 50 && lat < 62, "decimalLatitude should land near Denmark, got " + lat);
        require(lon > 3 && lon < 20, "decimalLongitude should land near Denmark, got " + lon);
        String date = e.getString("eventDate");
        require(date != null && date.length() >= 4, "eventDate should be non-trivial, got " + date);
      }

      List<Row> occurrences = pkg.rows("occurrence");
      require(!occurrences.isEmpty(), "expected some occurrences from zipf fan-out");
      for (Row o : occurrences) {
        require(o.get("event_fk") != null, "every occurrence must resolve its event_fk");
        String name = o.getString("scientificName");
        require(name != null && name.split(" ").length == 2, "expected a binomial name, got " + name);
      }

      Path dir = Files.createTempDirectory("datagen-e2e-smoke");
      pkg.writeTo(dir, new MapDescriptorSerializer());
      require(Files.exists(dir.resolve("event.csv")), "event.csv should exist");
      require(Files.exists(dir.resolve("occurrence.csv")), "occurrence.csv should exist");
      require(Files.exists(dir.resolve("datapackage.json")), "datapackage.json should exist");
      require(Files.exists(dir.resolve("datagen-run.json")), "datagen-run.json sidecar should exist");
      System.out.println("      wrote " + events.size() + " events, " + occurrences.size()
          + " occurrences to " + dir);
    });
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
      t.printStackTrace();
      failed++;
    }
  }
}
