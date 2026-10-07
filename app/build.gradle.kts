import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.electric.handytuner"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.electric.handytuner"
        minSdk = 33
        targetSdk = 33
        versionCode = 12
        versionName = "0.9.3-beta"
    }
    // Release key lives outside the repo; its path and password are in local.properties (git-ignored).
    val local = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
    signingConfigs {
        local.getProperty("releaseStoreFile")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = local.getProperty("releaseStorePassword")
                keyAlias = local.getProperty("releaseKeyAlias")
                keyPassword = local.getProperty("releaseStorePassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 drops unused code (mostly material-icons-extended): 53 MB -> a few MB. Framework reflection
            // (ServiceManager) and manifest components are safe; add keep rules to proguard-rules.pro if needed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
    // Compose is for the app's own screens only; the always-on :overlay process uses plain Views.
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(project(":pulse"))   // PULSE engine (merge-plan.md)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // Android's org.json is a stub in JVM tests; not shipped
}

// The licence texts ship inside the app (About → Licenses), copied from the repo so there is one copy of each.
val legalAssets = tasks.register<Copy>("legalAssets") {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(rootProject.file("NOTICE.md")) { rename { "NOTICE.txt" } }
    from(rootProject.file("pulse/NOTICE.md")) { rename { "NOTICE-PULSE.txt" } }
    from(rootProject.file("licenses"), rootProject.file("pulse/licenses"))
    into(layout.buildDirectory.dir("generated/legal/legal"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(legalAssets) }
