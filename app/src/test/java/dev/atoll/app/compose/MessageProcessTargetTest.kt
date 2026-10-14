package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.screens.TurnKind
import dev.atoll.app.ui.screens.TurnViewState
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageProcessTargetTest {
    private fun envelope(id: String) = Envelope(buildJsonObject { put("id", id) })
    @Test fun answerOpensItsRequestRatherThanTerminalEnvelope() {
        assertEquals("request", messageProcessId(MessageViewState("answer", "agent", "answer", ""),
            envelope("terminal"), Turn(envelope("request"), 1)))
    }
    @Test fun compactTurnOpensSuccessorAndLostTurnOpensOriginal() {
        val message = MessageViewState("row", "self", "question", "")
        for (kind in listOf(TurnKind.Merged, TurnKind.Preempted)) {
            assertEquals("successor", messageProcessId(message.copy(turnState = TurnViewState(kind, "original", "successor")),
                envelope("terminal"), Turn(envelope("original"), 1)))
        }
        assertEquals("original", messageProcessId(message.copy(turnState = TurnViewState(TurnKind.Lost, "original")), envelope("terminal"), null))
    }
    @Test fun plainMessageOpensItsEnvelope() {
        assertEquals("envelope", messageProcessId(MessageViewState("presentation-key", "human", "hi", ""), envelope("envelope"), null))
    }
}
