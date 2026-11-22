import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val playSigningFile = providers.gradleProperty("shotguidePlaySigningFile").orNull?.let { rootProject.file(it) }

android {
    namespace = "com.summer.shotguide"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.summer.shotguide.en"
        minSdk = 29
        targetSdk = 36
        versionCode = 23
        versionName = "0.16.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64 给真机，x86_64 留给本地模拟器联调。
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    if (playSigningFile != null) {
        val properties = Properties().apply { playSigningFile.inputStream().use { load(it) } }
        fun signingValue(key: String): String = properties.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("Missing $key in the Play signing properties.")
        val uploadConfig = signingConfigs.create("playUpload") {
            storeFile = playSigningFile.parentFile.resolve(signingValue("storeFile"))
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            keyPassword = signingValue("keyPassword")
        }
        buildTypes.named("release") { signingConfig = uploadConfig }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDirs(
        rootProject.file("lineart-android/app/src/main/assets"),
        rootProject.file("lineart-android/licenses"),
    )
    androidResources {
        noCompress += "onnx"
        ignoreAssetsPatterns += listOf("!lineart_fine.onnx", "!selfie_multiclass.tflite")
    }
}
dependencies {
    implementation(project(":lineartCore"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
