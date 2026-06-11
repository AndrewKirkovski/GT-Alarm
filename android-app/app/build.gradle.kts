import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Load keystore credentials from gitignored keystore.properties. The shared
// .p12 holds TWO keys: `gtwake.phone` (RSA 2048) signs THIS phone app —
// Google Play App Signing requires RSA and rejects EC — while `gtalarm.watch`
// (EC P-256) signs the AppGallery-only watch HAP. keystore.properties selects
// the phone alias. Without this signing config on both debug AND release
// buildTypes, debug builds fall back to Android Studio's default debug
// keystore (CN=Android Debug), whose fingerprint will NOT match the one
// registered in AGConnect Console or the watch's `supportLists` value —
// Wear Engine pairing fails silently with "peer app not authorized".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Read AGConnect appid from agconnect-services.json so the manifest meta-data
// stays in lock-step with the registered AGConnect Console entry. Manual
// declaration (the @SerializedName "appid=...") is still required because
// `agcp { manifest = false }` skips auto-injection (AGP 9 incompat).
val agconnectAppId: String = run {
    val f = file("agconnect-services.json")
    if (!f.exists()) {
        ""
    } else {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(f) as Map<String, Any>
        val client = json["client"] as? Map<*, *>
        client?.get("app_id")?.toString().orEmpty()
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.huawei.agconnect)
}

// AGCP 1.9.5.302 manifest-injection uses AGP internals (`getMetadataByConfig()`)
// removed in AGP 9.1; disable until AGCP ships an AGP-9 compatible release.
// Side effect: agconnect-services.json is build-time-only (read by `agconnectAppId`
// above for the manifestPlaceholder). Wear Engine reads the appid from the
// manifest meta-data, so functionality is preserved. Adding any AGConnect
// runtime SDK (analytics, push, crash) requires re-enabling this and bumping
// AGCP — tracked in Phase 2.
agcp {
    manifest = false
}

android {
    namespace = "com.kirkouski.gtwake.companion"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kirkouski.gtwake.companion"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        manifestPlaceholders["agconnectAppId"] = "appid=$agconnectAppId"
    }

    // Single signing config for both debug and release: the phone alias
    // `gtwake.phone` (RSA 2048, cert SHA-256 95:F6:00:1E:…) is the
    // AGConnect-registered signer for THIS app. (The watch HAP is signed
    // separately with the EC `gtalarm.watch` key.) See memory:keystore_path.md
    // for fingerprints + AGConnect pairing. CI must gate release builds on
    // keystore.properties presence — AGP otherwise silently produces an
    // unsigned release APK.
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("shared") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storeType = keystoreProps.getProperty("storeType") ?: "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        debug {
            // No applicationIdSuffix and the same signing key as release: the
            // watch's supportLists entry is keyed on (applicationId + cert
            // SHA-256). A `.debug` suffix or AGP's auto debug keystore would
            // break pairing. Side-by-side install of release + local debug
            // requires a separate AGConnect Console entry — not supported.
            isMinifyEnabled = false
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // Treat every kotlinc warning as a build failure. The corollary —
            // every @Suppress(...) on Kotlin code MUST carry an adjacent
            // `// reason: ...` comment per repo policy in docs/execution-plan.md.
            // Undocumented suppression = no reason.
            allWarningsAsErrors.set(true)
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig: enables BuildConfig.DEBUG gate in DatabaseModule so
        // destructive migration is debug-only. Release builds get strict
        // migrations.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Make android.util.Log calls a no-op in JVM unit tests instead of
        // throwing "Method d not mocked" — required for any test that
        // exercises code paths with Log.d/i/w/e (e.g. IncomingMessageHandler).
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Pure-JVM JUnit tests over our domain layer don't need much.
            // Smaller is better when the host is under memory pressure
            // (DevEco + Android Studio + Gradle daemon all fighting for
            // RAM); paging-file-too-small Windows errors show up at the
            // default unconstrained heap.
            it.maxHeapSize = "768m"
            it.jvmArgs("-XX:MaxMetaspaceSize=256m", "-XX:+UseSerialGC")
        }
    }

    lint {
        // Strict policy. Every warning fails the build unless either:
        //   - silenced in lint.xml with an inline comment + reason, or
        //   - present in lint-baseline.xml against a tracked phase in
        //     docs/execution-plan.md.
        // The baseline is auto-generated on first run and snapshotted in git
        // so reviewers can see exactly what's deferred and why.
        //
        // checkDependencies intentionally OFF: scanning transitive deps blew
        // the lint daemon's heap on first try. Phase 2 may revisit once
        // version pins line up and we can give Gradle more memory.
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        lintConfig = file("lint.xml")
        baseline = file("lint-baseline.xml")
    }
}

// Detekt — Kotlin code-smell linter. Companion to lint (resources/manifest)
// and kotlinc warnings. Catches cyclomatic complexity, dead code, swallowed
// exceptions, coroutine misuse, unsafe nullable calls. Same strict policy as
// lint: any suppression in detekt.yml needs a `// reason:` adjacent comment;
// any baseline entry must reference a tracked phase in docs/execution-plan.md.
detekt {
    config.from(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    autoCorrect = false
}

// Limit detekt's source set to hand-written code only. KSP/Hilt/Room
// generators emit Kotlin that intentionally violates detekt rules
// (long lines, underscore-prefixed variable names) — those are not
// code-smells, they're generated patterns. Override the source root
// rather than relying on `exclude(...)` which doesn't reliably filter
// AGP-injected generated source dirs in Detekt 2.0.
val detektSourceFiles = files(
    "src/main/java",
    "src/main/kotlin",
    "src/test/java",
    "src/test/kotlin",
    "src/androidTest/java",
    "src/androidTest/kotlin",
)

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget = "17"
    setSource(detektSourceFiles)
    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(false)
        markdown.required.set(false)
    }
}
tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
    setSource(detektSourceFiles)
}

// Make `check` (and the Android `check` chain) include detektDebug so CI
// or the standard verification command catches new smells.
afterEvaluate {
    tasks.named("check").configure { dependsOn("detektDebug") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material.components)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.androidx.datastore.preferences)

    // PickTime-Compose — wheel/odometer time picker. Apache-2.0. Hosted on
    // JitPack (group `com.github.anhaki`) — repo scope in settings.gradle.kts
    // restricts JitPack to that group only so this isn't a supply-chain hole.
    implementation(libs.picktime.compose)

    // Huawei Wear Engine SDK — phone-side P2P transport to Sports Watch / Lite
    // Wearable. Resolved against the Huawei Maven repo declared in
    // settings.gradle.kts dependencyResolutionManagement.
    implementation(libs.huawei.wearengine)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    // Real org.json — overrides the Android stub on the unit-test classpath
    // so WearJsonCodecTest can exercise actual parsing semantics rather
    // than the default-value stubs Android ships for JVM tests.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
