import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.spotless)
}

fun getVersionProps(propName: String): String {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

android {
    namespace = "io.nekohasekai.sfa"
    compileSdk = 37
    compileSdkMinor = 1

    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "${projectDir}/schemas")
    }

    defaultConfig {
        applicationId = "io.chainbox.app"
        minSdk = 24
        targetSdk = 35
        versionCode = getVersionProps("VERSION_CODE").toInt()
        versionName = getVersionProps("VERSION_NAME")
        buildConfigField("String", "FLAVOR", "\"other\"")
        buildConfigField("String", "KERNEL_TAG", "\"${getVersionProps("KERNEL_TAG")}\"")
        buildConfigField("String", "KERNEL_UPSTREAM", "\"${getVersionProps("KERNEL_UPSTREAM")}\"")
        buildConfigField("String", "KERNEL_COMMIT", "\"${getVersionProps("KERNEL_COMMIT")}\"")
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(FileInputStream(localPropsFile))
        }
        val releaseStore = file("release.keystore")
        val releasePass = localProps.getProperty("KEYSTORE_PASS").orEmpty()
        val releaseAlias = localProps.getProperty("ALIAS_NAME").orEmpty()
        val releaseKeyPass = localProps.getProperty("ALIAS_PASS").orEmpty()
        if (releaseStore.exists() && releasePass.isNotBlank() && releaseAlias.isNotBlank() && releaseKeyPass.isNotBlank()) {
            create("releaseConfig") {
                storeFile = releaseStore
                storePassword = releasePass
                keyAlias = releaseAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.findByName("releaseConfig")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    sourceSets {
        getByName("main") {
            java.directories.addAll(listOf(
                "src/minApi24/java",
                "src/other/java",
                "src/github/java"
            ))
            aidl.directories.add("src/minApi24/aidl")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        generateLocaleConfig = false
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        fatal += "NewApi"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

gradle.taskGraph.whenReady {
    val wantsReleaseApk = gradle.taskGraph.allTasks.any { task ->
        val n = task.name
        n.contains("Release") &&
            !n.contains("Debug") &&
            (
                n.startsWith("assemble") ||
                    n.startsWith("bundle") ||
                    n.startsWith("package") ||
                    n.contains("publish", ignoreCase = true)
                )
    }
    if (wantsReleaseApk && android.signingConfigs.findByName("releaseConfig") == null) {
        throw GradleException(
            "Release signing is unavailable. Configure release.keystore and signing properties; refusing debug-signed release builds.",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(files("libs/libbox.aar"))
    val lifecycleVersion = "2.11.0"
    val roomVersion = "2.8.4"
    val workVersion = "2.11.2"
    val cameraVersion = "1.6.1"
    val browserVersion = "1.10.0"
    val webkitVersion = "1.16.0"
    val coreVersion = "1.19.0"
    val materialVersion = "1.14.0"
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.6.0-android")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.work:work-runtime-ktx:$workVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.browser:browser:$browserVersion")
    implementation("androidx.webkit:webkit:$webkitVersion")
    implementation("androidx.core:core-ktx:$coreVersion")
    implementation("com.google.android.material:material:$materialVersion")
    val soraVersion = "0.23.6"
    val treeSitterVersion = "4.3.2"
    implementation("io.github.Rosemoe.sora-editor:editor:$soraVersion")
    implementation("io.github.Rosemoe.sora-editor:language-treesitter:$soraVersion")
    implementation("com.itsaky.androidide.treesitter:android-tree-sitter:$treeSitterVersion")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-json:$treeSitterVersion")
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    val libsuVersion = "6.0.0"
    implementation("com.github.topjohnwu.libsu:core:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:service:$libsuVersion")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    val activityVersion = "1.13.0"
    val lifecycleComposeVersion = "2.11.0"
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:$activityVersion")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion")
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.github.jeziellago:compose-markdown:0.7.2")
    implementation("org.kodein.emoji:emoji-kt:2.5.0")
    implementation("org.mozilla:rhino:1.7.15")
    val libghosttyVersion = "0.1.0-alpha01"
    implementation("io.github.sagernet:libghostty-android:$libghosttyVersion")
    implementation("io.github.sagernet:libghostty-android-extras:$libghosttyVersion")
    implementation("io.github.sagernet:libghostty-android-compose:$libghosttyVersion")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly(project(":libxposed-api"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_blank-line-before-declaration" to "disabled",
                "ktlint_standard_blank-line-between-when-conditions" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ))
    }
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}

tasks.register("compileOtherDebugKotlin") {
    dependsOn("compileDebugKotlin")
}

tasks.register("assembleOtherDebug") {
    dependsOn("assembleDebug")
}

tasks.register("assembleOtherRelease") {
    dependsOn("assembleRelease")
}

tasks.register("testOtherDebugUnitTest") {
    dependsOn("testDebugUnitTest")
}
