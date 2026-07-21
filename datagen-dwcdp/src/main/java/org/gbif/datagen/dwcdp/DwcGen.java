package org.gbif.datagen.dwcdp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

import org.gbif.datagen.engine.Seeds;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.GenContext;
import org.gbif.datagen.gen.Generator;
import org.gbif.datagen.schema.Field;

/** Generators carrying DwC-DP taste. Core stays standard-agnostic; opinions live here. */
public final class DwcGen {

  private DwcGen() {
  }

  // ---------------------------------------------------------------- dates

  /**
   * DwC dates are a zoo — {@code 1906-06}, {@code 2007-11-13/15},
   * {@code 1963-03-08T14:07-06:00}, {@code spring 1910} — and that messiness is the point of the
   * test data, so it gets its own builder rather than a bare string generator.
   */
  public static DateGen dates() {
    return new DateGen(1950, 2025, List.of(DateFormat.ISO_DAY));
  }

  /** Shapes an eventDate can take. */
  public enum DateFormat {
    /** {@code 1809-02-12} */
    ISO_DAY,
    /** {@code 1906-06} */
    PARTIAL_MONTH,
    /** {@code 1971} */
    PARTIAL_YEAR,
    /** {@code 2009-02-20T08:40Z} */
    ISO_DATETIME,
    /** {@code 2007-11-13/15} */
    INTERVAL,
    /** {@code spring 1910} — legal in verbatimEventDate, junk in eventDate */
    VERBATIM
  }

  /** Fluent date generator. */
  public static final class DateGen implements Generator<String> {
    private final int startYear;
    private final int endYear;
    private final List<DateFormat> formats;

    DateGen(int startYear, int endYear, List<DateFormat> formats) {
      this.startYear = startYear;
      this.endYear = endYear;
      this.formats = formats;
    }

    public DateGen between(int startYear, int endYear) {
      return new DateGen(startYear, endYear, formats);
    }

    public DateGen formats(DateFormat... formats) {
      return new DateGen(startYear, endYear, List.of(formats));
    }

    @Override
    public String generate(GenContext ctx) {
      Random r = ctx.random();
      int year = startYear + r.nextInt(endYear - startYear + 1);
      LocalDate date = LocalDate.ofYearDay(year, 1 + r.nextInt(LocalDate.of(year, 1, 1).lengthOfYear()));
      DateFormat format = formats.get(new Random(Seeds.salt(ctx.seed(), "dateFormat")).nextInt(formats.size()));

      return switch (format) {
        case ISO_DAY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        case PARTIAL_MONTH -> String.format("%04d-%02d", year, date.getMonthValue());
        case PARTIAL_YEAR -> String.valueOf(year);
        case ISO_DATETIME -> String.format("%sT%02d:%02dZ",
            date.format(DateTimeFormatter.ISO_LOCAL_DATE), r.nextInt(24), r.nextInt(60));
        case INTERVAL -> {
          LocalDate end = date.plusDays(1 + r.nextInt(14));
          yield date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "/"
              + end.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        case VERBATIM -> {
          String[] seasons = {"spring", "summer", "autumn", "winter", "early", "late"};
          yield seasons[r.nextInt(seasons.length)] + " " + year;
        }
      };
    }

    /** Derives a {@code year} column consistent with an already-generated date column. */
    public static Generator<Number> yearOf(String dateField) {
      return Gen.derive(row -> {
        String v = row.getString(dateField);
        if (v == null || v.length() < 4) {
          return null;
        }
        try {
          return Integer.parseInt(v.substring(0, 4));
        } catch (NumberFormatException e) {
          return null;
        }
      });
    }

    /** Derives a {@code month} column consistent with an already-generated date column. */
    public static Generator<Number> monthOf(String dateField) {
      return Gen.derive(row -> {
        String v = row.getString(dateField);
        if (v == null || v.length() < 7 || v.charAt(4) != '-') {
          return null;
        }
        try {
          return Integer.parseInt(v.substring(5, 7));
        } catch (NumberFormatException e) {
          return null;
        }
      });
    }

    /** Derives a {@code day} column consistent with an already-generated date column. */
    public static Generator<Number> dayOf(String dateField) {
      return Gen.derive(row -> {
        String v = row.getString(dateField);
        if (v == null || v.length() < 10 || v.charAt(7) != '-') {
          return null;
        }
        try {
          return Integer.parseInt(v.substring(8, 10));
        } catch (NumberFormatException e) {
          return null;
        }
      });
    }
  }

// ---------------------------------------------------------------- geography

  /** A bounding box with a friendly name. */
  public record Area(String name, double minLat, double maxLat, double minLon, double maxLon) {

    public static final Area DENMARK = new Area("Denmark", 54.5, 57.8, 8.0, 15.2);
    public static final Area KENYA = new Area("Kenya", -4.7, 5.0, 33.9, 41.9);
    public static final Area PERU = new Area("Peru", -18.4, -0.03, -81.3, -68.7);
    public static final Area NORWAY = new Area("Norway", 57.9, 71.2, 4.6, 31.1);
    public static final Area COSTA_RICA = new Area("Costa Rica", 8.0, 11.2, -85.9, -82.5);
    public static final Area GLOBAL = new Area("Global", -90, 90, -180, 180);
  }

  /**
   * Latitude/longitude within an area.
   *
   * <p>Axis is mandatory — call {@code .latitude()} or {@code .longitude()} before use. There is
   * deliberately no auto-detection from field name or term: sniffing the axis from context is
   * exactly the failure mode that produces this bug class — if detection ever falls through to
   * the wrong branch, a value legitimately drawn from the *longitude* range ({@code [-180, 180]})
   * gets assigned to a *latitude* field, and the result is a perfectly ordinary-looking number
   * that is nonetheless outside that field's real constraint. Two separate, explicitly-tagged
   * generator instances can't have that failure mode — there's nothing to detect, so there's
   * nothing to detect wrong.
   */
  public static AreaGen area(Area area) {
    return new AreaGen(area, 0, null);
  }

  /** Fluent coordinate generator. Axis must be set via {@link #latitude()}/{@link #longitude()}. */
  public static final class AreaGen implements Generator<Number> {
    private final Area area;
    private final double noise;
    private final Boolean isLatitude; // null until explicitly set — see generate()

    AreaGen(Area area, double noise, Boolean isLatitude) {
      this.area = area;
      this.noise = noise;
      this.isLatitude = isLatitude;
    }

    /** Gaussian jitter in degrees, applied after the uniform draw. */
    public AreaGen noise(double degrees) {
      return new AreaGen(area, degrees, isLatitude);
    }

    public AreaGen longitude() {
      return new AreaGen(area, noise, false);
    }

    public AreaGen latitude() {
      return new AreaGen(area, noise, true);
    }

    @Override
    public Number generate(GenContext ctx) {
      if (isLatitude == null) {
        throw new IllegalStateException("DwcGen.area(...) requires an explicit axis — call"
                                        + " .latitude() or .longitude() before binding it to a field. This generator was"
                                        + " about to produce a value for " + ctx.resource() + "." + ctx.field()
                                        + " with no axis specified.");
      }

      Random r = ctx.random();
      double min = isLatitude ? area.minLat() : area.minLon();
      double max = isLatitude ? area.maxLat() : area.maxLon();
      double v = min + r.nextDouble() * (max - min);
      if (noise > 0) {
        v += r.nextGaussian() * noise;
      }
      double clampMin = isLatitude ? -90 : -180;
      double clampMax = isLatitude ? 90 : 180;
      v = Math.max(clampMin, Math.min(clampMax, v));
      return Math.round(v * 1_000_000d) / 1_000_000d;
    }
  }

  // ---------------------------------------------------------------- names

  private static final List<String> GENERA = List.of(
      "Apus", "Troglodytes", "Turdus", "Erithacus", "Vulpes", "Lynx", "Bombus", "Formica",
      "Quercus", "Fagus", "Salmo", "Rana", "Ursus", "Alces", "Corvus", "Falco", "Cervus",
      "Lutra", "Bufo", "Anguilla", "Parus", "Sciurus", "Pinus", "Betula");

  private static final List<String> EPITHETS = List.of(
      "apus", "troglodytes", "merula", "rubecula", "vulpes", "lynx", "terrestris", "rufa",
      "robur", "sylvatica", "trutta", "temporaria", "arctos", "alces", "corax", "peregrinus",
      "elaphus", "lutra", "bufo", "anguilla", "major", "vulgaris", "sylvestris", "pendula");

  /** Binomial scientific names. Not a real taxonomy — plausible shape, deterministic. */
  public static Generator<String> scientificName() {
    return ctx -> {
      Random r = ctx.random();
      return GENERA.get(r.nextInt(GENERA.size())) + " " + EPITHETS.get(r.nextInt(EPITHETS.size()));
    };
  }

  private static final List<String> GIVEN = List.of(
      "J.", "A.", "M.", "P.", "K.", "L.", "S.", "T.", "N.", "R.");

  private static final List<String> FAMILY = List.of(
      "Doe", "Smith", "Nielsen", "Jensen", "Okonkwo", "Garcia", "Chen", "Müller", "Rossi",
      "Kowalski", "Andersen", "Pereira", "Sørensen", "Novak");

  /** Person names, optionally pipe-concatenated as DwC recommends for lists. */
  public static Generator<String> personName() {
    return ctx -> {
      Random r = ctx.random();
      return GIVEN.get(r.nextInt(GIVEN.size())) + " " + FAMILY.get(r.nextInt(FAMILY.size()));
    };
  }

  /** A {@code |}-separated agent list, 1..max entries. */
  public static Generator<String> personNames(int max) {
    Generator<String> one = personName();
    return ctx -> {
      int count = 1 + new Random(Seeds.salt(ctx.seed(), "nameCount")).nextInt(max);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < count; i++) {
        if (i > 0) {
          sb.append(" | ");
        }
        Random r = new Random(Seeds.salt(ctx.seed(), "name", i));
        sb.append(GIVEN.get(r.nextInt(GIVEN.size()))).append(' ')
            .append(FAMILY.get(r.nextInt(FAMILY.size())));
      }
      return sb.toString();
    };
  }

  /** ORCID-shaped agent identifiers. */
  public static Generator<String> orcid() {
    return ctx -> {
      Random r = ctx.random();
      return String.format("https://orcid.org/%04d-%04d-%04d-%04d",
          r.nextInt(10000), r.nextInt(10000), r.nextInt(10000), r.nextInt(10000));
    };
  }
}
