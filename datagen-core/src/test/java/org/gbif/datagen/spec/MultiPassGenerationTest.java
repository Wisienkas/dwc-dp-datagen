package org.gbif.datagen.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.smoke.FixtureSchemaBundle;
import org.junit.jupiter.api.Test;

/**
 * Proves the multi-pass field generation fix: a generator (chiefly {@code template()}) that
 * depends on another field on the same row must not fail just because that field happens to sit
 * later in schema order — the engine retries once the dependency exists. Three distinct outcomes
 * are covered, since "retry" is only correct for one of them:
 *
 * <ul>
 *   <li>dependency not yet generated -&gt; retry, then succeed once it exists
 *   <li>dependency generated but null -&gt; terminal failure immediately, no retry (retrying
 *       would never help; the value is final for that row)
 *   <li>dependency never resolves (a genuine cycle) -&gt; fails after one full pass makes zero
 *       progress, not an infinite loop
 * </ul>
 *
 * <p>The "badge" fixture table deliberately places {@code label} (which templates off
 * {@code badge_pk}) <em>before</em> {@code badge_pk} in schema field order — the exact shape of
 * the bug this fix addresses. On the pre-fix engine, this test fails with
 * "template on badge.label references 'badge_pk', which is null or not yet generated" thrown
 * from pass one; on the fixed engine it passes in two passes.
 */
class MultiPassGenerationTest {

  private SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  @Test
  void templateResolvesAcrossPassesWhenReferencedFieldIsLaterInSchemaOrder() {
    // "badge" schema order is [label, badge_pk] — label's template references badge_pk, which
    // has not run yet on pass one. This must not throw; it must retry and succeed on pass two.
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("badge")
      .allFields()
      .rows(50)
      .field("label", Gen.template("BADGE-{badge_pk}"))
      .build();

    GeneratedPackage pkg = spec.generate(1);

    for (Row row : pkg.rows("badge")) {
      String pk = row.getString("badge_pk");
      String label = row.getString("label");
      assertEquals("BADGE-" + pk, label,
                   "label must contain this row's actual badge_pk, proving the retry saw the real"
                   + " generated value rather than failing or substituting a placeholder");
    }
  }

  @Test
  void templateFailsImmediatelyAndTerminallyWhenReferencedFieldIsNull() {
    // "voucher" schema order is [voucher_pk, code, label] — code IS generated before label runs
    // (no ordering problem), but its generator always returns null. Retrying can never fix
    // this: the value is final for the row. Must fail on the first attempt, not after retries.
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("voucher")
        .fields("code", "label")
        .rows(10)
        .field("code", Gen.<String>constant(null))
        .field("label", Gen.template("CODE-{code}"))
        .build()
        .generate(1));

    assertTrue(ex.getMessage().contains("generated to null"),
               "expected a terminal null-value error, not a retry/cycle error, got: " + ex.getMessage());
  }

  @Test
  void mutualDependencyFailsAfterOneFullPassMakesNoProgressRatherThanLoopingForever() {
    // "loop" fields 'a' and 'b' each require the other via deriveAfter, so neither can ever
    // resolve on any pass — a genuine cycle, not just an unlucky field order. Must fail fast
    // (assertTimeoutPreemptively bounds it, in case a regression reintroduces an actual
    // infinite loop instead of the "zero progress -> throw" guard) with a message that
    // distinguishes this from both other failure modes above.
    IllegalStateException ex = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
      assertThrows(IllegalStateException.class, () ->
        Blueprint.forSchema(bundle())
          .resource("loop")
          .fields("a", "b")
          .rows(5)
          .field("a", Gen.deriveAfter(List.of("b"), row -> "A-" + row.getString("b")))
          .field("b", Gen.deriveAfter(List.of("a"), row -> "B-" + row.getString("a")))
          .build()
          .generate(1)));

    assertTrue(ex.getMessage().contains("no further progress") || ex.getMessage().toLowerCase().contains("cyclic"),
               "expected a cycle/no-progress error naming the unresolved fields, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("'a'") || ex.getMessage().contains("waiting on"),
               "error should name the specific fields stuck waiting on each other, got: " + ex.getMessage());
  }

  @Test
  void sameSeedStillProducesByteIdenticalOutputAcrossMultiplePasses() {
    // Guards against the fix accidentally making retried fields draw from a different random
    // stream than a same-named field that resolved on pass one would have — per-cell seeding
    // is keyed on (resource, field, rowIndex), not on which pass a field happened to resolve in,
    // so this must hold regardless of how many passes a row took.
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("badge")
      .allFields()
      .rows(30)
      .field("label", Gen.template("BADGE-{badge_pk}"))
      .build();

    List<Row> a = spec.generate(99).rows("badge");
    List<Row> b = spec.generate(99).rows("badge");

    for (int i = 0; i < a.size(); i++) {
      assertEquals(a.get(i).get("badge_pk"), b.get(i).get("badge_pk"));
      assertEquals(a.get(i).get("label"), b.get(i).get("label"));
    }
  }

  @Test
  void templateReferencingAnUnknownFieldFailsAtBuildWithSuggestion() {
    // Distinct from mutualDependencyFailsAfterOneFullPassMakesNoProgress: that one is a genuine
    // cycle, only detectable at generation time. This one is a bad field name, entirely knowable
    // before any row is generated — and prior to this fix, both failed identically at generation
    // time with the same "no further progress" message, which is exactly what made the real bug
    // this test is modeling hard to diagnose in practice.
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("badge")
        .allFields()
        .rows(10)
        .field("label", Gen.template("BADGE-{bagde_pk}")) // typo: bagde_pk, not badge_pk
        .build());

    assertTrue(ex.getMessage().contains("bagde_pk"), "error should name the bad reference, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("badge_pk"),
               "error should suggest the real field name, got: " + ex.getMessage());
  }

  @Test
  void templateReferencingARealButUnselectedFieldDoesNotSuggestItself() {
    // "voucher" has fields [voucher_pk, code, label]. Select only voucher_pk and label — leave
    // 'code' unselected — then template label off 'code' anyway. 'code' is a real schema field,
    // correctly spelled, just not selected here. The error must not suggest 'code' back as if
    // fixing a typo would help; the actual fix is selecting it (or referencing something else).
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("voucher")
        .fields("voucher_pk", "label")
        .rows(10)
        .field("label", Gen.template("V-{code}"))
        .build());

    assertTrue(ex.getMessage().contains("'code'"), "error should name the unselected reference, got: " + ex.getMessage());
    assertFalse(ex.getMessage().contains("Did you mean 'code'"),
                "must not suggest 'code' back as a typo fix when 'code' IS the (correctly spelled,"
                + " merely unselected) reference — that would send someone chasing a typo that"
                + " isn't one, got: "
                + ex.getMessage());
  }

  @Test
  void refWithBadTargetFieldSuggestsFromTargetTableSchema() {
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("widget").rows(10)
        .resource("gadget")
        .rows(20)
        .ref("widget_fk", "widget", "widgt_pk").and() // typo: widgt_pk, not widget_pk
        .build());

    assertTrue(ex.getMessage().contains("widgt_pk"), "error should name the bad reference, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("Did you mean 'widget_pk'"),
               "suggestion should be drawn from widget's fields, not gadget's, got: " + ex.getMessage());
  }

  @Test
  void refWithBadSourceFieldSuggestsFromOwnTableSchema() {
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("widget").rows(10)
        .resource("gadget")
        .rows(20)
        .ref("widgt_fk", "widget", "widget_pk").and() // typo on gadget's own side
        .build());

    assertTrue(ex.getMessage().contains("widgt_fk"), "error should name the bad reference, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("Did you mean 'widget_fk'"),
               "suggestion should be drawn from gadget's own fields, got: " + ex.getMessage());
  }

  @Test
  void selfRefWithBadTargetFieldSuggestsFromOwnTableSchema() {
    IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("widget")
        .rows(10)
        .selfRef("parent_fk", "widgt_pk") // typo: widgt_pk, not widget_pk
        .build());

    assertTrue(ex.getMessage().contains("widgt_pk"), "error should name the bad reference, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("Did you mean 'widget_pk'"),
               "selfRef's target schema is its own table, suggestion should reflect that, got: " + ex.getMessage());
  }

  @Test
  void unknownFieldWithNoCloseMatchListsAllAvailableFields() {
    // "widget" has no field remotely close to this by edit distance — must fall back to listing
    // every real field, not silently produce a bare "no such field." with nothing actionable.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
      Blueprint.forSchema(bundle())
        .resource("widget")
        .rows(10)
        .field("completelyUnrelatedFieldName", Gen.constant("x")));

    assertTrue(ex.getMessage().contains("Available fields:"),
               "expected a fallback field listing when no close match exists, got: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("widget_pk") && ex.getMessage().contains("status"),
               "the listing should actually name widget's real fields, got: " + ex.getMessage());
  }
}
