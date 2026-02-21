package com.lemonhall.jediterm.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeInputDispatchTest {
  @Test
  fun `empty value sends nothing and clears`() {
    val result = dispatchImeText("")
    assertNull(result.toSend)
    assertEquals("", result.nextValue)
  }

  @Test
  fun `non-empty value sends all and clears`() {
    val result = dispatchImeText("ls")
    assertEquals("ls", result.toSend)
    assertEquals("", result.nextValue)
  }
}

