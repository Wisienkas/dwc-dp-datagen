package org.gbif.datagen.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.spec.ResourceSpec;

/** Writes a generated package to disk. */
public final class PackageWriter {

  private final DescriptorSerializer descriptorSerializer;
  private String id = "https://example.org/dwc-dp-generated-dataset";
  private String name;
  private String created = Instant.now().toString();
  private boolean writeRunMetadata = true;

  public PackageWriter(DescriptorSerializer descriptorSerializer) {
    this.descriptorSerializer = Objects.requireNonNull(descriptorSerializer, "descriptorSerializer");
  }

  public PackageWriter id(String id) {
    this.id = id;
    return this;
  }

  /**
   * Sets the descriptor's {@code name}. Required at {@link #write} — unlike {@code id}, which
   * has an obviously-a-placeholder default, a missing {@code name} wouldn't fail or look wrong,
   * it would just silently produce a descriptor without one. Frictionless requires {@code name}
   * to be lowercase, with only alphanumerics, {@code -}, {@code .}, {@code _} — not validated
   * here, but worth conforming to if the descriptor needs to pass strict validation downstream.
   */
  public PackageWriter name(String name) {
    this.name = name;
    return this;
  }

  /** Fixing this makes output byte-reproducible; otherwise the descriptor carries a live timestamp. */
  public PackageWriter created(String created) {
    this.created = created;
    return this;
  }

  public PackageWriter writeRunMetadata(boolean write) {
    this.writeRunMetadata = write;
    return this;
  }

  public Path write(GeneratedPackage pkg, Path directory) {
    if (name == null) {
      throw new IllegalStateException("PackageWriter.name(...) was never set. A datapackage.json"
                                      + " without a name is a real gap most consumers require filled — set it explicitly"
                                      + " rather than relying on a default, since there's no name that's correct by default.");
    }
    try {
      Files.createDirectories(directory);

      for (ResourceSpec resource : pkg.spec().resources()) {
        CsvWriter.write(directory.resolve(resource.name() + ".csv"), resource.fieldNames(),
                        pkg.rows(resource.name()));
      }

      String descriptor = descriptorSerializer.serialize(pkg.spec(), id, name, created);
      Files.writeString(directory.resolve("datapackage.json"), descriptor, StandardCharsets.UTF_8);

      if (writeRunMetadata) {
        Files.writeString(directory.resolve("datagen-run.json"),
                          JsonSupport.writePretty(runMetadata(pkg)), StandardCharsets.UTF_8);
      }
      return directory;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing package to " + directory, e);
    }
  }

  private Map<String, Object> runMetadata(GeneratedPackage pkg) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("bundle", pkg.spec().bundle().coordinate());
    meta.put("name", name);
    meta.put("seed", pkg.seed());
    meta.put("generatedAt", created);
    Map<String, Object> counts = new LinkedHashMap<>();
    for (ResourceSpec resource : pkg.spec().resources()) {
      counts.put(resource.name(), pkg.rowCount(resource.name()));
    }
    meta.put("rowCounts", counts);
    meta.put("generationOrder", pkg.spec().generationOrder());
    return meta;
  }
}
