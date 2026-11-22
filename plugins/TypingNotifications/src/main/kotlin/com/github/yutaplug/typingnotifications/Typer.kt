package com.github.yutaplug.typingnotifications

import android.text.SpannableStringBuilder
import b.a.k.b as FormatUtils

/** Someone typing who passed the filters. */
internal class Typer(
    val key: String,
    val userId: Long,
    val name: String,
    /** "#general · Server", "your DMs", a group DM's name, or "" when the channel is unknown. */
    val place: String,
    val iconUrl: String?,
    val channelId: Long,
    val guildId: Long,
    var lastSeen: Long,
)

/** The texts shown for everyone typing right now, newest first. */
internal class TypingText(typers: List<Typer>) {
    val title: String
    val body: String
    val compact: CharSequence

    init {
        val names = typers.map { it.name }.distinct()
        val places = typers.map { it.place }.distinct()
        val newest = typers[0]
        if (typers.size == 1) {
            // "Name" / "is typing in #general · Server".
            title = newest.name
            body = if (newest.place.isEmpty()) "is typing" else "is typing in ${newest.place}"
        } else {
            // "A, B and C are typing" / where, one line per person when they're in different places.
            title = "${joinNames(names) { it }} ${if (names.size == 1) "is" else "are"} typing"
            body = when {
                places.size > 1 -> perPlace(typers, StringBuilder()) { it }.toString()
                places[0].isEmpty() -> ""
                else -> "in ${places[0]}"
            }
        }
        compact = compactText(typers, names, places)
    }

    /**
     * One line in the style of Discord's typing bar, "**A** and **B** are typing in #general · Server",
     * plus one line per person when they're typing in different places.
     */
    private fun compactText(typers: List<Typer>, names: List<String>, places: List<String>): CharSequence {
        val text = SpannableStringBuilder()
        text.append(joinNames(names, ::bold))
        text.append(if (names.size == 1) " is typing" else " are typing")
        if (places.size == 1) {
            if (places[0].isNotEmpty()) text.append(" in ").append(places[0])
            return text
        }
        text.append('\n')
        return perPlace(typers, text, ::bold)
    }

    private fun <T : Appendable> perPlace(typers: List<Typer>, out: T, name: (String) -> CharSequence): T {
        val lines = typers.take(MAX_LINES)
        lines.forEachIndexed { index, typer ->
            if (index > 0) out.append('\n')
            out.append(name(typer.name))
            if (typer.place.isNotEmpty()) out.append(" in ").append(typer.place)
        }
        if (typers.size > lines.size) out.append("\nand ${typers.size - lines.size} more")
        return out
    }

    /** "A", "A and B", "A, B and C", or "A, B and 3 others". */
    private fun joinNames(names: List<String>, name: (String) -> CharSequence): CharSequence {
        val text = SpannableStringBuilder()
        val shown = if (names.size > 3) names.take(2) else names
        shown.forEachIndexed { index, value ->
            if (index > 0) text.append(if (index == shown.size - 1 && names.size <= 3) " and " else ", ")
            text.append(name(value))
        }
        if (names.size > 3) text.append(" and ${names.size - 2} others")
        return text
    }

    private companion object {
        const val MAX_LINES = 3

        /** Bolds a name with Discord's own formatter, as its typing bar does (`**!!{a}!!**`). */
        fun bold(name: String): CharSequence =
            runCatching { FormatUtils.l("**!!{a}!!**", arrayOf(name), null, 2) }.getOrNull() ?: name
    }
}
