plugins {
    `java-library`
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Gradle 9 JVM Test Suite API — automatically wires JUnit Jupiter engine AND the
// platform launcher, so modules do not need to declare either manually.
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.11.4") // must match gradle/libs.versions.toml junit version
        }
    }
}

// Google Java Format — enforces Google Java Style across all modules.
// Formatting is a pre-commit concern (run `./gradlew spotlessApply` yourself), not a compile
// concern: `check` depends on `spotlessCheck` (wired by the Spotless plugin by default), so CI
// verifies formatting instead of rewriting source files during a build (PLAN-018 R-1.2).
spotless {
    java {
        googleJavaFormat("1.27.0")
        target("src/**/*.java")
    }
}
