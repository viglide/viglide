package app.viglide.examples.emarsi;

import app.viglide.core.domain.Candle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Loads OHLCV candle fixtures from CSV files on the test classpath. */
public final class CsvFixtureLoader {

  private CsvFixtureLoader() {}

  /**
   * Loads candles from {@code fixtures/<filename>} on the classpath. Expected CSV header: {@code
   * timestamp,open,high,low,close,volume}.
   */
  public static List<Candle> load(String filename) {
    String path = "fixtures/" + filename;
    InputStream is = CsvFixtureLoader.class.getClassLoader().getResourceAsStream(path);
    if (is == null) throw new IllegalArgumentException("Fixture not found on classpath: " + path);

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
      List<Candle> candles = new ArrayList<>();
      String line;
      boolean header = true;
      while ((line = reader.readLine()) != null) {
        if (header) {
          header = false;
          continue;
        }
        if (line.isBlank()) continue;
        String[] parts = line.split(",");
        candles.add(
            new Candle(
                Instant.parse(parts[0].trim()),
                new BigDecimal(parts[1].trim()),
                new BigDecimal(parts[2].trim()),
                new BigDecimal(parts[3].trim()),
                new BigDecimal(parts[4].trim()),
                new BigDecimal(parts[5].trim())));
      }
      return candles;
    } catch (IOException e) {
      throw new UncheckedIOException("I/O error reading fixture: " + path, e);
    } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("malformed CSV in fixture: " + path, e);
    }
  }
}
