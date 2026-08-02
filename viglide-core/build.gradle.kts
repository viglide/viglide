plugins {
    id("viglide.java-conventions")
}

// JUnit Jupiter is wired by the viglide.java-conventions plugin (test suite API).
dependencies {
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit)
}
