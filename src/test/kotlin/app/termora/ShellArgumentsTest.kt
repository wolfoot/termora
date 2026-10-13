package app.termora

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellArgumentsTest {
    @Test
    fun shellMetacharactersRemainOneLiteralArgument() {
        if (!java.io.File("/bin/sh").exists()) return
        for (value in listOf("file with spaces", "apostrophe's file", "x; printf INJECTED", "\$(printf INJECTED)",
            "`printf INJECTED`", "line\nbreak", "-rf", "中文 * ? [a]", "")) {
            val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${ShellArguments.quote(value)}").start()
            val actual = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor())
            assertEquals(value, actual)
        }
        assertFailsWith<IllegalArgumentException> { ShellArguments.quote("invalid\u0000path") }
    }
}
