import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.kirkouski.gtalarm"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kirkouski.gtalarm"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
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
    implementation(libs.compose.material.icons.extended)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
