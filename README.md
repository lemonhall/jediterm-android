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