## Codex 指令：Step 2.5 — 修复已知问题，确保真实 SSH 场景可用

### 背景

Step 2 的 Canvas 渲染已经跑通，256 色/true color/样式都正确。但在接入真实 SSH（连接 Mac 跑 vim/htop）之前，有几个问题必须修掉，否则实际使用时会出 bug。Android 编译很慢，请一次改对。

### 修改 1：CJK 双宽字符绘制（TerminalRenderSnapshot.kt + ComposeTerminalView.kt）

当前问题：双宽字符（中文、日文等）在 `TerminalRenderSnapshot` 里被当作普通单宽字符处理，绘制时只占一个 cell 宽度，导致后续字符全部错位。

修改 `TerminalRenderSnapshot.kt`：

在 `RenderCell` 数据类中增加一个字段标记该字符是否为双宽：

```kotlin
data class RenderCell(
    val char: Char,
    val fg: ComposeColor,
    val bg: ComposeColor,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val dim: Boolean,
    val hidden: Boolean,
    val isDoubleWidth: Boolean = false,  // 新增
)
```

在构建 `RenderCell` 时，判断字符宽度。JediTerm 的 `CharUtils` 里有 `isDoubleWidthCharacter` 或类似方法，如果没有，可以用 Unicode 的 East Asian Width 属性判断：

```kotlin
private fun isDoubleWidth(c: Char): Boolean {
    // CJK Unified Ideographs, CJK Compatibility Ideographs, Fullwidth Forms, etc.
    val code = c.code
    return (code in 0x1100..0x115F) ||   // Hangul Jamo
           (code in 0x2E80..0x303E) ||   // CJK Radicals
           (code in 0x3040..0x33BF) ||   // Hiragana, Katakana, CJK
           (code in 0x3400..0x4DBF) ||   // CJK Unified Ext A
           (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
           (code in 0xA000..0xA4CF) ||   // Yi
           (code in 0xAC00..0xD7AF) ||   // Hangul Syllables
           (code in 0xF900..0xFAFF) ||   // CJK Compatibility Ideographs
           (code in 0xFE30..0xFE4F) ||   // CJK Compatibility Forms
           (code in 0xFF01..0xFF60) ||   // Fullwidth Forms
           (code in 0xFFE0..0xFFE6)      // Fullwidth Signs
}
```

在填充 cells 数组时，双宽字符占两列：第一列放实际字符（`isDoubleWidth = true`），第二列放一个占位 cell（`char = '\u0000'` 或 `CharUtils.DWC`），渲染时跳过占位 cell。

注意：当前代码里已经有 `if (c == CharUtils.DWC) return@consume` 的逻辑来跳过 DWC 占位符，这是对的。但问题是双宽字符本身的那个 cell 没有标记为双宽，绘制时宽度不对。

修改 `ComposeTerminalView.kt` 的 Canvas 绘制逻辑：

```kotlin
for (col in 0 until snapshot.columns) {
    val cell = row[col]
    if (cell.char == CharUtils.DWC || cell.char == '\u0000') continue  // 跳过占位符

    val cellWidth = if (cell.isDoubleWidth) charWidthPx * 2 else charWidthPx
    val x = col * charWidthPx

    // 画背景时用 cellWidth
    drawRect(color = cell.bg, topLeft = Offset(x, y), size = Size(cellWidth, charHeightPx))

    // 画字符时也用 cellWidth 区域
    if (!cell.hidden && cell.char != ' ' && cell.char != '\u0000') {
        // drawText 的位置不变，但如果需要居中可以微调
        drawText(measuredText, topLeft = Offset(x, y))
    }
}
```

### 修改 2：焦点恢复（ComposeTerminalView.kt）

当前问题：`LaunchedEffect(Unit)` 只在首次 composition 时请求焦点。如果用户点了屏幕其他地方（比如系统通知栏），焦点丢失后键盘输入就不响应了，而且没有任何方式恢复焦点。

修改：给终端区域的 `Box` 加上点击恢复焦点的逻辑，并加上 `focusable()` 修饰符：

```kotlin
Box(
    modifier = modifier
        .fillMaxSize()
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures {
                focusRequester.requestFocus()
            }
        }
        .onPreviewKeyEvent { /* 现有的按键处理逻辑 */ }
) {
    // ...
}
```

同时把 `LaunchedEffect(Unit)` 改成在 session 启动后请求焦点：

```kotlin
LaunchedEffect(session, ttyConnector) {
    session.startSession(ttyConnector)
    focusRequester.requestFocus()
}
```

需要额外 import：
```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
```

### 修改 3：软键盘 composing 防重复（ComposeTerminalView.kt）

当前问题：中文输入法在 composing 阶段会频繁触发 `onValueChange`，每次触发都会把 composing 中的文本发送到终端，导致重复输入。

修改：使用 `TextFieldValue` 替代 `String`，通过 `composition` 属性判断是否在 composing 状态，只在 composing 结束后才发送：

```kotlin
import androidx.compose.ui.text.input.TextFieldValue

var inputValue by remember { mutableStateOf(TextFieldValue("")) }

BasicTextField(
    value = inputValue,
    onValueChange = { newValue ->
        // 只在 composing 结束时（composition == null）才发送
        if (newValue.composition == null && newValue.text.isNotEmpty()) {
            session.sendString(newValue.text)
            inputValue = TextFieldValue("")
        } else {
            inputValue = newValue
        }
    },
    // ... 其余不变
)
```

### 修改 4：sendBytes 方法（TerminalSessionManager.kt）

当前问题：`TerminalSessionManager` 只有 `sendString(text: String)` 方法，没有 `sendBytes(bytes: ByteArray)` 方法。`onPreviewKeyEvent` 里的按键映射通过 `terminal.getCodeForKey()` 拿到的是 `ByteArray`，但当前代码是把它转成 String 再调 `sendString`。对于二进制转义序列，这个转换可能出问题（编码问题）。

在 `TerminalSessionManager` 中增加：

```kotlin
fun sendBytes(bytes: ByteArray) {
    terminalStarter?.sendBytes(bytes, true)
}
```

然后在 `ComposeTerminalView.kt` 的 `onPreviewKeyEvent` 里，把 `session.sendString(String(code))` 改为 `session.sendBytes(code)`。

检查 `TerminalStarter` 是否有 `sendBytes(ByteArray, Boolean)` 方法。如果没有，看看有没有 `sendBytes(ByteArray)` 或者通过 `TtyConnector.write(ByteArray)` 直接写。根据 JediTerm 源码选择正确的调用方式。

### 修改 5：滚动边界检查（ComposeTerminalView.kt）

当前问题：需要确认滚动逻辑的边界条件。当 `scrollOffset` 为 0 时应该显示最新内容（底部），向上滚动增加 offset 显示历史。检查以下边界：

- `scrollOffset` 不能小于 0
- `scrollOffset` 不能大于 `historyBuffer.lineCount`
- 当有新输出到达且 `scrollOffset == 0` 时，自动显示最新内容（不要锁定在历史位置）
- 当 `scrollOffset > 0`（用户在看历史）且有新输出到达时，保持当前位置不动

检查当前代码是否正确处理了这些情况，如果没有，修复之。

### 修改 6：MockConnectors 增加 CJK 测试（MockConnectors.kt）

在 `mockColorTestConnector()` 的输出中增加一段 CJK 双宽字符测试：

```
CJK double-width:
你好世界 Hello
中文混合English测试
├──目录1
│  ├──文件.txt
│  └──子目录
└──目录2
```

这样在 sample app 里就能直接验证双宽字符是否对齐。

### 修改 7：确保 TerminalRenderSnapshot 的线程安全

当前 `buildSnapshot()` 方法里调用了 `textBuffer.lock()` 和 `textBuffer.unlock()`，这是对的。但检查一下：

- `lock()` 和 `unlock()` 是否在 try/finally 里（确保异常时也能 unlock）
- Canvas 绘制时读取 snapshot 对象是否安全（snapshot 是不可变的快照，应该没问题，但确认一下 `cells` 数组没有被外部修改的可能）

### 验证标准

修改完成后，确保：

1. `.\gradlew.bat :lib:assembleDebug` 编译通过
2. `.\gradlew.bat :sample:assembleDebug` 编译通过
3. `.\gradlew.bat :lib:testDebugUnitTest` 测试通过
4. sample app 运行后：
   - 颜色测试正常显示（跟之前一样）
   - CJK 字符测试中，中英文混排对齐正确，不错位
   - 点击屏幕任意位置后键盘输入仍然有效（焦点恢复）
   - 滚动到历史内容后，能正确滚回最新内容

### 注意事项

- 不要改动 `com.jediterm.*` 包下的任何 JediTerm core 源码（除非是之前已经做过的 AWT 兼容修复）
- 所有修改限定在 `com.lemonhall.jediterm.android.*` 和 `com.lemonhall.jediterm.sample.*` 包内
- 编译必须通过，不能有未解析的引用或类型错误
- 如果某个修改涉及到 JediTerm core 的 API 不确定，先查看源码确认方法签名，不要猜