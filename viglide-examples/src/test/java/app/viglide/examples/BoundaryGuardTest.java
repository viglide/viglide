package app.viglide.examples;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the public/private boundary at the bytecode level (CLAUDE.md §2, PLAN-018 R-4.1). {@code
 * viglide-examples} is the textbook benchmark set shipped in the public repo — it must never reach
 * into the private alpha, and its own dependency footprint should stay exactly {@code
 * viglide-core}, nothing more (ADR-0025 §4).
 */
class BoundaryGuardTest {

  private static JavaClasses examplesClasses;

  @BeforeAll
  static void importExamplesClasses() {
    Path productionClassRoot = Paths.get("build", "classes", "java", "main");
    examplesClasses = new ClassFileImporter().importPath(productionClassRoot);
  }

  @Test
  void atLeastOneProductionClassIsImported() {
    assertThat(examplesClasses).as("imported viglide-examples production classes").isNotEmpty();
  }

  @Test
  void examplesHasNoStrategiesDependency() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.examples..")
        .should()
        .accessClassesThat()
        .resideInAPackage("app.viglide.strategies..")
        .as("viglide-examples (PUBLIC) must not depend on app.viglide.strategies (PRIVATE)")
        .check(examplesClasses);
  }

  /**
   * ADR-0025 §4: viglide-examples depends on viglide-core only, nothing else internal. Restated as
   * a forbid-list (every other current internal module) rather than an allow-list, matching this
   * codebase's established ArchUnit idiom (see {@code ArchitectureGuardTest}).
   */
  @Test
  void examplesDependsOnCoreOnly() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.examples..")
        .should()
        .accessClassesThat()
        .resideInAnyPackage(
            "app.viglide.research..",
            "app.viglide.runtime..",
            "app.viglide.strategies..",
            "app.viglide.app..")
        .as("viglide-examples must depend on app.viglide.core.. only, among internal packages")
        .check(examplesClasses);
  }
}
