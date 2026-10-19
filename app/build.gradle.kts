import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 基础版本号取仓库根目录 VERSION 文件（跟随 cloudphone，各组件一致），缺省 1.0.0
val baseVersionName: String = run {
    var dir: File? = rootProject.rootDir
    while (dir != null) {
        val f = File(dir, "VERSION")
        if (f.isFile) {
            val v = f.readText().trim().removePrefix("v").removePrefix("V").trim()
            if (v.isNotEmpty()) return@run v
        }
        dir = dir.parentFile
    }
    "1.0.0"
}

// WebrtcPhone 本地打包号：每次打包在 build.number 里递增，versionName 形如 0.4.2-1。
// 基础版本仍跟随 cloudphone VERSION，不在本仓库复制。build.number 缺省 0（无后缀）。
val buildNumber: Int = rootProject.file("build.number")
    .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0

val appVersionName: String = if (buildNumber > 0) "$baseVersionName-$buildNumber" else baseVersionName

// versionCode = 基础版本数字拼接 * 1000 + 打包号，保证每次打包单调递增
val appVersionCode: Int = (baseVersionName.split(".")
    .fold(0) { acc, part -> acc * 100 + (part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0) } * 1000 + buildNumber)
    .coerceAtLeast(1)

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propOrEnv(name: String): String? =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: System.getenv(name)

android {
    namespace = "com.webrtc_phone.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webrtc_phone.android"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            // 签名凭据只从 gradle 属性 / local.properties / 环境变量读取，不入库
            val storePath = propOrEnv("KEYSTORE_FILE")
            if (storePath != null && File(storePath).exists()) {
                storeFile = File(storePath)
                storePassword = propOrEnv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = propOrEnv("KEY_ALIAS") ?: ""
                keyPassword = propOrEnv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    applicationVariants.all {
        val variantName = name
        val vName = versionName
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "WebrtcPhone-v${vName}-${variantName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
