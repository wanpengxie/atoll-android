package dev.atoll.app.app

/** Channel/view/focus entries share one bounded navigation history. */
internal data class WorkspaceLocation(val route: AppRoute?, val globalPanel: WorkspacePanel? = null)

internal class RouteHistory<T>(private val limit: Int = 64) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    val canBack get() = past.isNotEmpty()
    val canForward get() = future.isNotEmpty()
    fun push(current: T) { past.addLast(current); while (past.size > limit) past.removeFirst(); future.clear() }
    fun back(current: T): T { check(canBack); future.addLast(current); return past.removeLast() }
    fun forward(current: T): T { check(canForward); past.addLast(current); return future.removeLast() }
    fun clear() { past.clear(); future.clear() }
}
