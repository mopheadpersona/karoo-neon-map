plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "local.karoo.gtavimap"
    compileSdk = 34

    defaultConfig {
        applicationId = "local.karoo.gtavimap"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
    }

    // The key the published builds are signed with (keystore/README.md), when it is there. It is
    // not in git. Without it a build is signed with Android's own debug key, which installs just
    // as well but cannot update a build signed with the other key.
    val key = file("../keystore/local.jks")
    signingConfigs {
        if (key.exists()) create("local") {
            storeFile = key
            storePassword = "gtavimap"
            keyAlias = "gtavimap"
            keyPassword = "gtavimap"
        }
    }
    buildTypes {
        debug {
            if (key.exists()) signingConfig = signingConfigs.getByName("local")
        }
        release {
            isMinifyEnabled = false
            if (key.exists()) signingConfig = signingConfigs.getByName("local")
        }
    }

    // The two map themes written by make_karoo_theme.py go into the app as they are.
    sourceSets["main"].assets.srcDir("../../karoo-native")
    // The place list for the map icons, packed by make_places.py from OpenStreetMap (not in git).
    sourceSets["main"].assets.srcDir("../places")
    // The fonts of the data fields: free ones under the SIL Open Font Licence (fonts/README.md).
    sourceSets["main"].assets.srcDir("../fonts")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":karoo-ext-lib"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
}
