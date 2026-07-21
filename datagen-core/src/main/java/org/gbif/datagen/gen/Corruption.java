package org.gbif.datagen.gen;

import java.util.Random;

import org.gbif.datagen.engine.Seeds;

/**
 * Ways to make a value wrong on purpose.
 *
 * <p>An ingestion and validation pipeline has no test surface without invalid input — deliberately
 * broken data is half the interesting input space, not an afterthought.
 *
 * <p>These are per-cell. Per-record corruption (one entirely bad row, which is what real broken
 * data usually looks like) is a different mechanism and is deliberately not built here.
 */
public enum Corruption {

  /** Pushes a numeric value outside its schema {@code minimum}/{@code maximum}. */
  OUT_OF_RANGE {
    @Override
    Object apply(Object value, GenContext ctx) {
      if (!(value instanceof Number n)) {
        return value;
      }
      double max = ctx.fieldSchema().flatMap(f -> f.maximum()).orElse(n.doubleValue());
      double offset = 1 + new Random(Seeds.salt(ctx.seed(), "outOfRange")).nextDouble() * 999;
      double corrupted = max + offset;
      return ctx.fieldSchema().map(f -> f.isInteger()).orElse(false)
          ? (Object) (long) corrupted
          : (Object) corrupted;
    }
  },

  /** Replaces the value with text that will not parse as a date. */
  UNPARSEABLE_DATE {
    @Override
    Object apply(Object value, GenContext ctx) {
      String[] junk = {"spring 1910", "17IV1934", "1999-13-45", "no date", "????-??-??", "0000-00-00"};
      return junk[new Random(Seeds.salt(ctx.seed(), "unparseableDate")).nextInt(junk.length)];
    }
  },

  /** Pads with leading/trailing whitespace — trims that pipelines often forget. */
  WHITESPACE_PAD {
    @Override
    Object apply(Object value, GenContext ctx) {
      Random r = new Random(Seeds.salt(ctx.seed(), "whitespacePad"));
      String lead = " ".repeat(r.nextInt(3));
      String trail = r.nextBoolean() ? "\t" : "  ";
      return lead + value + trail;
    }
  },

  /** Puts non-numeric text in a numeric column. */
  WRONG_TYPE {
    @Override
    Object apply(Object value, GenContext ctx) {
      String[] junk = {"n/a", "NULL", "unknown", "-", "see remarks"};
      return junk[new Random(Seeds.salt(ctx.seed(), "wrongType")).nextInt(junk.length)];
    }
  },

  /** Empty string where a value was expected — distinct from a true null. */
  EMPTY_STRING {
    @Override
    Object apply(Object value, GenContext ctx) {
      return "";
    }
  },

  /** Mojibake, as produced by a UTF-8 file decoded as Latin-1. */
  ENCODING_ARTIFACT {
    @Override
    Object apply(Object value, GenContext ctx) {
      String s = String.valueOf(value);
      return s.replace("é", "Ã©").replace("ø", "Ã¸").replace("ü", "Ã¼").replace("á", "Ã¡")
          + (s.contains("Ã") ? "" : "Â");
    }
  },

  /** Embedded control characters that break naive delimited parsing. */
  CONTROL_CHARS {
    @Override
    Object apply(Object value, GenContext ctx) {
      return value + "\u0000\u001f";
    }
  };

  abstract Object apply(Object value, GenContext ctx);
}
