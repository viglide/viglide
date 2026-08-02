package app.viglide.core.regime;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Precomputed reference statistics a live decision loop classifies each new bar against (PLAN-009
 * Task H): the pair's full-history median {@code |funding rate|} and its trailing-30d realized-vol
 * tercile boundaries. Computed once, offline, from historical data ({@link
 * RegimeLabeler#computeReference}) — a live loop has no business re-deriving a multi-year
 * distribution on every bar.
 *
 * <p>{@code fullHistoryMedianAbsFundingRate} is empty for an OHLCV-only pair with no funding
 * history — {@link RegimeLabeler#classifyFunding} maps that to {@link FundingRegime#UNKNOWN}.
 */
public record RegimeReference(
    Optional<BigDecimal> fullHistoryMedianAbsFundingRate,
    double volTercileP33,
    double volTercileP66) {

  public RegimeReference {
    Objects.requireNonNull(fullHistoryMedianAbsFundingRate, "fullHistoryMedianAbsFundingRate");
    if (volTercileP33 > volTercileP66) {
      throw new IllegalArgumentException("volTercileP33 must be <= volTercileP66");
    }
  }
}
