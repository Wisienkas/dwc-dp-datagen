package org.gbif.datagen.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.gbif.datagen.gen.Row;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.write.DescriptorSerializer;
import org.gbif.datagen.write.PackageWriter;

/** The result of generating a package: rows per resource, plus what it took to produce them. */
public record GeneratedPackage(DataPackageSpec spec, Map<String, List<Row>> rows, long seed) {

  public List<Row> rows(String resource) {
    List<Row> r = rows.get(resource);
    if (r == null) {
      throw new IllegalArgumentException("No resource '" + resource + "' in this package");
    }
    return r;
  }

  public int rowCount(String resource) {
    return rows(resource).size();
  }

  /** Writes CSVs, {@code datapackage.json}, and a run metadata sidecar. */
  public Path writeTo(Path directory, DescriptorSerializer descriptorSerializer) {
    return new PackageWriter(descriptorSerializer).write(this, directory);
  }
}
