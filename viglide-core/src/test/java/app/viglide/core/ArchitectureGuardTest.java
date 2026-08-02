package app.viglide.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Direction;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the public/private boundary at the bytecode level (CLAUDE.md §2, §11).
 *
 * <p>Uses the code-source location of a known production class ({@link Direction}) to locate the
 * production class directory, bypassing {@code importPackages()} which returns zero classes on Java
 * 25 + Gradle 9 JVM Test Suite due to a classpath-scanning heuristic mismatch.
 */
class ArchitectureGuardTest {

  private static JavaClasses coreClasses;

  @BeforeAll
  static void importCoreClasses() throws Exception {
    Path productionClassRoot =
        Paths.get(Direction.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    coreClasses = new ClassFileImporter().importPath(productionClassRoot);
  }

  @Test
  void atLeastOneProductionClassIsImported() {
    // If this fails, the locator found the wrong directory and the guard rules below
    // would silently pass on an empty class set.
    assertThat(coreClasses).as("imported viglide-core production classes").isNotEmpty();
  }

  /** Fails immediately if anyone adds a Spring import to viglide-core (CLAUDE.md §3). */
  @Test
  void coreHasNoSpringDependencies() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.core..")
        .should()
        .accessClassesThat()
        .resideInAPackage("org.springframework..")
        .as("viglide-core must not depend on Spring Framework")
        .check(coreClasses);
  }

  /** Fails immediately if I/O (HTTP client) is introduced into the domain layer. */
  @Test
  void coreHasNoJavaNetHttp() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.core..")
        .should()
        .accessClassesThat()
        .resideInAPackage("java.net.http..")
        .as("viglide-core must not use java.net.http (no I/O in the domain layer)")
        .check(coreClasses);
  }

  /** Fails immediately if an exchange SDK leaks into the public domain module. */
  @Test
  void coreHasNoExchangeSdkDependencies() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.core..")
        .should()
        .accessClassesThat()
        .resideInAPackage("com.binance..")
        .orShould()
        .accessClassesThat()
        .resideInAPackage("com.revolut..")
        .as("viglide-core must not depend on any exchange SDK")
        .check(coreClasses);
  }

  /**
   * PLAN-018 R-4.1: restates CLAUDE.md §2's one rule ("PUBLIC never depends on PRIVATE") as a test
   * rather than a convention. {@code viglide-core} has zero internal dependencies at all, so this
   * is largely a tripwire against a future accidental import; the modules that actually touch
   * {@code app.viglide.strategies} at build time (viglide-research, viglide-examples,
   * viglide-runtime) have their own copy of this rule.
   */
  @Test
  void coreHasNoStrategiesDependency() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.core..")
        .should()
        .accessClassesThat()
        .resideInAPackage("app.viglide.strategies..")
        .as("viglide-core (PUBLIC) must not depend on app.viglide.strategies (PRIVATE)")
        .check(coreClasses);
  }
}
