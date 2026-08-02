plugins {
    id("viglide.java-conventions")
}

dependencies {
    implementation(project(":viglide-core"))
    implementation(project(":viglide-examples"))

    // JUnit Jupiter wired by convention plugin; only add extras here.
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit)
}

// CLI entrypoints. Invoke as:
//   ./gradlew :viglide-research:backtest  --args="--strategy=emarsi --dataset=… --symbol=… --interval=ONE_HOUR"
//   ./gradlew :viglide-research:calibrate --args="--strategy=emarsi --dataset=… --search=grid …"
tasks.register<JavaExec>("backtest") {
    group = "viglide"
    description = "Runs a single backtest. See BacktestCli for the full arg list."
    mainClass.set("app.viglide.research.cli.BacktestCli")
    classpath = sourceSets["main"].runtimeClasspath
    // Resolve relative dataset/out paths against the repo root, not the submodule dir.
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("calibrate") {
    group = "viglide"
    description = "Runs walk-forward parameter calibration. See CalibrateCli for the full arg list."
    mainClass.set("app.viglide.research.cli.CalibrateCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    // Calibration is CPU-bound; default to surfacing OOMs early.
    jvmArgs("-XX:+ExitOnOutOfMemoryError")
}

tasks.register<JavaExec>("promote") {
    group = "viglide"
    description = "Promotes a top-ranked calibration result into the local winners.json (gitignored)."
    mainClass.set("app.viglide.research.cli.PromoteCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("portfolio") {
    group = "viglide"
    description = "Runs a multi-symbol, RM-gated portfolio backtest. See PortfolioCli for the full arg list."
    mainClass.set("app.viglide.research.cli.PortfolioCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("regimeLabel") {
    group = "viglide"
    description = "Labels a pair's monthly funding/volatility regimes. See RegimeLabelCli for the full arg list."
    mainClass.set("app.viglide.research.cli.RegimeLabelCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("replay") {
    group = "viglide"
    description = "Replays a paper/testnet run's recorded decision inputs and diffs them against " +
        "the live ledger (PLAN-012 Task H, the K2 instrument). See ReplayCli for the full arg list."
    mainClass.set("app.viglide.research.cli.ReplayCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("nullModel") {
    group = "viglide"
    description = "Null-model (circular permutation) baseline: how often does this pipeline " +
        "produce a result this good from data with no real edge in it? (PLAN-013 Task I). " +
        "See NullModelCli for the full arg list."
    mainClass.set("app.viglide.research.cli.NullModelCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
