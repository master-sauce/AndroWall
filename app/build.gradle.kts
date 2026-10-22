import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// version counter: versionName = <major>.<build> (build +1 per assemble)
// `major` lives in build.version.properties — set it to 4 to ship 4.1, 4.2, ...
// (versionCode = major*1000 + build, so it keeps growing across a major bump)
val versionFile = rootProject.file("build.version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}
var majorNo = versionProps.getProperty("major")?.toIntOrNull() ?: 3
var buildNo = versionProps.getProperty("build")?.toIntOrNull() ?: 0
if (gradle.startParameter.taskNames.any {
        val n = it.lowercase()
        n.contains("assemble") || n.contains("install") || n.contains("bundle")
    }
) {
    buildNo += 1
    if (buildNo > 999) {           // keep versionCodes monotonic across majors
        majorNo += 1
        buildNo = 1
    }
    Properties().apply {
        setProperty("major", majorNo.toString())
        setProperty("build", buildNo.toString())
        versionFile.outputStream().use { store(it, "AndroWall version counter: versionName = <major>.<build>") }
    }
}

// private-key signing (credentials in keystore.properties at project root)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.androwall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.androwall"
        minSdk = 24
        targetSdk = 36
        versionCode = majorNo * 1_000 + buildNo
        versionName = "$majorNo.$buildNo"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("androwall") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/androwall-release.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("androwall")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("androwall")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // versioned name — AndroWall-3.X.apk, visible in the releases tag
            output?.outputFileName = "AndroWall-$majorNo.$buildNo.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.foundation)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}