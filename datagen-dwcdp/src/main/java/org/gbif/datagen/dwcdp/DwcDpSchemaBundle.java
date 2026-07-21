package org.gbif.datagen.dwcdp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.schema.TableSchema;

/**
 * Loads a DwC-DP schema bundle from the {@code dwc-dp-schemas} jar on the classpath.
 *
 * <p>Layout, which is followed exactly:
 *
 * <pre>
 * schemas/bundles.json
 * schemas/{version}/index.json
 * schemas/{version}/dwc-dp-profile.json
 * schemas/{version}/table-schemas/{name}.json
 * </pre>
 *
 * <p>Nothing enumerates a classpath directory. That is not merely a preference: listing a
 * classpath directory is not portable — it works for {@code file:} URLs, needs a JAR FileSystem
 * for {@code jar:} URLs, and fails outright under nested/shaded jars, which is exactly where this
 * runs (Spark assemblies). Every read here is a {@code getResourceAsStream} on a path derived
 * from a document, so any classloader works.
 *
 * <p>{@code index.json} supplies each table's {@code url}, so name-to-path is read rather than
 * guessed at with a filename template.
 */
public final class DwcDpSchemaBundle implements SchemaBundle {

  private static final String ROOT = "schemas";
  private static final String BUNDLES = ROOT + "/bundles.json";

  private final String version;
  private final Map<String, TableSchema> schemas;
  private final String profileUrl;

  private DwcDpSchemaBundle(String version, Map<String, TableSchema> schemas, String profileUrl) {
    this.version = version;
    this.schemas = schemas;
    this.profileUrl = profileUrl;
  }

  /** Loads the bundle whose {@code version} matches exactly, e.g. {@code "0.1"} or {@code "1.0_DEV"}. */
  @SuppressWarnings("unchecked")
  public static DwcDpSchemaBundle load(String version, ClassLoader loader) {
    Map<String, Object> bundles = readJson(BUNDLES, loader);
    Object listObj = bundles.get("bundles");
    if (!(listObj instanceof List)) {
      throw new IllegalStateException(BUNDLES + " has no 'bundles' array");
    }

    Map<String, Object> entry = null;
    Set<String> available = new TreeSet<>();
    for (Object o : (List<Object>) listObj) {
      Map<String, Object> b = (Map<String, Object>) o;
      String v = String.valueOf(b.get("version"));
      available.add(v);
      if (v.equals(version)) {
        entry = b;
      }
    }
    if (entry == null) {
      throw new IllegalArgumentException(
          "Unknown DwC-DP bundle \"" + version + "\". Available: " + available);
    }

    String indexPath = ROOT + "/" + entry.get("index");
    String profilePath = ROOT + "/" + entry.get("profile");

    Map<String, Object> index = readJson(indexPath, loader);
    crossCheckVersion(version, index, indexPath);

    String base = indexPath.substring(0, indexPath.lastIndexOf('/') + 1);
    Map<String, TableSchema> schemas = loadTables(index, base, loader, indexPath);

    Map<String, Object> profile = readJson(profilePath, loader);
    String profileUrl = profileUrl(profile, profilePath);
    crossCheckTableNames(schemas.keySet(), profile, profilePath, indexPath);

    return new DwcDpSchemaBundle(version, Map.copyOf(schemas), profileUrl);
  }

  public static DwcDpSchemaBundle load(String version) {
    return load(version, Thread.currentThread().getContextClassLoader() != null
        ? Thread.currentThread().getContextClassLoader()
        : DwcDpSchemaBundle.class.getClassLoader());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, TableSchema> loadTables(Map<String, Object> index, String base,
                                                     ClassLoader loader, String indexPath) {
    Object tables = index.get("tableSchemas");
    if (!(tables instanceof List)) {
      throw new IllegalStateException(indexPath + " has no 'tableSchemas' array");
    }
    Map<String, TableSchema> schemas = new LinkedHashMap<>();
    for (Object o : (List<Object>) tables) {
      Map<String, Object> t = (Map<String, Object>) o;
      String name = String.valueOf(t.get("name"));
      String url = String.valueOf(t.get("url"));
      Map<String, Object> json = readJson(base + url, loader);
      schemas.put(name, TableSchema.fromJson(name, json));
    }
    return schemas;
  }

  /** The profile URL is read from {@code $id}, verbatim. It is never synthesized or rewritten. */
  private static String profileUrl(Map<String, Object> profile, String path) {
    Object id = profile.get("$id");
    if (id == null) {
      throw new IllegalStateException(path + " has no '$id' to use as the profile URL");
    }
    return String.valueOf(id);
  }

  private static void crossCheckVersion(String version, Map<String, Object> index, String indexPath) {
    Object indexVersion = index.get("version");
    if (indexVersion != null && !version.equals(String.valueOf(indexVersion))) {
      throw new IllegalStateException("Bundle mismatch: bundles.json lists version \"" + version
          + "\" but " + indexPath + " declares \"" + indexVersion + "\". This is a bug in the"
          + " dwc-dp-schemas artifact; refusing to guess which is correct.");
    }
  }

  /**
   * The index's table list and the profile's {@code dwc-dp-resource-names} enum must agree. If
   * they don't, the schemas artifact is internally inconsistent — better learned at bundle-load
   * than at row 4000.
   */
  @SuppressWarnings("unchecked")
  private static void crossCheckTableNames(Set<String> indexNames, Map<String, Object> profile,
                                           String profilePath, String indexPath) {
    Object defs = profile.get("$defs");
    if (!(defs instanceof Map)) {
      return;
    }
    Object resourceNames = ((Map<String, Object>) defs).get("dwc-dp-resource-names");
    if (!(resourceNames instanceof Map)) {
      return;
    }
    Object enumValues = ((Map<String, Object>) resourceNames).get("enum");
    if (!(enumValues instanceof List)) {
      return;
    }

    Set<String> profileNames = new TreeSet<>();
    for (Object v : (List<Object>) enumValues) {
      profileNames.add(String.valueOf(v));
    }

    Set<String> onlyInIndex = new TreeSet<>(indexNames);
    onlyInIndex.removeAll(profileNames);
    Set<String> onlyInProfile = new TreeSet<>(profileNames);
    onlyInProfile.removeAll(indexNames);

    if (!onlyInIndex.isEmpty() || !onlyInProfile.isEmpty()) {
      List<String> problems = new ArrayList<>();
      if (!onlyInIndex.isEmpty()) {
        problems.add("in " + indexPath + " but not in the profile enum: " + onlyInIndex);
      }
      if (!onlyInProfile.isEmpty()) {
        problems.add("in " + profilePath + " enum but not in the index: " + onlyInProfile);
      }
      throw new IllegalStateException("dwc-dp-schemas is internally inconsistent — "
          + String.join("; ", problems));
    }
  }

  private static Map<String, Object> readJson(String path, ClassLoader loader) {
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Not found on classpath: " + path
                                        + ". Is the dwc-dp-schemas jar on the classpath?");
      }
      return JsonSupport.readObject(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading " + path, e);
    }
  }

  @Override
  public Set<String> tableNames() {
    return schemas.keySet();
  }

  @Override
  public Optional<TableSchema> schema(String tableName) {
    return Optional.ofNullable(schemas.get(tableName));
  }

  @Override
  public Optional<String> profileUrl() {
    return Optional.of(profileUrl);
  }

  @Override
  public String coordinate() {
    return "dwc-dp:" + version;
  }

  public String version() {
    return version;
  }
}
