// Baseline Profile 采集与 Macrobenchmark 启动基准（com.android.test 测试模块）。
// 只在本机连设备时运行：生成规则 ./gradlew :app:generateReleaseBaselineProfile，
// 量测 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest。
// 不参与 app 的运行时依赖，也不影响日常构建产物。
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.fangyi.classpp.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // BaselineProfileRule 官方支持 Android 13+，或 root 的 Android P+；生成时请用 API 33+
        // 设备，低于该条件会直接失败。28 只是让测试 APK 能装到更低版本的设备上。
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 被测应用与测试同进程（self-instrumenting）：androidx.benchmark 的标准接线，
    // 由 com.android.test 模块直接产出测试 APK
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
}
