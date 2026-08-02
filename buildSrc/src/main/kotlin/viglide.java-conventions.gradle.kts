plugins {
    `java-library`
    id("com.diffplug.spotless")
    id("maven-publish")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        // Ignore errors for missing references (e.g. classes in other repos) and missing @param tags
        addStringOption("Xdoclint:none", "-quiet")
    }
    isFailOnError = false
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("Viglide open-source core")
                url.set("https://github.com/viglide/viglide")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("viglide")
                        name.set("Viglide Contributors")
                        email.set("info@viglide.app")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/viglide/viglide.git")
                    developerConnection.set("scm:git:ssh://github.com/viglide/viglide.git")
                    url.set("https://github.com/viglide/viglide")
                }
            }
        }
    }
    // We expect repositories to be configured via ENV variables or init scripts in CI
    //
    // Sonatype decommissioned the legacy OSSRH Nexus2 host (s01.oss.sonatype.org) in favor of the
    // Central Portal (central.sonatype.com); a PUT against the old host now returns 405 Not Allowed
    // instead of a redirect. Snapshots have a direct Maven-compatible endpoint on the new host, but
    // releases do not — Central Portal only accepts releases via its bundle-upload Publisher API, so
    // `releasesRepoUrl` below is a placeholder that will need a different publishing mechanism
    // (e.g. a Central Portal-aware Gradle plugin) before it can be used for a real release.
    repositories {
        maven {
            name = "Sonatype"
            val releasesRepoUrl = uri("https://central.sonatype.com/api/v1/publisher/deployments/")
            val snapshotsRepoUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = project.findProperty("sonatypeUsername") as String? ?: System.getenv("SONATYPE_USERNAME")
                password = project.findProperty("sonatypePassword") as String? ?: System.getenv("SONATYPE_PASSWORD")
            }
        }
    }
}
