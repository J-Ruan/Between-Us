plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace = "cn.shiyan.betweenus"
 compileSdk = 35
 defaultConfig {
  applicationId = "cn.shiyan.betweenus"; minSdk = 26; targetSdk = 35
  versionCode = 1; versionName = "0.1.0-beta.1"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(project(":core"))
 implementation("androidx.exifinterface:exifinterface:1.4.1")
 implementation(platform("androidx.compose:compose-bom:2025.05.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
 implementation("androidx.room:room-runtime:2.7.2")
 implementation("androidx.room:room-ktx:2.7.2")
 implementation("net.zetetic:sqlcipher-android:4.9.0")
 ksp("androidx.room:room-compiler:2.7.2")
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
 implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
 implementation("androidx.biometric:biometric:1.1.0")
 implementation("com.google.code.gson:gson:2.13.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.room:room-testing:2.7.2")
}
