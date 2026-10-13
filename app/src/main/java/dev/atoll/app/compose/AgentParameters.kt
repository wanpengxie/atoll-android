package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

data class ModelChoice(val model: String, val effort: String = "", val modelLabel: String = model, val effortLabel: String = effort)
data class AgentParameters(val actorId: String, val choices: List<ModelChoice> = emptyList(), val current: ModelChoice? = null, val contextTokens: Long = 0, val contextWindow: Long = 0, val client: JsonObject? = null, val loading: Boolean = false, val switching: Boolean = false, val error: String = "") {
    val percent: Int get() = if (contextWindow > 0) ((contextTokens * 100 / contextWindow).coerceIn(0, 100)).toInt() else 0
}
object AgentParameterParser {
    fun options(actorId: String, value: JsonObject, manifest: JsonObject? = null): AgentParameters {
        val choices = value.array("models").flatMap { row ->
            val model = row as? JsonObject ?: return@flatMap emptyList()
            val id = model.text("value")
            if (id.isEmpty()) return@flatMap emptyList()
            val efforts = model.array("efforts")
            if (efforts.isEmpty()) listOf(ModelChoice(id, modelLabel = model.text("label").ifEmpty { id })) else efforts.mapNotNull { item ->
                val effort = item as? JsonObject ?: return@mapNotNull null
                effort.text("value").takeIf { it.isNotEmpty() }?.let { ModelChoice(id, it, model.text("label").ifEmpty { id }, effort.text("label").ifEmpty { it }) }
            }
        }.ifEmpty { fallback(manifest) }
        val current = (value["current"] as? JsonObject)?.let { it.text("model").takeIf(String::isNotEmpty)?.let { model -> ModelChoice(model, it.text("effort")) } }
        return AgentParameters(actorId, choices, current, client = value["client"] as? JsonObject)
    }
    fun context(parameters: AgentParameters, value: JsonObject): AgentParameters {
        val usage = value["usage"] as? JsonObject ?: value
        val model = usage.text("model")
        return parameters.copy(current = if (model.isNotEmpty()) ModelChoice(model, usage.text("effort")) else parameters.current,
            contextTokens = if ("context_tokens" in usage) usage.number("context_tokens") else parameters.contextTokens,
            contextWindow = if ("context_window" in usage) usage.number("context_window") else parameters.contextWindow)
    }
    fun fallback(manifest: JsonObject?): List<ModelChoice> {
        val word = (manifest?.get("words") as? JsonObject)?.get("agent.select") as? JsonObject ?: return emptyList()
        val schema = (word["input_schema"] ?: word["inputSchema"]) as? JsonObject ?: return emptyList()
        return schema.array("oneOf").mapNotNull { branch ->
            val props = (branch as? JsonObject)?.get("properties") as? JsonObject ?: return@mapNotNull null
            val model = props["model"] as? JsonObject ?: return@mapNotNull null
            val effort = props["effort"] as? JsonObject ?: return@mapNotNull null
            val id = model.text("const"); val strength = effort.text("const")
            if (id.isEmpty() || strength.isEmpty()) null else ModelChoice(id, strength, model.text("title").ifEmpty { id }, effort.text("title").ifEmpty { strength })
        }
    }
}
