plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Spotless is applied inside the viglide.java-conventions convention plugin.
    // It must live on the buildSrc compile classpath so the precompiled script can reference it.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")
}
