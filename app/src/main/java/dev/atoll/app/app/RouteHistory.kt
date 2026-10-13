package dev.atoll.app.app

/** Channel/view/focus entries share one bounded navigation history. */
internal class RouteHistory(private val limit: Int = 64) {
    private val past = ArrayDeque<AppRoute?>()
    private val future = ArrayDeque<AppRoute?>()
    val canBack get() = past.isNotEmpty()
    val canForward get() = future.isNotEmpty()
    fun push(current: AppRoute?) { past.addLast(current); while (past.size > limit) past.removeFirst(); future.clear() }
    fun back(current: AppRoute?): AppRoute? { check(canBack); future.addLast(current); return past.removeLast() }
    fun forward(current: AppRoute?): AppRoute? { check(canForward); past.addLast(current); return future.removeLast() }
    fun clear() { past.clear(); future.clear() }
}
