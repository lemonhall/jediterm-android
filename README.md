# jediterm-android

把 JetBrains 的 [JediTerm](https://github.com/JetBrains/jediterm) 终端仿真引擎移植到 Android（UI 使用 Jetpack Compose），用于通过 SSH 连接远程服务器并运行 TUI 程序（vim、htop、tmux 等）。

本仓库产出一个 Android Library（`.aar`），供主项目 [kotlinagentapp](https://github.com/lemonhall/kotlinagentapp) 通过 composite build 或直接依赖使用。主项目已使用 JSch 进行 SSH 连接，本库提供终端仿真和 Compose 渲染层。

## 架构

```
Android 设备                              远程 Mac
┌──────────────────────────┐        ┌──────────────────┐
│ ComposeTerminalView      │        │                  │
│   ↕ 渲染/输入            │        │  sshd → PTY      │
│ JediTerm core            │  SSH   │   ↕              │
│   ↕ 终端仿真             │◄──────►│  vim/htop/tmux   │
│ JSch (ChannelShell)      │        │                  │
└──────────────────────────┘        └──────────────────┘
```

## Modules

- `:lib` Android Library（JediTerm core 源码 + Compose 终端组件 + TerminalDisplay 适配）
- `:sample` 最小 demo app（编译/渲染验证用）

## 渲染方案

UI 层使用 Compose `Canvas` 逐字符绘制终端内容，而非 `BasicText` / `AnnotatedString`。原因：

- 终端需要精确的等宽字符定位（尤其是 CJK 双宽字符）
- 需要逐字符控制前景色/背景色（256 色 + true color）
- vim 等 TUI 程序快速滚动时，Canvas 直绘性能优于频繁重建 AnnotatedString

**文本选择**采用自实现方案（不依赖系统原生文本选择），与 Termux、iTerm2、Windows Terminal 等主流终端一致：

- 长按/拖拽手势 → 计算触摸坐标对应的行列位置 → 维护选区状态 → 绘制高亮背景 → 从 `TerminalTextBuffer` 读取选中文本 → 写入剪贴板
- 支持行选择和矩形选择
- 双击选词、三击选行
- 选择操作不干扰终端的鼠标事件上报（vim 中鼠标点击用于定位光标，不触发文本选择）
- JediTerm core 已内置 `TerminalSelection` 类，适配层基于此实现

## Upstream

JediTerm 源码取自 JetBrains/jediterm：`985e58caa97899e2d1b933aecd326421c65cd729`（`core` 模块），许可证选择 Apache 2.0。

## Build

```powershell
.\gradlew.bat :lib:assembleDebug
.\gradlew.bat :lib:testDebugUnitTest
.\gradlew.bat :sample:assembleDebug
```

## License

本项目 Android 适配层代码采用 Apache 2.0 许可。JediTerm core 代码遵循其原始的 Apache 2.0 许可。