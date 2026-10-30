plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.streambd.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "streambdiptv.player"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        
        // 🔒 সিকিউরিটি: Activation API URL-টি Base64 এ এনকোড করা হলো 
        // (যাতে APK ডিকম্পাইল করলেও সহজে URL খুঁজে না পাওয়া যায়)
        buildConfigField("String", "ENCODED_API", "\"aHR0cHM6Ly9zdHJlYW1iZC1hY3RpdmF0aW9uLXNlcnZlci5zdHJlYW1iZGlwdHYud29ya2Vycy5kZXY=\"")
        
        // অ্যাক্টিভেশন এনাবল করা হলো
        buildConfigField("boolean", "ACTIVATION_ENABLED", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.tv:tv-material:1.0.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Net
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Media3
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    
    // DASH (MPEG-DASH) এবং SmoothStreaming সাপোর্ট
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.3.1")
    
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    implementation("io.coil-kt:coil-compose:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kapt { correctErrorTypes = true }
