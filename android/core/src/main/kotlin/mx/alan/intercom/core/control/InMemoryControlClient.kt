package mx.alan.intercom.core.control

import java.util.concurrent.atomic.AtomicInteger

/** Implementación local para pruebas y modo demo sin servidor. */
class InMemoryControlClient : ControlClient {
    val ideas = mutableListOf<IdeaInput>()
    val radar = mutableListOf<IdeaInput>()
    val tasks = mutableListOf<TaskInput>()
    val notes = mutableListOf<NoteInput>()
    private val seq = AtomicInteger()

    override fun saveIdea(idea: IdeaInput) = ControlRecord("idea-${seq.incrementAndGet()}", "idea").also { ideas += idea }
    override fun sendToRadar(idea: IdeaInput) = ControlRecord("radar-${seq.incrementAndGet()}", "radar_idea").also { radar += idea }
    override fun createTask(task: TaskInput) = ControlRecord("task-${seq.incrementAndGet()}", "task").also { tasks += task }
    override fun addNote(note: NoteInput) = ControlRecord("note-${seq.incrementAndGet()}", "note").also { notes += note }
    override fun queryKnowledge(question: String) =
        KnowledgeAnswer("No tengo base de conocimiento conectada en modo demo.", emptyList())
    override fun recentResults(limit: Int) = emptyList<ResultSummary>()
    override fun pendingToday() = tasks.map { TaskSummary("t", it.title, it.due) }
}
