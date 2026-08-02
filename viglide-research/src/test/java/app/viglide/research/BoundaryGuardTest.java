package app.viglide.research;

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
 * viglide-research} is public and must be able to build/run with only the public {@code
 * viglide-examples} providers on the classpath — a private strategy such as {@code fundingarb} is
 * resolved purely by name through {@code StrategyRegistry}/{@code ParameterSpaceRegistry}
 * (ServiceLoader), never by importing {@code app.viglide.strategies} directly. The {@code
 * runtimeOnly(project(":viglide-strategies"))} line in this module's own {@code build.gradle.kts}
 * is deliberately a *runtime* classpath addition (transitional, until PLAN-018 R-5 splits the
 * repos) — it puts no class from that module on this module's *compile* classpath, so this test can
 * never see it.
 */
class BoundaryGuardTest {

  private static JavaClasses researchClasses;

  @BeforeAll
  static void importResearchClasses() {
    Path productionClassRoot = Paths.get("build", "classes", "java", "main");
    researchClasses = new ClassFileImporter().importPath(productionClassRoot);
  }

  @Test
  void atLeastOneProductionClassIsImported() {
    assertThat(researchClasses).as("imported viglide-research production classes").isNotEmpty();
  }

  @Test
  void researchHasNoStrategiesDependency() {
    noClasses()
        .that()
        .resideInAPackage("app.viglide.research..")
        .should()
        .accessClassesThat()
        .resideInAPackage("app.viglide.strategies..")
        .as("viglide-research (PUBLIC) must not depend on app.viglide.strategies (PRIVATE)")
        .check(researchClasses);
  }
}
