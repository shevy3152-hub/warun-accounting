plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.warun.accounting"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.warun.accounting"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DATABASE_NAME", "\"warun-accounting.db\"")
    }

    buildTypes {
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            matchingFallbacks += listOf("debug")
            isDebuggable = true
            buildConfigField(
                "String",
                "DATABASE_NAME",
                "\"warun-accounting-instrumented.db\""
            )
            resValue("string", "app_name", "わるん会計 TEST")
        }
    }

    testBuildType = "instrumented"

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val expectedInstrumentedApplicationId = "com.warun.accounting.instrumented"
val blockedA90SerialFragment = "A90SM241201326"
val connectedAndroidTestTaskPattern = Regex("""connected.*AndroidTest""")

val verifyInstrumentedApplicationIds by tasks.registering {
    group = "verification"
    description = "Verifies that instrumentation targets only the isolated test application."
    dependsOn("assembleInstrumented", "assembleInstrumentedAndroidTest")

    doLast {
        val appMetadata = layout.buildDirectory
            .file("outputs/apk/instrumented/output-metadata.json")
            .get()
            .asFile
        val testManifest = layout.buildDirectory
            .file(
                "intermediates/packaged_manifests/instrumentedAndroidTest/" +
                    "processInstrumentedAndroidTestManifest/AndroidManifest.xml"
            )
            .get()
            .asFile

        check(appMetadata.isFile) {
            "Instrumented APK metadata was not generated: ${appMetadata.absolutePath}"
        }
        check(testManifest.isFile) {
            "Instrumented AndroidTest manifest was not generated: ${testManifest.absolutePath}"
        }

        val appId = Regex(""""applicationId"\s*:\s*"([^"]+)"""")
            .find(appMetadata.readText())
            ?.groupValues
            ?.get(1)
        val targetAppId = Regex("""android:targetPackage="([^"]+)"""")
            .find(testManifest.readText())
            ?.groupValues
            ?.get(1)

        check(appId == expectedInstrumentedApplicationId) {
            "Instrumentation APK must use $expectedInstrumentedApplicationId, but was $appId."
        }
        check(targetAppId == expectedInstrumentedApplicationId) {
            "AndroidTest must target $expectedInstrumentedApplicationId, but was $targetAppId."
        }
    }
}

val verifyNoA90ForInstrumentation by tasks.registering {
    group = "verification"
    description = "Blocks instrumentation while the accounting acceptance-test A90 is connected."

    doLast {
        val adb = androidComponents.sdkComponents.adb.get().asFile
        val devices = providers.exec {
            commandLine(adb.absolutePath, "devices", "-l")
        }.standardOutput.asText.get()
        val connectedDevices = devices
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter { it.contains(Regex("""\sdevice(?:\s|$)""")) }
            .toList()
        val a90 = connectedDevices.firstOrNull { device ->
            device.contains(blockedA90SerialFragment, ignoreCase = true) ||
                device.contains(Regex("""(?:^|\s)(?:product|model|device):A90(?:\s|$)"""))
        }

        check(a90 == null) {
            "A90 is reserved for manual acceptance testing. " +
                "Run instrumentation on an emulator or dedicated test device. Detected: $a90"
        }
    }
}

val verifyInstrumentationSafety by tasks.registering {
    group = "verification"
    description = "Verifies the isolated application ID and rejects the A90 before instrumentation."
    dependsOn(verifyInstrumentedApplicationIds, verifyNoA90ForInstrumentation)
}

tasks.configureEach {
    if (name == "connectedInstrumentedAndroidTest" || name == "connectedAndroidTest") {
        dependsOn(verifyInstrumentationSafety)
    } else if (connectedAndroidTestTaskPattern.matches(name)) {
        doFirst {
            throw GradleException(
                "$name is prohibited. Run connectedInstrumentedAndroidTest " +
                    "on an emulator or dedicated test device."
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    val cameraXVersion = "1.5.3"
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
