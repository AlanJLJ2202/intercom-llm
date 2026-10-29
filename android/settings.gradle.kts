pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "intercom-llm"

// Núcleo JVM puro: orquestador, políticas, herramientas, VAD, proveedores de IA.
// Se compila y prueba sin Android SDK.
include(":core")

// App Android: requiere Android SDK (Android Studio o ANDROID_HOME).
val hasAndroidSdk = file("local.properties").exists() || System.getenv("ANDROID_HOME") != null
if (hasAndroidSdk && !providers.gradleProperty("skipApp").isPresent) {
    include(":app")
}

// Verificación de tipos de las fuentes de la app contra android-all (Robolectric),
// útil en entornos sin Android SDK (CI, contenedores). Uso: gradle -Ptypecheck :app-typecheck:compileKotlin
if (providers.gradleProperty("typecheck").isPresent) {
    include(":app-typecheck")
}
