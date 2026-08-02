package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ExchangeFilters} — PLAN-010 Task D1/D4. */
class ExchangeFiltersTest {

  private static final ExchangeFilters FILTERS =
      new ExchangeFilters(new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("0.10"));

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // ── construction ─────────────────────────────────────────────────────────────────────────────

  @Test
  void rejectsNullFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ExchangeFilters(null, bd("0.001"), bd("0.10")));
    assertThatNullPointerException()
        .isThrownBy(() -> new ExchangeFilters(bd("5"), null, bd("0.10")));
    assertThatNullPointerException()
        .isThrownBy(() -> new ExchangeFilters(bd("5"), bd("0.001"), null));
  }

  @Test
  void rejectsNegativeMinNotional() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExchangeFilters(bd("-1"), bd("0.001"), bd("0.10")));
  }

  @Test
  void zeroMinNotionalIsAllowed() {
    assertThat(new ExchangeFilters(BigDecimal.ZERO, bd("0.001"), bd("0.10")).minNotional())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void rejectsNonPositiveStepOrTickSize() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExchangeFilters(bd("5"), BigDecimal.ZERO, bd("0.10")));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExchangeFilters(bd("5"), bd("0.001"), BigDecimal.ZERO));
  }

  // ── roundQtyDown ─────────────────────────────────────────────────────────────────────────────

  @Test
  void roundQtyDown_flooresToNearestStep() {
    assertThat(FILTERS.roundQtyDown(bd("1.2347"))).isEqualByComparingTo(bd("1.234"));
  }

  @Test
  void roundQtyDown_exactMultipleIsUnchanged() {
    assertThat(FILTERS.roundQtyDown(bd("1.235"))).isEqualByComparingTo(bd("1.235"));
  }

  @Test
  void roundQtyDown_neverRoundsUp() {
    BigDecimal in = bd("0.0019");
    assertThat(FILTERS.roundQtyDown(in)).isLessThanOrEqualTo(in);
  }

  @Test
  void roundQtyDown_belowOneStepRoundsToZero() {
    assertThat(FILTERS.roundQtyDown(bd("0.0009"))).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ── roundStopLossTowardEntry / roundTakeProfitTowardEntry: the per-side matrix
  // ──────────────────

  @Test
  void buyStopLoss_roundsUpTowardEntry() {
    // Below a tick boundary: 49999.97 -> 50000.00 (up, closer to the entry above it).
    assertThat(FILTERS.roundStopLossTowardEntry(bd("49999.97"), Direction.BUY))
        .isEqualByComparingTo(bd("50000.00"));
  }

  @Test
  void buyTakeProfit_roundsDownTowardEntry() {
    // Above a tick boundary: 50999.97 -> 50999.90 (down, closer to the entry below it).
    assertThat(FILTERS.roundTakeProfitTowardEntry(bd("50999.97"), Direction.BUY))
        .isEqualByComparingTo(bd("50999.90"));
  }

  @Test
  void sellStopLoss_roundsDownTowardEntry() {
    // Short's stop sits above entry: 50999.97 -> 50999.90 (down, closer to entry below it).
    assertThat(FILTERS.roundStopLossTowardEntry(bd("50999.97"), Direction.SELL))
        .isEqualByComparingTo(bd("50999.90"));
  }

  @Test
  void sellTakeProfit_roundsUpTowardEntry() {
    // Short's target sits below entry: 49999.97 -> 50000.00 (up, closer to entry above it).
    assertThat(FILTERS.roundTakeProfitTowardEntry(bd("49999.97"), Direction.SELL))
        .isEqualByComparingTo(bd("50000.00"));
  }

  @Test
  void priceExactlyOnTick_isUnchangedRegardlessOfSideOrKind() {
    BigDecimal onTick = bd("50000.00");
    assertThat(FILTERS.roundStopLossTowardEntry(onTick, Direction.BUY))
        .isEqualByComparingTo(onTick);
    assertThat(FILTERS.roundStopLossTowardEntry(onTick, Direction.SELL))
        .isEqualByComparingTo(onTick);
    assertThat(FILTERS.roundTakeProfitTowardEntry(onTick, Direction.BUY))
        .isEqualByComparingTo(onTick);
    assertThat(FILTERS.roundTakeProfitTowardEntry(onTick, Direction.SELL))
        .isEqualByComparingTo(onTick);
  }

  /**
   * D4's property test: for a spread of unrounded prices, every rounded stop is at least as
   * protective as the unrounded one (never a worse trigger point), and every rounded take-profit
   * books no more profit than the unrounded one — the D10-2 "refuse, don't bump" invariant, cell by
   * cell of the matrix in {@link ExchangeFilters}' class Javadoc.
   */
  @Test
  void roundingMatrix_everyCellNeverLooksBetterThanUnrounded() {
    for (String raw : new String[] {"49999.94", "49999.97", "50000.03", "50000.09", "49123.456"}) {
      BigDecimal price = bd(raw);

      BigDecimal buySl = FILTERS.roundStopLossTowardEntry(price, Direction.BUY);
      // A long's stop below entry: rounded must be >= raw (closer to entry, triggers no later).
      assertThat(buySl).isGreaterThanOrEqualTo(price);

      BigDecimal buyTp = FILTERS.roundTakeProfitTowardEntry(price, Direction.BUY);
      // A long's target above entry: rounded must be <= raw (books no more than raw would have).
      assertThat(buyTp).isLessThanOrEqualTo(price);

      BigDecimal sellSl = FILTERS.roundStopLossTowardEntry(price, Direction.SELL);
      // A short's stop above entry: rounded must be <= raw (closer to entry, triggers no later).
      assertThat(sellSl).isLessThanOrEqualTo(price);

      BigDecimal sellTp = FILTERS.roundTakeProfitTowardEntry(price, Direction.SELL);
      // A short's target below entry: rounded must be >= raw (books no more than raw would have).
      assertThat(sellTp).isGreaterThanOrEqualTo(price);
    }
  }
}
