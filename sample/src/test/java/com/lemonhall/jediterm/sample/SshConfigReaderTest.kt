package com.lemonhall.jediterm.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SshConfigReaderTest {
  @Test
  fun `uses defaults when env missing`() {
    val tempDir = createTempDirectory("jediterm-sample-").toFile()
    try {
      val config = SshConfigReader.readFromFilesDir(tempDir)
      assertEquals("192.168.50.149", config.host)
      assertEquals(22, config.port)
      assertEquals("lemonhall", config.user)
      assertNull(config.password)
      assertNull(config.privateKeyPath)
      assertNull(config.passphrase)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `parses env and resolves private key path`() {
    val tempDir = createTempDirectory("jediterm-sample-").toFile()
    try {
      File(tempDir, "id_rsa").writeText("dummy")
      File(tempDir, ".env").writeText(
        """
        # comment
        SSH_HOST=example.com
        SSH_PORT=22022
        SSH_USER=testuser
        SSH_PASSWORD=
        SSH_PRIVATE_KEY_PATH=id_rsa
        SSH_PRIVATE_KEY_PASSPHRASE=pass
        """.trimIndent(),
      )

      val config = SshConfigReader.readFromFilesDir(tempDir)
      assertEquals("example.com", config.host)
      assertEquals(22022, config.port)
      assertEquals("testuser", config.user)
      assertNull(config.password)
      assertEquals(File(tempDir, "id_rsa").absolutePath, config.privateKeyPath)
      assertEquals("pass", config.passphrase)
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
