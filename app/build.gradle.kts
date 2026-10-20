import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dslauncher"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dslauncher"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "1.03"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("releaseKey") {
            // 私有 release 签名：RSA2048 / 有效期约 27 年，仅本机持有。
            storeFile = file("../dslauncher-release.jks")
            storePassword = "DsL2026SecPass"
            keyAlias = "dslauncher"
            keyPassword = "DsL2026SecPass"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // 体积关键：开启代码压缩 + 资源压缩。
            // 之前 isMinifyEnabled=false 且 proguard 是 no-op，等于完全不压缩，
            // 开启 R8 后会自动剥掉未使用的图标、Compose 代码与资源。
            // -Pforhardening=true 时全关：加固壳自带变换，R8 优化后的代码再过一层壳
            // 容易出现「打开几秒就闪退」——送加固的包用不压缩的基线。
            val forHardening = project.hasProperty("forhardening")
            // -Pnoobf=true：仍然压缩（R8 裁剪/优化/资源压缩），但「不混淆」——
            // 类名/方法名保持原样、保留行号，崩溃栈可读。用于需要可读栈的场景。
            val noObf = project.hasProperty("noobf")
            isMinifyEnabled = !forHardening
            isShrinkResources = !forHardening
            if (!forHardening) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    if (noObf) "proguard-rules-nontobf.pro" else "proguard-rules.pro"
                )
            }
            // -Punsigned=true 时不签名（输出 app-release-unsigned.apk，供加固后再签）
            if (!project.hasProperty("unsigned")) {
                signingConfig = signingConfigs.getByName("releaseKey")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // sshj / BouncyCastle 的 jar 里带了签名与重复的 META-INF 条目，
    // 不排除会在打包阶段报 duplicate resource。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // 预览工具仅调试期需要，release 不打包
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // 图标：只依赖 core（ArrowBack/Send/PlayArrow/Settings/Lock/Share/AccountCircle/MailOutline/CheckCircle 等基础图标都在）。
    // 之前依赖的是体积巨大的 material-icons-extended，而项目实际只用到这几个基础图标。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // 内网穿透：Pinggy 本质是免注册的 SSH 反向隧道（ssh -p 443 -R0:localhost:端口 a.pinggy.io），
    // 用纯 Java 的 sshj 实现即可 —— 不用打包任何原生二进制，
    // 从而绕开 Android 10+ 禁止执行应用可写目录文件（W^X）的限制。
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle：sshj 的传递依赖，但 Android 上需要「显式 + 完整版」才能覆盖系统那份
    // 残缺的 BC provider（否则握手时会抛 no such algorithm: X25519 for provider BC）。
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80.2")
}
