// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFileAccessTest {
    @Test
    fun `an ordinary name is kept as it is`() {
        assertEquals("Week 3 handout.pdf", LocalFileAccess.displayName("Week 3 handout.pdf"))
    }

    @Test
    fun `characters a file system refuses become underscores`() {
        assertEquals("a_b_c_d_e_f_g_h_i.txt", LocalFileAccess.displayName("a/b\\c:d*e?f\"g<h>i.txt"))
    }

    @Test
    fun `a name cannot climb out of the folder`() {
        assertEquals(".._.._secret.txt", LocalFileAccess.displayName("../../secret.txt"))
    }

    @Test
    fun `control characters are dropped and an empty name becomes file`() {
        assertEquals("ab", LocalFileAccess.displayName("a\u0000b"))
        assertEquals("file", LocalFileAccess.displayName("   "))
    }
}
