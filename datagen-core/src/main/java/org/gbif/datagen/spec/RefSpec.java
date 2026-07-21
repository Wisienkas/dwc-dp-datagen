package org.gbif.datagen.spec;

/**
 * A reference from a field on this resource to a field on another (or the same) resource.
 *
 * <p>Two independent axes, and keeping them independent is the point:
 *
 * <ul>
 *   <li><b>Generation</b> — always. The ref drives topological ordering and supplies the default
 *       {@code lookup} generator for its source field.
 *   <li><b>{@link #declared()}</b> — whether the relation appears in the emitted
 *       {@code datapackage.json}.
 * </ul>
 *
 * <p>Setting {@code declared(false)} produces a package whose data honours a relation its
 * descriptor is silent about — the implicit-reference shape that real publishers emit. Generating
 * the same dataset twice, once declared and once not, yields byte-identical CSVs and differing
 * descriptors, which isolates exactly where a pipeline reads the descriptor versus the data.
 */
public record RefSpec(String field, String targetResource, String targetField, String predicate,
                      boolean declared) {

  public static RefSpec of(String field, String targetResource, String targetField) {
    return new RefSpec(field, targetResource, targetField, null, true);
  }

  public RefSpec predicate(String predicate) {
    return new RefSpec(field, targetResource, targetField, predicate, declared);
  }

  /** Whether to emit this relation in the descriptor's {@code foreignKeys}. */
  public RefSpec declared(boolean declared) {
    return new RefSpec(field, targetResource, targetField, predicate, declared);
  }

  public boolean isSelfReference(String owningResource) {
    return targetResource.equals(owningResource);
  }
}
