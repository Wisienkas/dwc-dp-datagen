package org.gbif.datagen.recipes;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

import org.gbif.datagen.dwcdp.DwcDp;
import org.gbif.datagen.dwcdp.DwcDpDescriptorSerializer;
import org.gbif.datagen.dwcdp.DwcDpSchemaBundle;
import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.write.PackageWriter;

/**
 * Shared CLI plumbing for the recipes below: bundle loading, seeding, writing. Each recipe
 * contributes only its resource graph via {@code specBuilder}.
 *
 * <p>Usage: {@code java -cp ... RecipeClass [outputDir] [seed] [bundleVersion]}
 */
final class RecipeMain {

  private RecipeMain() {
  }

  static void run(String[] args, String defaultOutputDirName, Function<SchemaBundle, DataPackageSpec> specBuilder) {
    Path outputDir = Path.of(args.length > 0 ? args[0] : defaultOutputDirName);
    long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
    String bundleVersion = args.length > 2 ? args[2] : "1.0_DEV";

    DwcDpSchemaBundle bundle = DwcDp.bundle(bundleVersion);
    DataPackageSpec spec = specBuilder.apply(bundle);
    GeneratedPackage pkg = spec.generate(seed);

    Path written = new PackageWriter(new DwcDpDescriptorSerializer())
      .name(defaultOutputDirName)
      .id("https://example.org/datagen/" + defaultOutputDirName)
      .created(Instant.now().toString())
      .write(pkg, outputDir);

    System.out.println("Wrote " + defaultOutputDirName + " to " + written);
    long total = 0;
    for (String r : spec.generationOrder()) {
      int n = pkg.rowCount(r);
      total += n;
      System.out.printf("  %-24s %,10d rows%n", r, n);
    }
    System.out.printf("  %-24s %,10d rows%n", "TOTAL", total);
  }
}
