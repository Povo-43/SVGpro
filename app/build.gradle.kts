plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.svgpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.svgpro.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.3.0"
    }

    // APKファイル名をSVGproに統一（SVGpro-debug.apk / SVGpro-release.apk）
    applicationVariants.all {
        val variantName = this.name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "SVGpro-$variantName.apk"
        }
    }

    signingConfigs {
        create("release") {
            val props = rootProject.properties
            storeFile = rootProject.file(props["android.signing.release.storeFile"] as? String ?: "app/svgpro-release.keystore")
            // パスワードは環境変数から読み込む（リポジトリに平文を置かない）。
            // CI は GitHub Secrets から渡す。ローカルで release ビルドする場合は事前に export してください。
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = props["android.signing.release.keyAlias"] as? String ?: "svgpro-release"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 固定リリース鍵で署名（debug鍵は使わない。鍵を固定しないと実機への更新インストールで署名競合が発生する）
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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
    }
    packaging {
        resources.excludes += "META-INF/*.md"
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
