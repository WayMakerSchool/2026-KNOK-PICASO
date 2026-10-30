import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)

  id("org.jetbrains.kotlin.android")

  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

val knokV3 = providers.gradleProperty("knokV3").orNull == "true"
val knokV2 = !knokV3 && providers.gradleProperty("knokV2").orNull == "true"
val knokCandidate = knokV3 || knokV2 || providers.gradleProperty("knokCandidate").orNull == "true"

// Keep local builds working until Firebase's google-services.json is supplied.
// Once the file is placed in app/, the normal Google Services resources are generated.
if (!knokCandidate && file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = when {
      knokV3 -> "com.aistudio.quietneighbors.gspqzy.v3"
      knokV2 -> "com.aistudio.quietneighbors.gspqzy.v2overnight"
      knokCandidate -> "com.aistudio.quietneighbors.gspqzy.candidate"
      else -> "com.aistudio.quietneighbors.gspqzy"
    }
    manifestPlaceholders["knokAppLabel"] = if (knokV3) "KNOK V3" else if (knokV2) "KNOK V2 환경 검증" else if (knokCandidate) "KNOK 검증" else "@string/app_name"
    manifestPlaceholders["knokAppIcon"] = if (knokV3) "@drawable/knok_v3_launcher" else "@mipmap/ic_launcher"
    manifestPlaceholders["knokRoundIcon"] = if (knokV3) "@drawable/knok_v3_launcher" else "@mipmap/ic_launcher_round"
    minSdk = 24
    targetSdk = 36
    versionCode = if (knokV3) 5 else if (knokV2) 4 else 1
    versionName = if (knokV3) "3.2.2-model-score" else if (knokV2) "2.3-room-verification" else "1.0"
    buildConfigField("boolean", "KNOK_V3", knokV3.toString())
    buildConfigField("boolean", "KNOK_CANDIDATE", knokCandidate.toString())
    buildConfigField("boolean", "KNOK_V2", knokV2.toString())

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation("com.google.firebase:firebase-functions")
  debugImplementation("com.google.firebase:firebase-appcheck-debug")
  releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.storage)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.tensorflow.lite)
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
