plugins {
    id("viglide.java-conventions")
}

dependencies {
    api(project(":viglide-core"))

    // JUnit Jupiter wired by convention plugin; only add extras here.
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit)
}
