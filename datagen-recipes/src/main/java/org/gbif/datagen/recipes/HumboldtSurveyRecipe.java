package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** Standardized sampling-effort data (the "Humboldt extension" tables): survey/survey-target
 *  describe what was being looked for, so absence can be inferred and not just presence.
 *  Roughly a third of occurrences are opportunistic records from the same events, not part of
 *  any standardized survey — survey_fk is deliberately null for those.
 *
 *  <p>survey-target / survey-survey-target are included specifically to exercise both branches
 *  of {@code HumboldtExtensionBuilder}: {@code Dist.uniform(0, 3)} on the junction table means
 *  roughly a third of surveys get zero survey-target rows (single Humboldt row per event, no
 *  fan-out) while the rest get 1-3 (fan-out — one Humboldt extension row per survey-target,
 *  all sharing the same eventID). Previously neither table was generated at all, so the
 *  fan-out branch was never hit by this dataset.
 *
 *  <pre>
 *  event (2,500)              survey (1,800)             survey-target (40)
 *   site-visit episodes   ┌──event_fk──▶ standardized      standardized target
 *          │              │              sampling             definitions
 *          │ event_fk     │                   │                    ▲
 *          ▼              │                   │ survey_fk          │ surveyTarget_fk
 *  occurrence (~30,000) ──┘                   ▼                    │
 *   sighting/absence              survey-survey-target ────────────┘
 *   records                       (0-3 per survey; ~1/3 get none)
 *          │
 *          │ organism_fk (25%)
 *          ▼
 *     organism (4,000)
 *  </pre>
 *  */
public final class HumboldtSurveyRecipe {

  private HumboldtSurveyRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.NORWAY).latitude().noise(0.08))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.NORWAY).longitude().noise(0.08))

      .resource("event")
      .allFields()
      .rows(2_500)
      // eventID is a weakPk (schema's natural DwC identifier) but not req+unique. The
      // schema-default placeholder already seems to vary per row (e.g. event-77, event-245 in
      // the pipeline logs), so this probably isn't fixing a live collision — kept explicit
      // anyway since this field feeds GBIF ID assignment downstream and I'd rather not depend
      // on undocumented default behavior for it.
      .field("eventID", Gen.uuid())
      .field("eventType", Gen.weighted("transect walk", 0.5, "camera trap deployment", 0.3,
                                       "BioBlitz", 0.2))
      .field("eventDate", DwcGen.dates().between(2019, 2025).formats(DwcGen.DateFormat.ISO_DAY))
      .field("fieldNotes", Gen.constant("standardized transect, fixed duration"))

      .resource("survey")
      .allFields()
      .rows(1_800)
      .field("surveyID", Gen.uuid()) // same weakPk-not-req+unique gap as eventID
      .field("event_fk", Gen.lookup("event", "event_pk"))
      .field("samplingEffortProtocol", Gen.constant("standardized transect, fixed duration"))
      .field("isAbundanceReported", Gen.weighted(true, 0.7, false, 0.3))
      .field("isAbsenceReported", Gen.weighted(true, 0.6, false, 0.4))
      .field("geospatialScopeAreaUnit", Gen.constant("m2"))
      .field("totalAreaSampledUnit", Gen.constant("m2"))

      // Standardized target vocabulary — deliberately a small, reused pool. Real Humboldt
      // datasets draw survey targets from a handful of standard protocol definitions, not one
      // bespoke target per survey.
      .resource("survey-target")
      .allFields()
      .rows(40)
      .field("surveyTargetID", Gen.uuid()) // same weakPk-not-req+unique gap as eventID
      .field("surveyTargetDescription", Gen.sample(
        "all vascular plants > 1m height",
        "breeding birds within 100m radius",
        "all bat species, mist-net and acoustic",
        "epigeic ants",
        "understory vascular flora"))
      .field("isSurveyTargetFullyReported", Gen.weighted(true, 0.65, false, 0.35))

      // Junction: which target(s) each survey was scoped to. Dist.uniform(0, 3) means some
      // surveys get no rows here at all (HumboldtExtensionBuilder's single-row-per-survey
      // path) and the rest get 1-3 (its fan-out path, joined via survey-survey-target ->
      // survey-target) — both branches now get real coverage from this recipe.
      .resource("survey-survey-target")
      .allFields()
      .per("survey", Dist.uniform(0, 3))
      // surveyTarget_fk left to the schema-fk auto default: a real lookup into the
      // survey-target pool, with replacement — several surveys legitimately share the same
      // standardized target definition.

      .resource("organism")
      .allFields()
      .rows(4_000)
      .field("organismID", Gen.uuid())
      .field("organismScope", Gen.constant("individual"))

      .resource("occurrence")
      .allFields()
      // Was Dist.zipf(1.3).max(25), drawn independently per event. With s=1.3 that's skewed
      // enough that plausibly most of the 2,500 events landed at 0 — starving the occurrence
      // extension EventCoreBuilder attaches per event, which is the actual GBIF-ID-bearing
      // payload for this dataset. That's the more likely explanation for "No records with
      // valid GBIF ID! totalCount 0", not an identifier collision. Dist.uniform(1, 25)
      // guarantees every event gets at least one; trades away the zipf skew, so swap back if
      // the long-tail shape matters more than this guarantee.
      .per("event", Dist.uniform(1, 25))
      .field("scientificName", DwcGen.scientificName())
      // occurrenceID: same weakPk-not-req+unique note as eventID above — kept explicit for the
      // same reason (feeds GBIF ID assignment), not because of a confirmed collision.
      .field("occurrenceID", Gen.uuid())
      .field("occurrenceStatus", Gen.weighted("present", 0.8, "absent", 0.2))
      .field("organismID", Gen.lookup("organism", "organismID").nullChance(0.75))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "humboldt-survey", HumboldtSurveyRecipe::build);
  }
}
