# Baseline Profile 与启动基准

本目录的 `:baselineprofile` 模块用于两件事：

1. **基准测量**：用 Macrobenchmark 记录冷启动的 `timeToInitialDisplay`；
2. **生成规则**：用 `BaselineProfileRule` 采集 App 自身代码的 Baseline Profile，写回源码后由
   `androidx.baselineprofile` Gradle 插件合并进 release 构建。

App 依赖库自带的 profile 规则已经覆盖 Compose / activity / lifecycle 等大头；本模块补的是
App 自己的启动路径（`MainActivity` 首组合、三个仓库 bootstrap、JSON 解析、Haze/Coil 初始化）。

## 前置条件

- 一台已开启 USB 调试的设备，建议 Android 13+（API 33+）。`BaselineProfileRule` 也支持
  root 的 Android 9+，但项目约定代理不执行设备验证，以下命令均由本地执行。
- 多设备连接时用 `ANDROID_SERIAL=<serial>` 指定目标设备。
- 生成和测量都必须使用 `debuggable=false` 的变体；debug 构建不会加载 profile。
- **生成前在设备上保留一份真实数据**（至少一份课表 + 若干待办）。冷启动采集只覆盖首屏
  实际走过的代码；空数据设备会把空状态路径写进 profile，课表网格/待办列表的启动路径
  覆盖不到。

## 1. 先测基线（生成 profile 之前）

`StartupBenchmark` 有两组测试：

- `coldStartupNoCompilation`：`CompilationMode.None()`，不预编译，作为最坏情况基线；
- `coldStartupBaselineProfile`：`CompilationMode.Partial(BaselineProfileMode.Require)`，
  验证 profile 生效后的收益。

生成 profile 之前只跑第一组（第二组会因为缺少已嵌入的规则而失败，这是有意的）：

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fangyi.classpp.baselineprofile.StartupBenchmark#coldStartupNoCompilation
```

结果（JSON / 报告）在 `baselineprofile/build/outputs/` 下。记下 `timeToInitialDisplay` 的
中位数/P50，作为后续对比基线。

## 2. 生成 Baseline Profile

```bash
./gradlew :app:generateReleaseBaselineProfile
```

这条命令会：

1. 构建 `nonMinifiedRelease`（未混淆，规则保留原始类名）；
2. 在设备上运行 `BaselineProfileGenerator`（冷启动采集，默认最多 15 轮、连续 3 轮稳定后停止）；
3. 把规则写回 `app/src/release/generated/baselineProfiles/baseline-prof.txt`
   （`baselineProfile { saveInSrc = true }`，过滤为 `com.fangyi.classpp.**`）。

这里不生成 `startup-prof.txt`：插件的 startup profile 按设计不做 package 过滤，写回
`src` 时会把依赖库规则一起合回来，文件会膨胀数倍；baseline profile 已覆盖冷启动路径。

把生成的文件提交进仓库，release 构建立即消费它；不提交则每次构建仍只有依赖库规则。

> 输出目录由插件的 `saveInSrc` + `mergeIntoMain` 默认值决定：App 模块默认不合并到
> `src/main`，所以规则落在 `src/release/generated/baselineProfiles/`。这是有意保留的
> 默认行为，方便将 release 专属规则与其他变体区分开。
## 3. 确认规则进入 release

```bash
./gradlew :app:assembleRelease
# 合并后的 profile（R8 之前，仍是原始类名）
grep -c "com/fangyi" \
  app/build/intermediates/merged_art_profile/release/mergeReleaseArtProfile/baseline-prof.txt
```

生成前该值是 0；生成后应大于 0。注意不要用 R8 之后的
`app/build/intermediates/r8_art_profile/release/minifyReleaseWithR8/baseline-prof.txt`
做这个判断——那里是混淆后的类名。若生成后该值仍为 0，先执行一次
`./gradlew :app:clean :app:assembleRelease`，确认新生成的源目录被 AGP 纳入本次构建。

APK 中应继续存在：

```
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
```

## 4. 生成后复测

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fangyi.classpp.baselineprofile.StartupBenchmark
```

两组都跑：`coldStartupNoCompilation` 与 `coldStartupBaselineProfile`。后者会先通过
`ProfileInstaller` 安装 profile 再编译；目标 APK 缺少内嵌规则或 ProfileInstaller 会直接失败，
避免把「规则没生效」误读成收益。

> 这里没有额外接入 `ProfileVerifier`：`BaselineProfileMode.Require` 已在基准开始时完成
> 安装 + 编译，失败会直接抛错；ProfileVerifier 只额外报告安装/编译状态，不改变判定。

## 注意事项

- **只在 `debuggable=false` 的构建生效**：debug 构建不会加载 profile；`benchmarkRelease`
  虽然 `profileable=true`（允许 shell 采集），但 `debuggable=false`，所以可以正常使用 profile。
- **API 24–30 的安装时机**：`ProfileInstaller` 在首次启动后异步安装并触发编译，安装后的
  第一次启动不享受；Android 12+ 在安装时由系统处理。基准测量前先正常启动一次应用，
  或直接用 API 33+ 设备。
- **profile 会陈旧**：启动路径大改（新增首屏、换初始化链路）后重新生成。旧规则不会坏，
  只是收益衰减。
- **生成环境使用 `nonMinifiedRelease`**：插件用未混淆变体采集规则，再由 R8 重写进 release；
  不要把 `benchmarkRelease` 的产物当作规则来源。
- **AGP 9 的 `optimization` 覆盖 `isMinifyEnabled`**：插件默认只把 `nonMinifiedRelease`
  的 `isMinifyEnabled` 置 false，在 AGP 9 下 R8 仍会执行、规则会变成混淆名。因此
  `app/build.gradle.kts` 预创建了 `nonMinifiedRelease` 并显式 `optimization { enable = false }`；
  修改构建类型时不要删掉这段，否则 profile 采集会退化成混淆名。
- **不要在 CI 里跑**：该模块需要设备，且本项目约定不做模拟器/真机验证。日常构建只消费
  已提交的 profile 文件。
