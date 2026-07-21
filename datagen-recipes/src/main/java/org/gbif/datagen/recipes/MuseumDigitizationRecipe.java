package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** The kitchen sink: natural history digitization combining observations, specimens, and
 *  photographs in one dataset. Exercises every join covered by the other four recipes at once —
 *  strong FKs, weak (unenforced) relationships in both directions, self-references, and the
 *  occurrence-media many-to-many.
 *
 *  <pre>
 *    event (4,000)
 *                         collecting trips & site visits
 *                         (self-ref: parentEvent_fk, 85% root)
 *                                      │
 *                      ┌───────────────┼────────────────────┐
 *         event_fk     │               │ collectionEvent_fk │
 *      (zipf 1.2,      │               │ (60% populated,    │
 *       max 12)        ▼               │  independent link) │
 *               occurrence (~18,000)   │                    │
 *              observation records ────┼────────────────────┤
 *                     │        │       │                    │
 *        occurrence_fk│        │organismID (weak, 30%)      │
 *                     ▼        ▼                            │
 *     occurrence-identifier   organism (3,000)              │
 *       (~9,000, legacy #s)                                 │
 *                     │                                     │
 *     evidenceForOccurrenceID (weak, 80% linked,            │
 *      "material is evidence for this occurrence")          │
 *                     ▼                                     │
 *               material (~9,000)◀──────────────────────────┘
 *              specimen jars / mounts
 *                     │
 *       materialEntity_fk │ (uniform 0-1)
 *                     ▼
 *         material-identifier (~4,500)
 *
 *     occurrence (~18,000)                       media (8,000)
 *              │                                       │
 *   occurrence_fk│ (uniform 0-2)              media_fk │ (reused across occurrences)
 *              └──────────► occurrence-media (~18,000) ◄┘
 *
 *                                 media (8,000)
 *                                        │
 *                           media_fk     │ (uniform 0-1)
 *                                        ▼
 *                           media-identifier (~4,000)
 *  </pre>
 *  */
public final class MuseumDigitizationRecipe {

  private MuseumDigitizationRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.GLOBAL).latitude().noise(0.0))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.GLOBAL).longitude().noise(0.0))

      .resource("event")
      .allFields()
      .rows(4_000)
      // required, no enum, but examples name a controlled vocabulary of category values
      .field("eventCategory", Gen.weighted(
        "material collection", 0.55, "occurrence", 0.35, "survey", 0.10))
      .field("eventType", Gen.sample(java.util.List.of(
        "field collection", "accession event", "site visit")))
      .field("eventDate", DwcGen.dates().between(1950, 2025)
        .formats(DwcGen.DateFormat.ISO_DAY, DwcGen.DateFormat.PARTIAL_MONTH,
                 DwcGen.DateFormat.PARTIAL_YEAR))
      .field("year", DwcGen.DateGen.yearOf("eventDate"))
      .field("month", DwcGen.DateGen.monthOf("eventDate"))
      .field("day", DwcGen.DateGen.dayOf("eventDate"))
      // self-ref hierarchy (a broader collecting trip containing several site visits) —
      // mostly standalone events, a minority nested under a parent.
      .selfRef("parentEvent_fk", "event_pk").roots(0.85)

      .resource("organism")
      .allFields()
      .rows(3_000)
      .field("organismScope", Gen.constant("individual"))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.2).max(12))
      .field("scientificName", DwcGen.scientificName())
      // real vocabulary is detected/notDetected, not present/absent
      .field("occurrenceStatus", Gen.weighted("detected", 0.85, "notDetected", 0.15))
      .field("recordedBy", DwcGen.personNames(2))
      // organismID is a weak FK straight to organism.organismID — there is no organism_fk.
      // The auto-wired default has no null-modeling, so most occurrences would otherwise
      // get linked to a tracked organism; override to make that the minority case.
      .field("organismID", Gen.lookup("organism", "organismID").nullChance(0.7))
      // flat occurrence set for this recipe — no synthetic nesting hierarchy.
      .field("isPartOfOccurrence_fk", Gen.<String>constant(null))

      .resource("occurrence-identifier")
      .allFields()
      .per("occurrence", Dist.uniform(0, 1))
      .field("identifier", Gen.template("LEGACY-{occurrence_fk}"))
      .field("identifierType", Gen.constant("legacyAccessionNumber"))

      .resource("material")
      .allFields()
      .per("occurrence", Dist.uniform(0, 1))
      .field("materialEntityType", Gen.weighted("preserved", 0.7, "tissue", 0.3))
      .field("institutionCode", Gen.sample(java.util.List.of("NHMD", "SNM", "ZMUC", "GBIF-TEST")))
      .field("catalogNumber", Gen.sequence(500_000))
      // evidenceForOccurrenceID is the real (weak) relationship to occurrence — "this
      // material is evidence for that occurrence," not a derivation. anchored on the
      // occurrence, but not every specimen has a linked occurrence record (some are
      // accessioned independently), so override the anchor default's null modeling.
      .field("evidenceForOccurrenceID",
             Gen.<Object>lambda(ctx -> ctx.parentRow("occurrence").map(p -> p.get("occurrenceID")).orElse(null))
               .nullChance(0.2))
      // collectionEvent_fk is independent of the occurrence link entirely — a separate,
      // optional strong FK straight to event.
      .field("collectionEvent_fk", Gen.lookup("event", "event_pk").nullChance(0.4))
      // derivation/subsampling is the exception, not the rule, for a primary specimen.
      .field("derivationEvent_fk", Gen.lookup("event", "event_pk").nullChance(0.9))
      // no synthetic specimen-of-specimen hierarchy in this recipe.
      .field("isPartOfMaterialEntityID", Gen.<String>constant(null))
      .field("derivedFromMaterialEntityID", Gen.<String>constant(null))

      .resource("material-identifier")
      .allFields()
      .per("material", Dist.uniform(0, 1))
      .field("identifier", Gen.template("https://collections.example.org/mat/{materialEntity_fk}"))
      .field("identifierType", Gen.weighted("barcode", 0.6, "QR", 0.25, "accessionNumber", 0.15))

      .resource("media")
      .allFields()
      .rows(8_000)
      .field("mediaType", Gen.constant("StillImage"))
      .field("format", Gen.constant("image/jpeg"))
      .field("accessURI", Gen.template("https://collections.example.org/media/{media_pk}.jpg"))
      .field("title", Gen.template("Specimen photo {media_pk}"))
      // no synthetic media-of-media hierarchy in this recipe.
      .field("derivedFromMediaID", Gen.<String>constant(null))
      .field("isPartOfMediaID", Gen.<String>constant(null))

      .resource("occurrence-media")
      .allFields()
      .per("occurrence", Dist.uniform(0, 2))
      // media_fk left to the schema-fk auto default: a real lookup into the media pool,
      // with replacement — the same photo can legitimately illustrate more than one
      // occurrence, which is the many-to-many shape this table exists to model.

      .resource("media-identifier")
      .allFields()
      .per("media", Dist.uniform(0, 1))
      .field("identifier", Gen.template("https://collections.example.org/media-id/{media_fk}"))
      .field("identifierType", Gen.constant("URI"))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "museum-digitization", MuseumDigitizationRecipe::build);
  }
}
