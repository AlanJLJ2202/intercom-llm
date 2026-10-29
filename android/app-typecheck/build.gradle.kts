// Verificación de tipos de las fuentes de :app sin Android SDK.
// Compila ../app/src/main/java contra android-all (framework de Android
// publicado en Maven Central por Robolectric). No produce un APK.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../app/src/main/java")
}

dependencies {
    compileOnly("org.robolectric:android-all:15-robolectric-13954326")
    implementation(project(":core"))
}
