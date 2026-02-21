## Codex 指令：创建 jediterm-android 库项目

### 背景

在 `E:\development\jediterm-android` 目录下创建一个 Android Library 项目。这个库的目标是将 JetBrains 的 JediTerm 终端仿真引擎（https://github.com/JetBrains/jediterm ，选择 Apache 2.0 许可）移植到 Android 平台，用 Jetpack Compose 替换原有的 Swing UI 层。

最终产物是一个 `.aar`，供主项目 `kotlinagentapp` 通过 composite build 或直接依赖使用。

### 参考主项目配置（必须对齐）

从 `E:\development\kotlinagentapp` 获取以下配置参数：
- AGP: `8.7.3`
- Kotlin: `2.0.21`
- Compose BOM: `2026.02.00`
- Compose Compiler 插件: `org.jetbrains.kotlin.plugin.compose` (版本跟随 Kotlin `2.0.21`)
- Gradle Wrapper: `8.9`
- compileSdk: `35`
- minSdk: `24`
- targetSdk: `35`
- JVM target: `1.8`（配合 `coreLibraryDesugaring`）
- desugar_jdk_libs: `2.0.4`
- Java sourceCompatibility / targetCompatibility: `JavaVersion.VERSION_1_8`

### 项目结构

```
E:\development\jediterm-android/
├── build.gradle.kts                    # 根项目
├── settings.gradle.kts
├── gradle.properties                   # 从 kotlinagentapp 复制关键配置
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties   # Gradle 8.9
│   └── libs.versions.toml             # Version Catalog
├── .gitignore
├── LICENSE                             # Apache 2.0
├── README.md
├── lib/                                # Android Library 模块
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/                   # 保持 java/ 目录名（兼容 JediTerm 原始 Java 源码）
│       │       └── com/jediterm/
│       │           ├── core/           # ← 从 JediTerm 仓库 core/src/com/jediterm/core/ 原封不动复制
│       │           └── terminal/       # ← 从 JediTerm 仓库 core/src/com/jediterm/terminal/ 原封不动复制
│       │       └── com/lemonhall/jediterm/android/  # ← 你写的 Android 适配层
│       │           ├── ComposeTerminalView.kt
│       │           ├── ComposeTerminalDisplay.kt
│       │           ├── AndroidExecutorServiceManager.kt
│       │           └── TerminalSessionManager.kt
│       └── test/                       # ← 从 JediTerm 仓库 core/tests/ 复制测试
│           ├── java/
│           │   └── com/jediterm/...
│           └── resources/
│               └── testData/...
└── sample/                             # 可选的 demo app（最小化）
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/lemonhall/jediterm/sample/
            └── MainActivity.kt
```

### 第一步：Gradle 配置

1. 运行 `gradle wrapper --gradle-version 8.9` 或手动创建 wrapper 文件，确保 `gradle-wrapper.properties` 指向 `gradle-8.9-all.zip`。

2. `gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
composeBom = "2026.02.00"
coreKtx = "1.16.0"
desugarJdkLibs = "2.0.4"
junit = "4.13.2"
slf4jApi = "2.0.9"
slf4jAndroid = "2.0.9"
jetbrainsAnnotations = "24.1.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
android-desugar-jdk-libs = { group = "com.android.tools", name = "desugar_jdk_libs", version.ref = "desugarJdkLibs" }
slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "slf4jApi" }
slf4j-android = { group = "org.slf4j", name = "slf4j-android", version.ref = "slf4jAndroid" }
jetbrains-annotations = { group = "org.jetbrains", name = "annotations", version.ref = "jetbrainsAnnotations" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

3. `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "jediterm-android"
include(":lib")
include(":sample")
```

4. 根 `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

5. `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

6. `lib/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lemonhall.jediterm.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures { compose = true }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.slf4j.api)
    compileOnly(libs.jetbrains.annotations)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
```

### 第二步：复制 JediTerm core 源码

从 https://github.com/JetBrains/jediterm (master 分支) 复制以下目录：

- `core/src/com/jediterm/core/` → `lib/src/main/java/com/jediterm/core/`
- `core/src/com/jediterm/terminal/` → `lib/src/main/java/com/jediterm/terminal/`
- `core/tests/src/` → `lib/src/test/java/`
- `core/tests/resources/` → `lib/src/test/resources/`

### 第三步：修复 Android 不兼容的代码

1. 在 `TtyConnector.java` 中，删除或注释掉两个引用 `java.awt.Dimension` 的 `@Deprecated` 方法（`resize(Dimension)` 和 `waitFor()`），只保留 `resize(TermSize)` 方法。同时删除 `import java.awt.Dimension;`。

2. 在 `ProcessTtyConnector.java` 中，如果有 `java.awt.Dimension` 引用，同样处理。

3. 检查 `core/src/com/jediterm/core/Platform.kt`，确认 `System.getProperty("os.name")` 在 Android 上能正常工作（Android 返回 "Linux"，应该没问题，但确认一下逻辑）。

4. 在 `JediTermDebouncerImpl.java` 中，确认 `javax.swing.Timer` 没有被引用。如果有，替换为 `java.util.Timer` 或 `Handler`。

### 第四步：创建 Android 适配层

在 `lib/src/main/java/com/lemonhall/jediterm/android/` 下创建以下文件：

#### `ComposeTerminalDisplay.kt`
实现 `com.jediterm.terminal.TerminalDisplay` 接口。这个接口的方法包括：
- `setCursor(x: Int, y: Int)`
- `setCursorShape(cursorShape: CursorShape?)`
- `beep()`
- `scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int)`
- `setCursorVisible(isCursorVisible: Boolean)`
- `getTerminalWidth(): Int`
- `getTerminalHeight(): Int`
- `getRowCount(): Int`

用 Compose `mutableStateOf` 来持有光标位置、可见性等状态，让 Compose 自动 recompose。

#### `AndroidExecutorServiceManager.kt`
实现 `com.jediterm.terminal.TerminalExecutorServiceManager` 接口。用 Android 的 `Handler(Looper.getMainLooper())` 来调度 UI 更新，用 `Executors.newSingleThreadExecutor()` 来处理后台任务。

#### `ComposeTerminalView.kt`
一个 `@Composable` 函数，核心逻辑：
- 用 `Canvas` 组件渲染终端文本（从 `TerminalTextBuffer` 读取行数据）
- 用 `drawText` 绘制每个字符，根据 `TextStyle` 设置前景色/背景色
- 绘制光标（方块/下划线/竖线，根据 `CursorShape`）
- 处理触摸事件（选择文本）
- 处理软键盘输入，通过 `TerminalOutputStream` 发送到 PTY
- 支持滚动手势浏览历史缓冲区

初始版本可以简化：先实现基本的文本渲染 + 光标显示 + 键盘输入，选择和滚动后续迭代。

#### `TerminalSessionManager.kt`
管理终端会话的生命周期：
- 创建 `JediTerminal` + `TerminalTextBuffer` + `JediEmulator` + `TerminalStarter`
- 提供 `startSession(ttyConnector: TtyConnector)` 方法
- 提供 `stopSession()` 方法
- 处理 resize 事件

### 第五步：创建 sample app（最小化）

`sample/build.gradle.kts` 配置一个最简单的 Android Application，依赖 `:lib` 模块。

`MainActivity.kt` 里放一个全屏的 `ComposeTerminalView`，连接一个 mock 的 `TtyConnector`（不需要真正的 PTY，用一个简单的 echo 回显即可），用于验证库能编译通过并基本渲染。

### 第六步：验证

确保以下命令能通过：
```
.\gradlew.bat :lib:assembleDebug
.\gradlew.bat :lib:testDebugUnitTest
.\gradlew.bat :sample:assembleDebug
```

### 注意事项

- 不要引入任何 `java.awt.*` 或 `javax.swing.*` 依赖
- JediTerm 原始源码是 Java + Kotlin 混合的，保持原样即可，不需要全部转成 Kotlin
- `slf4j-api` 是 JediTerm core 的日志依赖，Android 端用 `slf4j-android` 桥接
- 所有新写的代码用 Kotlin
- 包名约定：JediTerm 原始代码保持 `com.jediterm.*`，Android 适配层用 `com.lemonhall.jediterm.android.*`