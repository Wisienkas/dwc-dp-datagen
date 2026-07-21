package org.gbif.datagen.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gbif.datagen.gen.Row;

/**
 * Writes RFC 4180 CSV in the resource's field order.
 *
 * <p>No dialect is emitted for these files, so they must follow the defaults exactly: comma
 * delimiter, double-quote quoting, CRLF-agnostic. Values containing a delimiter, quote, or line
 * break are quoted; embedded quotes are doubled.
 */
public final class CsvWriter {

  private CsvWriter() {
  }

  public static void write(Path path, List<String> columns, List<Row> rows) {
    try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writeRow(w, columns);
      for (Row row : rows) {
        List<String> values = columns.stream().map(c -> cell(row.get(c))).toList();
        writeRow(w, values);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing CSV to " + path, e);
    }
  }

  private static String cell(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
      return String.valueOf((long) (double) d);
    }
    return String.valueOf(value);
  }

  private static void writeRow(Writer w, List<String> values) throws IOException {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        w.write(',');
      }
      w.write(quoteIfNeeded(values.get(i)));
    }
    w.write("\r\n");
  }

  private static String quoteIfNeeded(String value) {
    if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }
}
