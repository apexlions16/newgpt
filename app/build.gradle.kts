plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Ortam değişkeni > gradle property > varsayılan */
fun cfg(name: String, default: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: default

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.gflix.app"
    compileSdk = 35

    defaultConfig {
        // Ayrı paket: eski G-Flix (com.gflix.app) ile aynı telefonda yan yana kurulur
        applicationId = "com.gflix.archive.next"
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("GFLIX_VERSION_CODE", "1").toInt()
        versionName = cfg("GFLIX_VERSION_NAME", "1.0.0")

        // İçeriklerin tutulduğu GitHub deposu
        buildConfigField("String", "GH_OWNER", cfg("GFLIX_OWNER", "thedrowned925").quoted())
        buildConfigField("String", "GH_REPO", cfg("GFLIX_REPO", "g-flix").quoted())
        // Boş bırakılırsa deponun varsayılan dalı kullanılır
        buildConfigField("String", "GH_BRANCH", cfg("GFLIX_BRANCH", "").quoted())
        // Private depo için salt-okunur erişim anahtarı (GitHub Secrets'tan gelir)
        buildConfigField("String", "GH_TOKEN", cfg("GFLIX_TOKEN", "").quoted())
        // archive.org arşiv öneki (ör. gflix-ahmet). Boşsa uygulama ilk açılışta sorar; anahtar değil, gizli bilgi yok.
        buildConfigField("String", "IA_PREFIX", cfg("GFLIX_IA_PREFIX", "").quoted())
    }

    // Release keystore is never kept in source control. Provide all GFLIX_KEYSTORE* values in CI.
    val signingFile = cfg("GFLIX_KEYSTORE").takeIf { it.isNotBlank() }?.let { file(it) }
    signingConfigs {
        create("gflix") {
            signingFile?.let { storeFile = it }
            storePassword = cfg("GFLIX_KEYSTORE_PASSWORD")
            keyAlias = cfg("GFLIX_KEY_ALIAS")
            keyPassword = cfg("GFLIX_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile?.exists() == true) signingConfig = signingConfigs.getByName("gflix")
        }
        debug {
            // Android Gradle Plugin's default debug key (never a production release key).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
