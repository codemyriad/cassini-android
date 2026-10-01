plugins { id("com.android.application") }

android {
    namespace = "org.cassini.probe"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.cassini.probe"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Fetched and checksum-verified by scripts/run-litert-gpu-probe.sh.
    // Avoid pulling AI-pack delivery/lifecycle dependencies into this isolated probe.
    implementation(files(
        rootProject.file("../../.tools/litert-2.2.0.aar"),
        rootProject.file("../../.tools/litert-api-2.2.0.aar"),
    ))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
