plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization") }
android {
    namespace = "app.termora.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.termora.android"
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += (providers.gradleProperty("termoraAbis").orNull ?: "arm64-v8a").split(",") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { getByName("release") {
        isMinifyEnabled = false
        if(providers.gradleProperty("termoraTestSigning").orNull=="true") signingConfig=signingConfigs.getByName("debug")
    } }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")); implementation("org.slf4j:slf4j-nop:2.0.17"); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation("androidx.test.ext:junit:1.2.1") }
val verifyRdp by tasks.registering {
    doLast { check((providers.gradleProperty("termoraAbis").orNull ?: "arm64-v8a").split(",").all { file("src/main/jniLibs/$it/libfreerdp-android.so").exists() }) { "Run scripts/build-rdp.sh before building the APK" } }
}
tasks.named("preBuild") { dependsOn(verifyRdp) }
