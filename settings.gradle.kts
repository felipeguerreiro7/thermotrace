pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ThermoTrace"
include(":app")
// Módulo NFC do fabricante (Fudan Microelectronics). Código de terceiro:
// não editar. As correções ficam em com.thermotrace.app.nfc.
include(":nfcinstruct")
