plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.prince.ai"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // AI provider configuration. Defaults point at Mistral; switch the base URL and
        // model to swap providers (OpenAI, Together, Groq, OpenRouter, local LM Studio,
        // etc.) — anything OpenAI-API-compatible works without code changes. Set values
        // in the repo-root `.env` (gitignored). -PgradlePropertyName overrides for CI.
        val apiKey = providers
            .gradleProperty("AI_API_KEY")
            .orElse(providers.provider { readEnv("AI_API_KEY") })
            .getOrElse("")
        val baseUrl = providers
            .gradleProperty("AI_BASE_URL")
            .orElse(providers.provider { readEnv("AI_BASE_URL") })
            .getOrElse("https://api.mistral.ai/v1")
        val textModel = providers
            .gradleProperty("AI_TEXT_MODEL")
            .orElse(providers.provider { readEnv("AI_TEXT_MODEL") })
            .getOrElse("mistral-small-latest")
        val imageModel = providers
            .gradleProperty("AI_IMAGE_MODEL")
            .orElse(providers.provider { readEnv("AI_IMAGE_MODEL") })
            .getOrElse("")
        buildConfigField("String", "API_KEY", "\"" + apiKey + "\"")
        buildConfigField("String", "BASE_URL", "\"" + baseUrl + "\"")
        buildConfigField("String", "TEXT_MODEL", "\"" + textModel + "\"")
        buildConfigField("String", "IMAGE_MODEL", "\"" + imageModel + "\"")
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
}

fun readEnv(key: String): String {
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
