plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.syntx.btbridge.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.syntx.btbridge.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 20
        versionName = "2.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.runtime:runtime:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")

    // Wear Compose: Material для часов + ScalingLazyColumn.
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
