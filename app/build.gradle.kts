import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// BASE_URL / API_TOKEN come from local.properties (git-ignored) or -P flags. Never commit secrets.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, default: String) =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: default

android {
    namespace = "il.ac.tau.lecturesummarizer"
    compileSdk = 35
    defaultConfig {
        applicationId = "il.ac.tau.lecturesummarizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"${prop("lecture.baseUrl", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "DEFAULT_API_TOKEN", "\"${prop("lecture.apiToken", "")}\"")
        buildConfigField("boolean", "DEFAULT_MOCK", prop("lecture.mock", "true"))
    }
    buildTypes {
        // Pro unlock for debug/beta: DEBUG_PRO=all debug installs; PRO_DEVICE_IDS=comma list of X-Device-Id (owner's phone).
        debug {
            buildConfigField("boolean", "DEBUG_PRO", prop("lecture.debugPro", "false"))
            buildConfigField("String", "PRO_DEVICE_IDS", "\"${prop("lecture.proDeviceIds", "254c58f2-ea7f-44ad-919e-670f79ae5aa8")}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_PRO", "false")
            buildConfigField("String", "PRO_DEVICE_IDS", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.maxHeapSize="2g"; if(!project.hasProperty("screenshots") || it.name.contains("Release"))it.exclude("**/PolishScreenshotsTest*", "**/CalendarV2ScreenshotsTest*"); it.systemProperty("roborazzi.test.record",project.findProperty("screenshots")?.toString() ?: "false") } } }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
