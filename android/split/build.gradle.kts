plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.prince.split"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // OAuth Web client ID — read from the repo-root `.env` file (gitignored) so the
        // same secret seeds both Android + iOS builds. Schema lives in `.env.example`.
        // -Dgradle property override still wins (handy for CI).
        val oauthClientId = providers
            .gradleProperty("SPLIT_OAUTH_WEB_CLIENT_ID")
            .orElse(providers.provider { readEnv("SPLIT_OAUTH_WEB_CLIENT_ID") })
            .getOrElse("")
        buildConfigField("String", "OAUTH_WEB_CLIENT_ID", "\"" + oauthClientId + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.appcompat)
    implementation(libs.play.services.auth)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

/** Reads `KEY=VALUE` from the repo-root `.env`. Returns empty string if missing. */
fun readEnv(key: String): String {
    // android/split/build.gradle.kts → repo root is two levels up.
    val envFile = rootProject.layout.projectDirectory.file("../.env").asFile
    if (!envFile.exists()) return ""
    return envFile.readLines().asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()
}
