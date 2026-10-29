import java.util.Properties
import java.io.ByteArrayOutputStream

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

// 执行 git 命令的辅助工具（带 fallback，避免离线源码构建报错）
fun gitCommand(vararg args: String): String {
    return try {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", *args)
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        stdout.toString().trim()
    } catch (_: Exception) {
        ""
    }
}

val gitCommitCount: Int = gitCommand("rev-list", "--count", "HEAD").toIntOrNull() ?: 12
val gitCommitHash: String = gitCommand("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }
val isGitDirty: Boolean = gitCommand("status", "--porcelain").isNotEmpty()
val gitExactTag: String = gitCommand("describe", "--tags", "--exact-match")

// versionName：若在 Release Tag 上则为纯版本号，否则形如 0.4.2-75e0d39（脏工作区追加 -dirty）
val appVersionName: String = if (gitExactTag.isNotEmpty()) {
    baseVersionName
} else {
    val dirtySuffix = if (isGitDirty) "-dirty" else ""
    "$baseVersionName-$gitCommitHash$dirtySuffix"
}

// versionCode = Major * 10,000,000 + Minor * 100,000 + Patch * 1,000 + gitCommitCount
// 保证 32 位正整数内单调递增，永不溢出
val appVersionCode: Int = run {
    val parts = baseVersionName.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    (major * 10_000_000 + minor * 100_000 + patch * 1_000 + gitCommitCount).coerceAtLeast(1)
}

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

// 同步被控端二进制资产：优先从项目本地 release/agentd 同步，若不存在则保持现状（由 CI 或开发者手动放置）
val syncAgentAssetsTask = tasks.register("syncAgentAssets") {
    val localAgentDir = File(rootProject.rootDir, "../../release/agentd")
    val assetsDir = file("src/main/assets")
    inputs.dir(localAgentDir).optional()
    outputs.dir(assetsDir)
    doLast {
        if (localAgentDir.isDirectory) {
            assetsDir.mkdirs()
            listOf("cloudphone-agent-arm64", "cloudphone-agent-armeabi-v7a", "cloudphone-agent-amd64", "libsys_core.so").forEach { name ->
                val src = File(localAgentDir, name)
                val dst = File(assetsDir, name)
                if (src.isFile && (!dst.exists() || src.lastModified() > dst.lastModified() || src.length() != dst.length())) {
                    src.copyTo(dst, overwrite = true)
                    logger.lifecycle("Synced agent asset: $name -> ${dst.path}")
                }
            }
        }
    }
}
tasks.named("preBuild") {
    dependsOn(syncAgentAssetsTask)
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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
