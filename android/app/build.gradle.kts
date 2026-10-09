import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名优先从 PANDA_RELEASE_KEYSTORE_PROPERTIES 指定的外置文件读取，
 * 未设置时兼容仓库根目录的 keystore.properties。
 * 该文件已被 .gitignore 排除，签名文件与口令都不入库、不进源码压缩包。
 *
 * 未提供 keystore.properties 时，release 变体退回 Android 默认调试密钥签名，
 * 使 assembleRelease 仍能产出可直接安装的 APK（仅用于本地联调，
 * 不可用于分发；一旦补齐正式签名，产物签名会随之改变）。
 */
val externalSigningProperties = providers.environmentVariable("PANDA_RELEASE_KEYSTORE_PROPERTIES")
    .orNull?.trim()?.takeIf { it.isNotEmpty() }
val keystorePropertiesFile = rootProject.file(externalSigningProperties ?: "keystore.properties")
if (externalSigningProperties != null) {
    require(keystorePropertiesFile.isFile) { "External Release signing properties file is missing" }
}
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()
if (hasReleaseSigning) {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { property ->
        require(!keystoreProperties.getProperty(property).isNullOrBlank()) {
            "Release signing properties missing required field: $property"
        }
    }
}

/**
 * OTA 授权密钥从仓库根目录的 ota.properties 读取，再经 BuildConfig 注入代码。
 * 该文件已被 .gitignore 排除：密钥属凭据，不入库、不进 src.zip。
 *
 * 未提供时注入空串——App 构建照常成功，只是 OTA 授权密钥输入框不预填
 * （固件侧缺密钥会直接编译失败，两侧行为刻意不同：固件没有密钥就是不可用状态）。
 */
val otaPropertiesFile = rootProject.file("ota.properties")
val otaProperties = Properties().apply {
    if (otaPropertiesFile.exists()) {
        otaPropertiesFile.inputStream().use { load(it) }
    }
}
val otaDefaultAuthKey = otaProperties.getProperty("authKey", "").trim()

android {
    namespace = "com.example.pandatemperature"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.pandatemperature"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OTA 授权密钥（凭据）：来自 ota.properties，未配置时为空串。
        // 代码侧见 OtaConstants.DEFAULT_AUTH_KEY。
        buildConfigField("String", "OTA_DEFAULT_AUTH_KEY", "\"$otaDefaultAuthKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val configuredStoreFile = keystoreProperties.getProperty("storeFile")
                // External properties resolve relative stores beside the properties file;
                // the legacy local configuration keeps its existing path behavior.
                storeFile = if (externalSigningProperties != null) {
                    keystorePropertiesFile.parentFile.resolve(configuredStoreFile)
                } else file(configuredStoreFile)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 优先使用 keystore.properties 的正式签名；缺失时退回调试密钥，
            // 保证 release APK 仍可安装，便于真机联调。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
        // 用于注入 OTA 授权密钥（见 defaultConfig.buildConfigField）
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material Icons Extended - 包含所有 Material Icons（如 Bluetooth, Thermostat, WaterDrop 等）
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.accompanist.swiperefresh)
    
    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Google Play Services Location（用于GPS定位）
    implementation("com.google.android.gms:play-services-location:21.1.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    /**
     * com.google.android.gms:play-services-location 经由 play-services-base
     * 传递引入 androidx.fragment:fragment:1.0.0。该版本早于
     * ComponentActivity.registerForActivityResult 所要求的 1.3.0，
     * 导致 release 变体的 lintVitalRelease 直接失败（235 条同源错误）。
     *
     * 本项目不使用 Fragment，因此这里只提升版本下限、不新增直接依赖，
     * 既满足 lint 要求，也避免把 2018 年的旧库打进发布包。
     */
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("play-services-base 传递依赖 fragment:1.0.0，低于 ActivityResult API 要求的 1.3.0")
        }
    }
}