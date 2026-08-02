package app.viglide.core.backtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Direction;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2 (PLAN-007 Task D): no order path may bypass the Risk Manager (CLAUDE.md §11). {@link
 * VirtualExchange} is the only class that can move the paper portfolio, so this fails the build if
 * any production class other than the two RM-aware harnesses ({@link BacktestHarness}, {@link
 * FundingArbHarness}) ever gains a dependency on it.
 *
 * <p>Uses the same codesource-location import trick as {@code ArchitectureGuardTest} — this only
 * imports the production class set, so test classes (which legitimately unit-test {@link
 * VirtualExchange} directly, e.g. {@code VirtualExchangeTest}) are never part of the analysis.
 */
class OrderPathGuardTest {

  private static JavaClasses coreClasses;

  @BeforeAll
  static void importCoreClasses() throws Exception {
    Path productionClassRoot =
        Paths.get(Direction.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    coreClasses = new ClassFileImporter().importPath(productionClassRoot);
  }

  @Test
  void atLeastOneProductionClassIsImported() {
    assertThat(coreClasses).as("imported viglide-core production classes").isNotEmpty();
  }

  /**
   * {@code onSignal} must stay package-private — the language itself then forbids cross-package
   * calls; this test only needs to catch someone widening its visibility.
   */
  @Test
  void onSignal_isPackagePrivate() throws NoSuchMethodException {
    var method =
        VirtualExchange.class.getDeclaredMethod(
            "onSignal", app.viglide.core.domain.TechnicalSignal.class);
    int mods = method.getModifiers();
    assertThat(Modifier.isPublic(mods) || Modifier.isProtected(mods) || Modifier.isPrivate(mods))
        .as("onSignal must remain package-private (no explicit access modifier)")
        .isFalse();
  }

  /**
   * Production rule: only the two RM-aware harnesses may depend on {@link VirtualExchange}.
   * Deliberately adding a direct {@code virtualExchange.onSignal(...)} call to some other
   * production class (verify once locally, then revert) makes this fail.
   */
  @Test
  void onlyHarnessesDependOnVirtualExchange() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.core..")
        .and()
        .areNotAssignableTo(BacktestHarness.class)
        .and()
        .areNotAssignableTo(FundingArbHarness.class)
        .and()
        .areNotAssignableTo(VirtualExchange.class)
        .should()
        .dependOnClassesThat()
        .areAssignableTo(VirtualExchange.class)
        .as(
            "only BacktestHarness/FundingArbHarness may call VirtualExchange mutators (CLAUDE.md §11)")
        .check(coreClasses);
  }
}
