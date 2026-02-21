## Codex 指令：第二步 — 让终端真正可用

### 目标

把 `ComposeTerminalView` 从"能编译的骨架"升级到"能跑 vim 的终端"。完成后应该能：
- 正确渲染带颜色/样式的终端输出（256 色 + true color）
- 显示光标（支持 block/underline/bar 三种形状）
- 捕获特殊按键（方向键、Esc、Ctrl 组合键、Tab、F1-F12）
- 根据屏幕尺寸和字体自动计算终端行列数
- 支持滚动浏览历史输出

### 1. 重写 ComposeTerminalView.kt — 用 Canvas 替代 BasicText

当前的 `BasicText` 方案无法渲染逐字符的颜色和样式。改用 Compose `Canvas` 逐字符绘制。

核心思路：

```kotlin
@Composable
fun ComposeTerminalView(
    ttyConnector: TtyConnector,
    modifier: Modifier = Modifier,
) {
    // 1. 测量单个等宽字符的宽高（用 TextMeasurer）
    // 2. 根据 Canvas 尺寸 / 字符尺寸 计算 columns 和 rows
    // 3. 用计算出的 columns/rows 初始化 TerminalSessionManager
    // 4. Canvas 里逐行逐字符绘制：
    //    - 从 TerminalTextBuffer 获取每一行（TerminalLine）
    //    - 遍历每行的 entries（CharEntry），每个 entry 有 style（TextStyle）
    //    - style 里有 foreground/background color，bold/italic/underline 等
    //    - 用 drawRect 画背景色，用 drawText 画前景字符
    // 5. 绘制光标（根据 ComposeTerminalDisplay 的 cursorX/cursorY/cursorShape）
}
```

关键实现细节：

**颜色转换** — JediTerm 的 `com.jediterm.core.Color` 需要转成 Compose 的 `androidx.compose.ui.graphics.Color`：

```kotlin
fun com.jediterm.core.Color.toComposeColor(): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(red, green, blue)
}
```

**从 TerminalTextBuffer 读取样式化内容** — 不要用 `getLine(row).text`（丢失样式），要用：

```kotlin
val line = terminalTextBuffer.getLine(row)
line.forEachEntry { entry ->
    val style: TextStyle = entry.style
    val text: String = entry.text.toString()
    val fg = styleState.getForeground(style.foregroundIndex)
    val bg = styleState.getBackground(style.backgroundIndex)
    // 用 fg/bg 绘制 text
}
```

注意：需要查看 `TerminalLine` 的实际 API，上面是伪代码，具体方法名以 JediTerm 源码为准。核心是遍历行内的每个带样式的文本片段。

**字符宽度处理** — CJK 字符占两列宽，JediTerm 的 `CharUtils.getCharWidth()` 或 `WcWidth` 可以判断。绘制时双宽字符要占两个 cell 的位置。

**光标绘制**：

```kotlin
when (display.cursorShape) {
    CursorShape.BLINK_BLOCK, CursorShape.STEADY_BLOCK -> 
        drawRect(color, topLeft, Size(charWidth, charHeight))  // 实心方块
    CursorShape.BLINK_UNDERLINE, CursorShape.STEADY_UNDERLINE -> 
        drawRect(color, Offset(x, y + charHeight - 2f), Size(charWidth, 2f))  // 下划线
    CursorShape.BLINK_VERTICAL_BAR, CursorShape.STEADY_VERTICAL_BAR -> 
        drawRect(color, topLeft, Size(2f, charHeight))  // 竖线
}
```

### 2. 键盘输入 — 用 Modifier.onKeyEvent 替代 BasicTextField

`BasicTextField` 的问题是它走 IME 通道，特殊键会被系统吃掉。改用 `Modifier.onKeyEvent` 捕获硬键盘事件，同时保留一个隐藏的 `BasicTextField` 处理软键盘的可打印字符输入。

```kotlin
Modifier.onKeyEvent { keyEvent ->
    if (keyEvent.type == KeyEventType.KeyDown) {
        val bytes = mapKeyToTerminalBytes(keyEvent)
        if (bytes != null) {
            session.sendBytes(bytes)
            true
        } else false
    } else false
}
```

**按键映射函数 `mapKeyToTerminalBytes`**：

| Android KeyEvent | 终端转义序列 |
|---|---|
| KEYCODE_DPAD_UP | `\u001b[A` |
| KEYCODE_DPAD_DOWN | `\u001b[B` |
| KEYCODE_DPAD_RIGHT | `\u001b[C` |
| KEYCODE_DPAD_LEFT | `\u001b[D` |
| KEYCODE_ESCAPE / KEYCODE_BACK | `\u001b` |
| KEYCODE_TAB | `\t` |
| KEYCODE_ENTER | `\r` |
| KEYCODE_DEL (退格) | `\u007f` |
| KEYCODE_FORWARD_DEL | `\u001b[3~` |
| KEYCODE_MOVE_HOME | `\u001b[H` |
| KEYCODE_MOVE_END | `\u001b[F` |
| KEYCODE_PAGE_UP | `\u001b[5~` |
| KEYCODE_PAGE_DOWN | `\u001b[6~` |
| KEYCODE_F1 - F12 | `\u001bOP` - `\u001b[24~` |
| Ctrl + 字母 | 字母的 ASCII 值 AND 0x1F（如 Ctrl+C = 0x03） |

注意：JediTerm 自带 `TerminalKeyEncoder`，优先使用它来做按键到转义序列的映射，不要自己硬编码。查看 `com.jediterm.terminal.TerminalKeyEncoder` 的 API。

同时在 `TerminalSessionManager` 里加一个 `sendBytes(bytes: ByteArray)` 方法：

```kotlin
fun sendBytes(bytes: ByteArray) {
    terminalStarter?.sendBytes(bytes, true)
}
```

### 3. 动态 resize

当 Compose 布局尺寸变化时（屏幕旋转、软键盘弹出/收起），需要重新计算终端行列数并通知 JediTerm：

```kotlin
Canvas(
    modifier = modifier
        .fillMaxSize()
        .onSizeChanged { size ->
            val newCols = (size.width / charWidthPx).toInt()
            val newRows = (size.height / charHeightPx).toInt()
            if (newCols != currentCols || newRows != currentRows) {
                currentCols = newCols
                currentRows = newRows
                session.resize(newCols, newRows)
            }
        }
) { /* 绘制逻辑 */ }
```

这会触发 JediTerm 内部的 resize → SSH channel 的 `setPtySize` → 远端 SIGWINCH → vim 重绘。

### 4. 滚动支持

用 `Modifier.verticalScroll` 或 `Modifier.pointerInput` 处理触摸滚动手势。滚动时改变一个 `scrollOffset` 状态，渲染时从 `terminalTextBuffer.getLine(row - scrollOffset)` 读取历史行。

JediTerm 的 `TerminalTextBuffer` 自带 scrollback buffer（`getHistoryBuffer()`），历史行数可以通过 `historyBuffer.lineCount` 获取。

初始版本可以简化：只在非 alternate screen 模式下支持滚动（vim 用 alternate screen，不需要外部滚动）。

### 5. 更新 sample 的 MockEchoConnector

当前的 mock connector 只能 echo，无法验证颜色渲染。加一个选项让它输出 ANSI 颜色测试序列：

```kotlin
fun mockColorTestConnector(): TtyConnector {
    // 输出 256 色测试条 + 一些带样式的文本
    // \u001b[31m 红色 \u001b[32m 绿色 ... \u001b[0m 重置
    // \u001b[1m 粗体 \u001b[4m 下划线 等
}
```

### 6. 验证标准

完成后，以下场景应该能正常工作：

1. `.\gradlew.bat :lib:assembleDebug` 和 `.\gradlew.bat :sample:assembleDebug` 编译通过
2. sample app 运行后能看到带颜色的终端输出（mock connector 的颜色测试）
3. 光标可见且位置正确
4. 旋转屏幕后终端尺寸自动调整
5. 能输入可打印字符和特殊键（至少方向键、Esc、Enter、Backspace、Tab、Ctrl+C）

### 注意事项

- 所有新代码用 Kotlin
- 不要引入 `java.awt.*` 或 `javax.swing.*`
- Canvas 绘制文本时使用 `drawText`（需要 `TextMeasurer`），不要用 `drawIntoCanvas` + Android 原生 `Canvas.drawText`，保持纯 Compose 实现
- 性能考虑：只重绘 damage 区域而不是整个屏幕是理想的，但初始版本可以每次全量重绘，后续优化
- 线程安全：`TerminalTextBuffer` 的读取必须在 `lock()/unlock()` 之间进行