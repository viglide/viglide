package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link Factor}. */
class FactorTest {

  @Test
  void happyPath_constructsWithValidValues() {
    Factor f = new Factor("EMA_CROSSOVER", "fast crossed above slow", 0.9);
    assertThat(f.code()).isEqualTo("EMA_CROSSOVER");
    assertThat(f.detail()).isEqualTo("fast crossed above slow");
    assertThat(f.contribution()).isEqualTo(0.9);
  }

  @Test
  void acceptsBoundaryContributions() {
    assertThatCode(() -> new Factor("A", "b", 0.0)).doesNotThrowAnyException();
    assertThatCode(() -> new Factor("A", "b", 1.0)).doesNotThrowAnyException();
  }

  @Test
  void rejectsNullCode() {
    assertThatNullPointerException().isThrownBy(() -> new Factor(null, "detail", 0.5));
  }

  @Test
  void rejectsNullDetail() {
    assertThatNullPointerException().isThrownBy(() -> new Factor("CODE", null, 0.5));
  }

  @Test
  void rejectsBlankCode() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Factor("", "detail", 0.5));
  }

  @Test
  void rejectsBlankDetail() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Factor("CODE", "  ", 0.5));
  }

  @Test
  void rejectsContributionBelowZero() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Factor("A", "b", -0.1));
  }

  @Test
  void rejectsContributionAboveOne() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Factor("A", "b", 1.01));
  }
}
