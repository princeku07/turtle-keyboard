plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.prince.notion"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // Notion OAuth credentials. Register a "public integration" at
        // https://www.notion.so/profile/integrations and paste the values into the
        // repo-root `.env` (gitignored). The client SECRET ships in BuildConfig — fine
        // for personal use, swap to a token-exchange Worker before any public release.
        val clientId = providers
            .gradleProperty("NOTION_OAUTH_CLIENT_ID")
            .orElse(providers.provider { readEnv("NOTION_OAUTH_CLIENT_ID") })
            .getOrElse("")
        val clientSecret = providers
            .gradleProperty("NOTION_OAUTH_CLIENT_SECRET")
            .orElse(providers.provider { readEnv("NOTION_OAUTH_CLIENT_SECRET") })
            .getOrElse("")
        val redirectUri = providers
            .gradleProperty("NOTION_OAUTH_REDIRECT_URI")
            .orElse(providers.provider { readEnv("NOTION_OAUTH_REDIRECT_URI") })
            .getOrElse("turtlekeyboard://notion-redirect")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"" + clientId + "\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET", "\"" + clientSecret + "\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"" + redirectUri + "\"")
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
