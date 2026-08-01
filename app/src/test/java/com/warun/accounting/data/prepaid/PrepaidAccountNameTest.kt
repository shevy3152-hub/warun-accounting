package com.warun.accounting.data.prepaid

import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidAccountNameTest {
    @Test
    fun displayNameNormalizesWidthAndWhitespace() {
        assertEquals(
            "Test Pay",
            normalizePrepaidAccountName("  Ｔｅｓｔ　Ｐａｙ  ")
        )
    }

    @Test
    fun duplicateKeyIgnoresWidthCaseAndRepeatedWhitespace() {
        assertEquals(
            prepaidAccountNameDuplicateKey("ＴＥＳＴ　　ＰＡＹ"),
            prepaidAccountNameDuplicateKey("test pay")
        )
    }
}
