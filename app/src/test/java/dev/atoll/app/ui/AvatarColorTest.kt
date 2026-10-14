package dev.atoll.app.ui

import androidx.compose.ui.graphics.Color
import dev.atoll.app.ui.theme.AtollAvatarTints
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected indexes come from docs/design/tools/kit.py, the same algorithm the web client uses. */
class AvatarColorTest {
    private val palette = listOf(0xFF5477AD, 0xFF3F8C80, 0xFF6E8243, 0xFFA87548, 0xFFB0605F, 0xFF7F64A8, 0xFF44835F, 0xFF96628C)

    @Test fun matchesTheDesignKit() {
        mapOf("builder" to 0, "reviewer" to 2, "tester" to 6, "agent:claude:1790181618736" to 2, "human:root:1" to 5, "测试" to 7)
            .forEach { (id, index) -> assertEquals(id, Color(palette[index]), AtollAvatarTints.of(id).background) }
    }

    @Test fun contentIsWhite() = assertEquals(Color.White, AtollAvatarTints.of("builder").content)
}
