plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // SDK oficial de Anthropic (Java, compatible con Kotlin y Android). Trae OkHttp y Jackson.
    api("com.anthropic:anthropic-java:3.3.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Latencia del orquestador (EXP-09, parte de software)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mx.alan.intercom.core.bench.LatencyBenchKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    args(
        (project.findProperty("benchModel") as String?) ?: "claude-opus-5-5",
        (project.findProperty("benchReps") as String?) ?: "5",
    )
}
