plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.thermotrace.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thermotrace.app"
        // 26 por causa de java.time. Abaixo disso exigiria desugaring.
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "0.8.6"
        testInstrumentationRunner = "com.thermotrace.app.EscopoInstrumentation"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    sourceSets {
        getByName("test").kotlin.directories.add("src/testShared/java")
        getByName("androidTest").kotlin.directories.add("src/testShared/java")
    }
    lint { abortOnError = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("tt.gerarContrato", providers.gradleProperty("tt.gerarContrato").getOrElse("false"))
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":nfcinstruct"))

    // Localizacao da coleta (D07). Primeira dependencia Google do app: o
    // caminho recomendado para um fix novo e o FusedLocationProviderClient, e
    // a alternativa sem Play Services so existe da API 30 em diante, enquanto
    // o minSdk aqui e 26.
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // Persistência local. O app precisa funcionar sem sinal: doca, câmara fria
    // e galpão não têm rede. O banco local é a fonte da verdade até o outbox
    // conseguir sincronizar.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Leitura de QR/código de barras do documento fiscal. Sem Play Services:
    // o app precisa rodar em aparelho de galpão, que às vezes não tem GMS.
    implementation(libs.androidx.appcompat)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.json.jvm) // JVM: contratos JSON; Android usa a implementação da plataforma.
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
