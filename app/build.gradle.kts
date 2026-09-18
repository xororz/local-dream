import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    android.set(true)
    version.set("1.8.0")
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/cpp/3rdparty/") }
    }
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom("$projectDir/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(files("src/main/java", "src/main/kotlin"))
}

android {
    namespace = "io.github.xororz.localdream"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.xororz.localdream"
        minSdk = 28
//        minSdk = 31
        targetSdk = 36
        versionCode = 74
        versionName = "2.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.findProperty("RELEASE_STORE_FILE") as String? ?: "keystore.jks")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
        }
    }

    bundle {
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
//            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("basic") {
            dimension = "version"
            versionNameSuffix = ""
        }
        create("filter") {
            dimension = "version"
            versionNameSuffix = "_with_filter"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = output.versionName.orNull
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("LocalDream_armv8a_$versionName.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material3.xml)
    implementation(libs.coil.compose)
    implementation(libs.cropify)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Adds the ktlint-rule wrappers to detekt; we only enable UnusedImports
    // (the standalone ktlint plugin's no-unused-imports does not flag them).
    detektPlugins(libs.detekt.formatting)
}

// The backend is an executable launched in a separate process, packaged as
// a .so so Android extracts it into nativeLibraryDir.
val nativeProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val buildNativeCore by tasks.registering(Exec::class) {
    workingDir("src/main/cpp")
    val cmakeBin = nativeProperties.getProperty("cmake.dir")?.let { "$it/bin:" }.orEmpty()
    environment("PATH", "${cmakeBin}${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH")}")
    environment("ANDROID_NDK_ROOT", androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath)
    val qnnSdk = nativeProperties.getProperty("qnn.sdk.dir") ?: System.getenv("QNN_SDK_ROOT")
    commandLine(listOf("bash", "build.sh") + listOfNotNull(qnnSdk?.let { "-DQNN_SDK_ROOT=$it" }))
    inputs.files(fileTree("src/main/cpp") { exclude("build/**", "**/.git/**") })
    inputs.property("qnnSdk", qnnSdk.orEmpty())
    outputs.file("src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so")
    outputs.dir("src/main/assets/qnnlibs")
    outputs.dir("src/main/assets/licenses/qnn-2.50.0.260828")
}
tasks.named("preBuild").configure { dependsOn(buildNativeCore) }
