package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** Herbarium digitization: event -> occurrence (field collection), weak-linked to material
 *  (the pressed specimen), with a subset of specimens having barcode/accession identifiers.
 *  Exercises the weak-reference join specifically (evidenceForOccurrenceID -> occurrenceID).
 *
 * <pre>
 *                event (5,000)
 *               collecting trips
 *                      │
 *                      │ event_fk  (zipf 1.1, max 6 per trip)
 *                      ▼
 *              occurrence (~22,000)
 *             field collection records
 *                      │
 *                      │ evidenceForOccurrenceID  (weak, 85% linked)
 *                      ▼
 *               material (~19,000)
 *              pressed/mounted specimens
 *                      │
 *                      │ material_fk  (uniform 0-2)
 *                      ▼
 *          material-identifier (~19,000)
 *           barcodes / QR codes / accession #s
 * </pre>
 *
 *  */
public final class HerbariumSpecimenRecipe {

  private HerbariumSpecimenRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.COSTA_RICA).latitude().noise(0.02))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.COSTA_RICA).longitude().noise(0.02))

      .resource("event")
      .allFields()
      .rows(5_000)
      .field("eventType", Gen.constant("collecting trip"))
      .field("eventDate", DwcGen.dates().between(2010, 2025).formats(DwcGen.DateFormat.ISO_DAY))
      .field("habitat", Gen.<String>sample(
          java.util.List.of("lowland rainforest", "cloud forest", "premontane wet forest"))
        .nullChance(0.1))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.1).max(6))
      .field("scientificName", DwcGen.scientificName())
      .field("recordedBy", DwcGen.personNames(3))
      // occurrence has no organism/survey concept in this domain — leave unselected via
      // allFields()+schema default is fine since neither is required.

      .resource("material")
      .allFields()
      .per("occurrence", Dist.uniform(0, 1)) // 0 or 1 specimen per field record
      .field("materialEntityType", Gen.constant("PreservedSpecimen"))
      .field("institutionCode", Gen.sample(java.util.List.of("INBio", "CR-Lankester", "MO", "NY")))
      .field("preparations", Gen.constant("pressed and mounted on herbarium sheet"))
      .field("catalogNumber", Gen.sequence(100_000))
      // 15% of specimens are accessioned from exchange with no field-collection record —
      // the weak FK's whole reason for being unenforced. Override explicitly: the
      // per(occurrence,...) anchor default would otherwise always populate it.
      .field("evidenceForOccurrenceID",
             Gen.<Object>lambda(ctx -> ctx.parentRow("occurrence").map(p -> p.get("occurrenceID")).orElse(null))
               .nullChance(0.15))

      .resource("material-identifier")
      .allFields()
      .per("material", Dist.uniform(0, 2))
      .field("identifierType", Gen.weighted("barcode", 0.6, "QR", 0.25, "accessionNumber", 0.15))
      .field("identifier", Gen.template("https://herbarium.example.org/specimen/{materialEntity_fk}"))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "herbarium-specimen", HerbariumSpecimenRecipe::build);
  }
}
