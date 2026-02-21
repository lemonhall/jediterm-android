## 问题根因

SSH 连接建立后，`ComposeTerminalView` 的 `onSizeChanged` 回调在 Compose 布局阶段立即触发，此时 `TerminalStarter` 的 emulator 循环尚未完全就绪。`session.resize()` 会让 jediterm 内部向 SSH channel 写入终端 resize 相关的控制序列，而此刻 JSch 的 channel IO 管道还没准备好接收这类数据，导致 SSH channel 被服务端关闭（`read returned -1 EOF`）。

表现为：连接后能正常显示 prompt（read 正常），但第一次键盘输入后 channel 立即断开，后续所有输入无效。

## 修复方式

在 `ComposeTerminalView` 中增加 `sessionStarted` 标记，`LaunchedEffect` 里 `startSession()` 完成后延迟 500ms 再置为 `true`。`onSizeChanged` 回调中只有 `sessionStarted == true` 时才执行 `session.resize()` 和 `onResize` 回调，避免了 emulator 未就绪时的竞态。

## 排查过程

1. echo connector 测试通过，确认输入链路（`TerminalInputView` → `commitText` → `session.sendString()` → `TerminalStarter.sendString()` → `ttyConnector.write()`）完整可用
2. 加诊断代码在主线程直接调用 `ttyConnector.write()`，触发了 Android 的 `NetworkOnMainThreadException`，意外把 SSH channel 搞崩——这是个误导项，但也证实了 JSch channel 一旦异常就会永久关闭
3. 在 `write()` 前后加状态日志，发现第一个字符写成功后 280ms channel 就变成 `isClosed=true`，且没有 read 回显
4. 在 `connect()` 后加 monitor 线程持续监控 channel 状态，发现不打字时 channel 一直活着，一打字就死
5. 怀疑 `onSizeChanged` 触发的 `session.resize()` 存在竞态，加 `sessionStarted` 守卫后问题消失，`ls` 命令正常执行