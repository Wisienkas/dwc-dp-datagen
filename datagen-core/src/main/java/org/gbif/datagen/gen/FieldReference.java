package org.gbif.datagen.gen;

import java.util.List;

/**
 * Implemented by generators that read another field's value off the same row (chiefly
 * {@link Gen#template} and {@link Gen#deriveAfter}), so the field names they depend on can be
 * validated against the resource's actual selected fields at {@code Blueprint.build()} time —
 * before any row generation happens — rather than surfacing as an ambiguous "no further
 * progress" error that looks identical to a genuine cycle.
 */
public interface FieldReference {
  List<String> referencedFields();
}
