plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "tech.illusion.spacewhiteboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.illusion.spacewhiteboard"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // ui-tooling 只在 Debug 构建里需要（Compose 预览/布局审查工具），它会把
    // androidx.compose.material:material 拉进依赖图；配置块下方 exclude 列表覆盖不到它
    // （只排除了 ui / ui-graphics / ui-text / foundation 四个模块，material 不在其中），
    // implementation 会让它连同这条 material 依赖一起出现在 releaseRuntimeClasspath 上,
    // 违反本项目"零 Material"的硬约束——即使源码里一行 Material import 都没有,产物里也有。
    // debugImplementation 把它限制在 Debug 变体,release 变体的依赖图里就不会再出现。
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
