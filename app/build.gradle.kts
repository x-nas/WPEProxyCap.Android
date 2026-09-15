import java.security.SecureRandom
import java.util.Properties

plugins {
    id("com.android.application")
}

/*
  两份不入库的本机配置（都在仓库根目录）：

  local.properties     sdk.dir=…（Android Studio / sdkmanager 自动写）
                       wpc.apiKey=…（订阅服务器的 X-Api-Key；不填也能编译，但连不上订阅服务器）
  keystore.properties  storeFile / storePassword / keyAlias / keyPassword（发布签名；不填则发布包不签名）

  ⚠️ 发布签名的密钥就是「设备身份」的一部分：ANDROID_ID 按签名密钥区分，换了密钥发版，
     所有用户在 WPE 那边都会变成新设备（可能撞上设备数上限）。密钥生成一次、异地备份、永远用同一把。
*/
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val apiKey: String = (localProps.getProperty("wpc.apiKey") ?: System.getenv("WPC_API_KEY") ?: "").trim()

// ApiKey 不以字符串进 APK：拆成两段异或（A = key XOR B，B 每次构建随机），运行时由 service/ApiKey.kt 拼回。
// 与 Windows 版 ProxyService.ApiKey() 同一种做法 —— 只挡「解压 APK 搜字符串」，挡不住反编译。
val apiKeyB: ByteArray = ByteArray(apiKey.length).also { SecureRandom().nextBytes(it) }
val apiKeyA: ByteArray = apiKey.toByteArray(Charsets.US_ASCII).mapIndexed { i, c -> (c.toInt() xor apiKeyB[i].toInt()).toByte() }.toByteArray()
fun javaBytes(b: ByteArray): String = b.joinToString(",", "new byte[]{", "}") { "(byte)0x%02X".format(it.toInt() and 0xFF) }

android {
    namespace = "com.wpe64.wpc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wpe64.wpc"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ABI 只在下面的 splits 里限定（arm64-v8a 给真机、x86_64 给模拟器）。
        // ⚠️ AGP 9 不允许 ndk.abiFilters 与 splits.abi 同时设置，别在这里再写一遍。

        buildConfigField("byte[]", "WPC_KEY_A", javaBytes(apiKeyA))
        buildConfigField("byte[]", "WPC_KEY_B", javaBytes(apiKeyB))
        buildConfigField("String", "KERNEL_VERSION", "\"v1.19.21\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // mihomo 内核（core/ 由 tools/build-core.sh 用 gomobile 编成 AAR）
    implementation(files("libs/wpccore.aar"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 单元测试里是空桩，测试要用真的实现
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
