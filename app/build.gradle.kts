plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fangyi.classpp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fangyi.classpp"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.6-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
        // Baseline Profile 插件的采集变体。AGP 9 的 optimization DSL 优先于 isMinifyEnabled：
        // 插件默认只把 nonMinifiedRelease 的 isMinifyEnabled 置 false，R8 仍会执行并把规则
        // 记录成混淆名，插件后续的规则重写无法把它映射回 release。这里预创建该变体并显式
        // 关闭 optimization；插件检测到已存在后会跳过自己的默认配置，因此 profileable /
        // debuggable / signing 等属性按插件同一套值一并补齐。
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            optimization {
                enable = false
            }
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            isProfileable = true
            enableAndroidTestCoverage = false
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Baseline Profile 消费端：生成结果（本地连设备跑 :baselineprofile 的采集测试）写回
// app/src/release/generated/baselineProfiles 并随源码提交，日常构建只合并已提交的规则，
// 不再触发采集。
baselineProfile {
    saveInSrc = true
    // 只保留 App 自身规则：依赖库的 profile 由 AGP 从 AAR 合并，落盘里再带一份会让
    // 生成文件膨胀数倍且难以审阅
    filter {
        include("com.fangyi.classpp.**")
    }
    // 只生成 baseline profile；startup profile 按上面的理由不生成，关掉对应告警
    warnings {
        noStartupProfileRulesGenerated = false
    }
}

// 组合性能诊断：默认不生成报告（零额外构建开销）。需要时用
// ./gradlew :app:compileDebugKotlin -PcomposeCompilerReports 输出到
// build/compose_compiler（*-composables.txt / *-classes.txt / *-module.json），
// 用于核对 SettingsHomePage / WeekPage / DayDateContent / CourseCard 等的 skippable 与稳定性
composeCompiler {
    if (providers.gradleProperty("composeCompilerReports").isPresent) {
        reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
        metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.haze.core)
    implementation(libs.haze.blur)
    implementation(libs.kyant.shapes)
    implementation(libs.kyant.backdrop)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}