import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // The @Serializable navigation keys (nav/Dest.kt).
    alias(libs.plugins.kotlin.serialization)
}

// AGP cannot shrink ABI APK resources and build an AAB in one configuration (Google issue 402800800).
// Bundle invocations use the same native inputs with ABI delivery controlled by the bundle itself.
val abiApks = !providers.gradleProperty("cassiniBundle").map { it.toBooleanStrict() }.getOrElse(false)

android {
    namespace = "io.codemyriad.cassini"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // libcassini_opus is built in :engine; the app's NDK strips the packaged native libraries.
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "io.codemyriad.cassini"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 16
        versionName = "0.4.2-beta.1"
        if (!abiApks) ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Builds the graph on a scratch root (docs/architecture.md section 9).
        testInstrumentationRunner = "io.codemyriad.cassini.CassiniTestRunner"
    }
    splits {
        abi {
            isEnable = abiApks
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    // debug, or releaseTest for tag builds. The minified release build cannot be instrumented.
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    // Release signing is configured only when CI or a maintainer supplies the keystore and its secrets. The same
    // key signs the GitHub APK and the Play bundle, and Play App Signing holds it too (docs/release.md).
    val releaseKeystore = providers.environmentVariable("CASSINI_RELEASE_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                fun secret(name: String) = providers.environmentVariable(name).orNull
                    ?: throw GradleException("CASSINI_RELEASE_KEYSTORE_FILE is set but $name is not.")
                storeFile = file(releaseKeystore)
                storePassword = secret("CASSINI_RELEASE_STORE_PASSWORD")
                keyAlias = secret("CASSINI_RELEASE_KEY_ALIAS")
                keyPassword = secret("CASSINI_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // The release build without R8, for device tests: they call app and Kotlin library members that
        // R8 removes from the minified APK. Tag builds instrument this APK, then install the published
        // release APK over it and check that it launches (scripts/ci-emulator-smoke.sh).
        create("releaseTest") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    // BuildConfig.DEBUG gates StrictMode and the files/debug-root.txt override (architecture 2.5).
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // No baseline: every finding is fixed or, in lint.xml, ignored with its reason.
        lintConfig = file("lint.xml")
    }
    sourceSets.getByName("main") {
        assets.srcDir(rootDir.resolve(".tools/whistle-package/assets"))
        jniLibs.srcDir(rootDir.resolve(".tools/whistle-package/jni"))
    }
    androidResources { noCompress += listOf("cact", "onnx") }
    packaging.jniLibs.excludes += setOf("lib/armeabi-v7a/**", "lib/x86/**")
    packaging.jniLibs.keepDebugSymbols += setOf(
        "**/libcassini_needle.so",
        "**/libcassini_whistle_ort.so",
        "**/libcassini_whistle_weights.so",
    )
    // The conformance vectors and the golden checkpoint are :core test data, not :app test inputs.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // LicenseAssetsTest compares the APK's copies with these repository files. Declaring them as
                // inputs, relative to their directories, re-runs the tests when one changes instead of leaving
                // them up to date or taking them from the build cache.
                it.inputs.files(
                    rootDir.resolve("THIRD_PARTY_NOTICES.md"),
                    rootDir.resolve("PRIVACY.md"),
                    rootDir.resolve("scripts/native-dependencies.json"),
                    file("src/main/assets/licenses"),
                ).withPropertyName("licenseSources").withPathSensitivity(PathSensitivity.RELATIVE)
                // The AppGraph tests seal the committed FLEURS clip, as the :data tests do.
                it.inputs.dir(rootDir.resolve("fixtures/audio"))
                    .withPropertyName("fixtures")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // A local copy: the lambda must not capture the script object (configuration cache).
                val fixturesPath = rootDir.resolve("fixtures").absolutePath
                it.jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dcassini.fixtures=$fixturesPath") })
                // Robolectric needs these on JDK 17.
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                )
            }
        }
    }
    bundle {
        abi { enableSplit = true }
        language { enableSplit = false }
    }
    sourceSets {
        // Device tests read the committed CC-BY-4.0 FLEURS clip from fixtures/audio. The local-only YouTube clips
        // (tools/fetch-youtube-audio.sh output in src/androidTest/assets) are packaged when present and never
        // committed. The private meeting clips (tools/private-fixtures) go into the :bench APK only.
        getByName("androidTest") {
            assets.directories.add(rootDir.resolve("fixtures/audio").path)
        }
        // The loopback network configuration and the test files provider of the instrumented build types
        // (src/instrumented).
        for (type in listOf("debug", "releaseTest")) {
            getByName(type) {
                res.directories.add("src/instrumented/res")
                java.directories.add("src/instrumented/java")
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The design system and the stateless screens (docs/design/ui.md).
    implementation(project(":ui"))
    implementation(project(":core"))
    // The native wrappers, libcassini_opus, sherpa-onnx and the zstd-jni AAR (engine/build.gradle.kts).
    implementation(project(":engine"))
    // The Room catalogue, DataStore settings and file repositories that AppGraph wires (architecture 2.3 and 2.5).
    implementation(project(":data"))
    // Catalogue exposes the Room database (it closes it in tests); :data keeps Room as an implementation detail.
    implementation(libs.room.runtime)
    implementation(libs.coroutines.android)
    // ProcessingService: LifecycleService, ServiceCompat, NotificationCompat, getContextForLanguage; ProcessLifecycleOwner
    // for ON_START (architecture 5.3 and 5.7).
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // MainActivity is an AppCompatActivity only for AppCompatDelegate.setApplicationLocales (architecture 6).
    implementation(libs.appcompat)
    // The Compose UI: one activity, Navigation 3 destinations, one ViewModel per destination.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.bundles.compose)
    implementation(libs.bundles.navigation3)
    implementation(libs.serialization.json)
    // The JVM unit tests decompress with the zstd-jni jar, which carries the desktop natives.
    testImplementation(libs.zstd.jni)
    testImplementation(libs.org.json)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Espresso.pressBack in AppShellTest: it reaches the focused window, so a sheet gets back first.
    testImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    // ModelDownloadDeviceTest compresses its loopback model files; the APK already carries the AAR (:engine).
    androidTestImplementation("${libs.zstd.jni.get()}@aar")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // enableAccessibilityChecks() runs only on a device: under Robolectric ATF skips Compose content (P2 outcome).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    // ScreenAccessibilityTest shows :ui screens in the empty ComponentActivity of this manifest. It is in both device
    // test build types (tag builds instrument releaseTest), and their manifests make it not exported. The release
    // build does not have it.
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
    "releaseTestImplementation"(platform(libs.compose.bom))
    "releaseTestImplementation"(libs.compose.ui.test.manifest)
}

val checkWhistlePackage = tasks.register("checkWhistlePackage") {
    val manifest = rootDir.resolve(".tools/whistle-package/assets/whistle/manifest.json")
    doLast {
        if (!manifest.isFile) throw GradleException("Whistle worker assets missing. Run scripts/setup.sh.")
    }
}
tasks.named("preBuild") { dependsOn(checkWhistlePackage) }

// Keep the documented install paths while publishing ABI-specific outputs in the generated metadata.
for (variant in listOf("debug", "release", "releaseTest")) {
    val capitalized = variant.replaceFirstChar { it.uppercase() }
    val signed = providers.environmentVariable("CASSINI_RELEASE_KEYSTORE_FILE").isPresent
    val suffix = if (variant == "debug" || signed) "" else "-unsigned"
    val universal = layout.buildDirectory.file("outputs/apk/$variant/app-universal-$variant$suffix.apk")
    val legacy = layout.buildDirectory.file("outputs/apk/$variant/app-$variant$suffix.apk")
    val alias = tasks.register("stage${capitalized}UniversalApk") {
        dependsOn("package$capitalized")
        inputs.file(universal)
        outputs.file(legacy)
        doLast { universal.get().asFile.copyTo(legacy.get().asFile, overwrite = true) }
    }
    tasks.matching { it.name == "assemble$capitalized" }.configureEach { finalizedBy(alias) }
}
