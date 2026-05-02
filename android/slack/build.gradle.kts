plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.prince.slack"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // Slack OAuth credentials. Create an app at https://api.slack.com/apps,
        // configure OAuth Redirect URLs to match NOTION_*-style values below, and add
        // user-token scopes: chat:write, channels:read, groups:read, users:read.
        // Like Notion, the client SECRET ships in BuildConfig — fine for personal use,
        // swap to a token-exchange Worker before any wider release.
        val clientId = providers
            .gradleProperty("SLACK_OAUTH_CLIENT_ID")
            .orElse(providers.provider { readEnv("SLACK_OAUTH_CLIENT_ID") })
            .getOrElse("")
        val clientSecret = providers
            .gradleProperty("SLACK_OAUTH_CLIENT_SECRET")
            .orElse(providers.provider { readEnv("SLACK_OAUTH_CLIENT_SECRET") })
            .getOrElse("")
        val redirectUri = providers
            .gradleProperty("SLACK_OAUTH_REDIRECT_URI")
            .orElse(providers.provider { readEnv("SLACK_OAUTH_REDIRECT_URI") })
            .getOrElse("https://www.turtlekeyboard.com/oauth/slack")
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
    implementation(project(":split"))
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
