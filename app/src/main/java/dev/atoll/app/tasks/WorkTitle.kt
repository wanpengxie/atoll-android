package dev.atoll.app.tasks

/** Match the web list's collapsed whitespace; facts keep the full body for details. */
private val workTitleWhitespace=Regex("\\s+")
internal fun compactWorkTitle(title: String): String = title.replace(workTitleWhitespace, " ").trim()
