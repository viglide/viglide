package app.viglide.core.params;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a strategy's calibrated parameters from a winners store (PLAN-012 Task C, review finding
 * F3) — the same file {@code PromoteCli} writes and {@code PromoteCli.loadWinners} reads. Until
 * this class, the only two "strategy + params" wiring paths were {@code PromoteCli}'s own PSR/DSR
 * re-run and each {@link app.viglide.core.spi.StrategyProvider#create(Map)}'s CLI-args path
 * directly — no connection ran from a winners store to the live/paper decision loop, so {@code
 * PaperTradingRunner} always ran textbook defaults (`ARCHITECTURE.md` R11).
 *
 * <p>Selects by exact match on the operator-chosen key convention {@code SYMBOL_strategy_YEAR} (the
 * {@code --key=} promote-time convention every entry in the real local store already follows —
 * {@code PromoteCli} treats the key as an opaque, operator-supplied string, not a structured field,
 * so this class parses the convention rather than relying on the file format enforcing it), picking
 * the most recent year present for that {@code (symbol, strategy)} pair.
 *
 * <p><strong>Fails loudly, never silently falls back to textbook</strong> — a caller that asked for
 * calibrated parameters and can't get them must find out at startup, not discover it 90 days into a
 * paper record that measured the wrong thing.
 */
public final class WinnersParamsLoader {

  private WinnersParamsLoader() {}

  /**
   * One resolved winners-store entry: CLI-shaped args ready for {@link
   * app.viglide.core.spi.StrategyProvider#create(Map)}, the training year selected, and a
   * values-never-exposed hash of the parameter map ({@link ParamsHash}) for provenance logging.
   */
  public record Resolved(Map<String, String> args, int trainingYear, String paramsHash) {}

  /**
   * @param winnersPath path to a {@code winners.json}-shaped file
   * @param strategyName the strategy's registered name (e.g. {@code "fundingarb"}) — see {@link
   *     app.viglide.core.spi.StrategyRegistry}
   * @param symbol the trading pair (e.g. {@code "ETHUSDT"})
   * @throws IllegalStateException if the file is missing/malformed, or no entry matches — always
   *     listing what keys <em>were</em> found, per this class's fail-loud contract
   */
  @SuppressWarnings("unchecked")
  public static Resolved resolve(Path winnersPath, String strategyName, String symbol) {
    Objects.requireNonNull(winnersPath, "winnersPath");
    Objects.requireNonNull(strategyName, "strategyName");
    Objects.requireNonNull(symbol, "symbol");

    if (!Files.exists(winnersPath)) {
      throw new IllegalStateException(
          "--params=winners:"
              + winnersPath
              + " refuses to start: file does not exist. See docs/runbook.md §10.");
    }

    Map<String, Object> winners;
    try {
      Object root = JsonReader.parse(Files.readString(winnersPath));
      if (!(root instanceof Map<?, ?> m)) {
        throw new IllegalStateException(
            "--params=winners:"
                + winnersPath
                + " refuses to start: not a JSON object at the top"
                + " level.");
      }
      winners = (Map<String, Object>) m;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "--params=winners:" + winnersPath + " refuses to start: could not read the file", e);
    }

    Pattern keyPattern =
        Pattern.compile(
            "^" + Pattern.quote(symbol) + "_" + Pattern.quote(strategyName) + "_(\\d{4})$");
    int bestYear = -1;
    String bestKey = null;
    for (String key : winners.keySet()) {
      Matcher matcher = keyPattern.matcher(key);
      if (matcher.matches()) {
        int year = Integer.parseInt(matcher.group(1));
        if (year > bestYear) {
          bestYear = year;
          bestKey = key;
        }
      }
    }
    if (bestKey == null) {
      throw new IllegalStateException(
          "--params=winners:"
              + winnersPath
              + " has no entry for (strategy="
              + strategyName
              + ", symbol="
              + symbol
              + "); expected a key shaped '"
              + symbol
              + "_"
              + strategyName
              + "_<year>'. Entries found in this store: "
              + String.join(", ", winners.keySet()));
    }

    Object entryObj = winners.get(bestKey);
    if (!(entryObj instanceof Map<?, ?> entryMapRaw)) {
      throw new IllegalStateException(
          "--params=winners:"
              + winnersPath
              + " refuses to start: entry '"
              + bestKey
              + "' is not a JSON object.");
    }
    Map<String, Object> entryMap = (Map<String, Object>) entryMapRaw;
    Object paramsObj = entryMap.get("params");
    if (!(paramsObj instanceof Map<?, ?> paramsMapRaw)) {
      throw new IllegalStateException(
          "--params=winners:"
              + winnersPath
              + " refuses to start: entry '"
              + bestKey
              + "' has no 'params' object.");
    }
    Map<String, Object> params = (Map<String, Object>) paramsMapRaw;

    Map<String, String> args = CliArgs.camelMapToCliArgs(params);
    return new Resolved(args, bestYear, ParamsHash.of(args));
  }
}
