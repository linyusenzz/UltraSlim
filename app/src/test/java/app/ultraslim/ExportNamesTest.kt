package app.ultraslim

import app.ultraslim.util.ExportNames
import org.junit.Assert.*
import org.junit.Test

class ExportNamesTest {
    @Test fun namesAreUsableBeforeProviderCreation() {
        assertEquals("a_b.heic", ExportNames.fromSource("a:b.jpg", "HEIC"))
        assertEquals("a_______b.avif", ExportNames.fromSource("a:*?\"<>|b.jpg", "avif"))
        assertEquals("Photo.jpg", ExportNames.fromSource("...", "jpg"))
        assertEquals("123456.heic", ExportNames.fromSource("123456.jpg", "heic"))
        assertEquals("Screenshot.heic", ExportNames.fromSource("Screenshot.jpg", "heic"))
    }

    @Test fun byteBudgetAppliesBeforeAndAfterCollisionWithWholeUnicodeCodePoints() {
        for (source in listOf("a".repeat(251) + ".jpg", "图".repeat(85) + ".jpg", "😀".repeat(80) + ".jpg")) {
            val name = ExportNames.fromSource(source, "heic")
            val collision = ExportNames.available(name, setOf(name.uppercase()))
            assertTrue(name.toByteArray().size <= 255)
            assertTrue(collision.toByteArray().size <= 255)
            assertTrue(collision.endsWith(" (1).heic"))
            assertEquals(name, name.toByteArray().toString(Charsets.UTF_8))
            assertEquals(collision, collision.toByteArray().toString(Charsets.UTF_8))
        }
    }

    @Test fun collisionsIncludeCaseAndUnicodeEquivalence() {
        assertEquals("é (2).heic", ExportNames.available("é.heic", setOf("e\u0301.HEIC", "É (1).heic")))
        assertEquals("a_b (1).heic", ExportNames.available(ExportNames.fromSource("a:b.jpg", "heic"), setOf("a_b.heic")))
    }

}
