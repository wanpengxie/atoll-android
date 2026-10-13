package dev.atoll.app.terminal

import dev.atoll.app.net.MemoryKeyValueStore
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class PtyFramesTest {
    @Test fun binaryUsesUnsignedBigEndianStreamIdAndPreservesBytes() {
        val input = byteArrayOf(0,-1,27,10) + "中文".toByteArray()
        val encoded = PtyFrames.binary(0xfedcba98, input)
        assertEquals("fedcba98", encoded.substring(0,4).hex())
        val decoded = PtyFrames.decode(encoded)!!
        assertEquals(0xfedcba98, decoded.id); assertArrayEquals(input, decoded.bytes)
        assertNull(PtyFrames.decode(byteArrayOf(1,2,3).toByteString())); assertNull(PtyFrames.decode(ByteArray(4).toByteString()))
    }
    @Test fun encodesControlFramesWithoutEmptySessionAndDecodesResponses() {
        val open = Json.parseToJsonElement(PtyFrames.open(5,"c","d","",100,40)).jsonObject
        assertFalse(open.containsKey("session")); assertEquals(JsonPrimitive("d"),open["device"])
        assertEquals(PtyControl.Ready(5,"abc"),PtyFrames.decode("""{"type":"ready","id":5,"session":"abc"}"""))
        assertEquals(PtyControl.Exit(5,"done"),PtyFrames.decode("""{"type":"exit","id":5,"reason":"done"}"""))
        assertNull(PtyFrames.decode("broken")); assertNull(PtyFrames.decode("""{"type":"ready","id":0}""")); assertNull(PtyFrames.decode("""{"type":"unknown","id":1}"""))
    }
    @Test fun controlAndAltKeysPreserveUtf8AndThemePersists() {
        assertArrayEquals(byteArrayOf(3), terminalCodePoint('c'.code,true))
        assertArrayEquals(byteArrayOf(27), terminalCodePoint('['.code,true))
        assertArrayEquals(byteArrayOf(0), terminalCodePoint('2'.code,true))
        assertArrayEquals(byteArrayOf(31), terminalCodePoint('/'.code,true))
        assertArrayEquals(byteArrayOf(27,97), terminalCodePoint('a'.code,false,true))
        assertArrayEquals("中".toByteArray(),terminalCodePoint('中'.code,true))
        val store = MemoryKeyValueStore(); val prefs = TerminalPreferences(store)
        assertEquals(TerminalTheme.DARK,prefs.theme.value); prefs.toggle()
        assertEquals(TerminalTheme.LIGHT,TerminalPreferences(store).theme.value)
    }
    @Test fun selectsLocalOrSingleDeviceWithoutGuessingAmongMultipleDevices() {
        assertEquals("local-device", preferredTerminalDevice(listOf(TerminalDevice("m"),TerminalDevice("local-device"))))
        assertEquals("m",preferredTerminalDevice(listOf(TerminalDevice("m"))))
        assertEquals("",preferredTerminalDevice(listOf(TerminalDevice("a"),TerminalDevice("b"))))
        assertEquals("b",preferredTerminalDevice(listOf(TerminalDevice("a"),TerminalDevice("b")),"b"))
    }
}
