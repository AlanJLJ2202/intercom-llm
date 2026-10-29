package mx.alan.intercom.core

import mx.alan.intercom.core.llm.ChatModel
import mx.alan.intercom.core.llm.ChatModelException
import mx.alan.intercom.core.llm.ChatRequest
import mx.alan.intercom.core.llm.ModelTurn

/** Modelo guionado: devuelve turnos en orden y registra las peticiones. */
class ScriptedChatModel(vararg turns: ModelTurn) : ChatModel {
    override val id = "scripted"
    private val queue = ArrayDeque(turns.toList())
    val requests = mutableListOf<ChatRequest>()
    var failWith: ChatModelException? = null

    override fun respond(request: ChatRequest): ModelTurn {
        requests += request
        failWith?.let { throw it }
        return queue.removeFirstOrNull() ?: ModelTurn("fin", emptyList(), "end_turn")
    }
}
