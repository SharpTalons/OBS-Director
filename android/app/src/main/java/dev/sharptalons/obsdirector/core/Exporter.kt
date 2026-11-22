package dev.sharptalons.obsdirector.core

/** Clip lists for editing software. Timecodes assume the recording starts at 00:00:00:00, 30 fps. */
object Exporter {
    const val FPS = 30
    val formats = listOf("Resolve EDL", "CSV", "Text")

    private fun tcf(ms: Long): String {
        val s = ms / 1000
        val f = (ms % 1000) * FPS / 1000
        return "%02d:%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60, f)
    }

    private fun tagNames(c: Clip) = c.tags.mapNotNull { Tag.of(it)?.label }

    fun export(rec: Recording, format: String): String = when (format) {
        "Resolve EDL" -> edl(rec)
        "CSV" -> csv(rec)
        else -> text(rec)
    }

    fun extension(format: String) = when (format) { "Resolve EDL" -> "edl"; "CSV" -> "csv"; else -> "txt" }

    /**
     * DaVinci Resolve: Timeline > Import > Timeline Markers from EDL. Each clip becomes a marker
     * spanning its length, coloured by its first tag (green when untagged).
     */
    fun edl(rec: Recording): String = buildString {
        append("TITLE: ").append(rec.file.substringBeforeLast('.')).append('\n')
        append("FCM: NON-DROP FRAME\n")
        rec.clips.forEachIndexed { i, c ->
            val durFrames = maxOf(1L, (c.lengthMs * FPS + 500) / 1000)
            val a = tcf(c.startMs)
            val b = tcf(c.startMs + durFrames * 1000 / FPS)
            val colour = c.tags.firstOrNull()?.let { Tag.of(it)?.resolve } ?: "Green"
            val name = listOf(tagNames(c).joinToString(", "), c.note).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "Clip ${i + 1}" }.replace("|", "/")
            append('\n')
            append("%03d  001      V     C        %s %s %s %s  \n".format(i + 1, a, b, a, b))
            append(" |C:ResolveColor").append(colour).append(" |M:").append(name).append(" |D:").append(durFrames).append('\n')
        }
    }

    fun csv(rec: Recording): String = buildString {
        append("number,start,end,duration_s,tags,note\n")
        rec.clips.forEachIndexed { i, c ->
            append(i + 1).append(',').append(tcf(c.startMs)).append(',').append(tcf(c.endMs)).append(',')
                .append("%.1f".format(c.lengthMs / 1000.0)).append(',')
                .append('"').append(tagNames(c).joinToString("; ")).append("\",")
                .append('"').append(c.note.replace("\"", "\"\"")).append("\"\n")
        }
    }

    fun text(rec: Recording): String = rec.clips.joinToString("\n") { c ->
        val tags = tagNames(c).takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "] ") ?: ""
        "${Director.tc(c.startMs)} - ${Director.tc(c.endMs)}  (${Director.len(c.lengthMs)})  $tags${c.note.ifBlank { "Clip" }}"
    }

    fun how(format: String) = when (format) {
        "Resolve EDL" -> "DaVinci Resolve: save as an .edl file, then Timeline, Import, Timeline Markers from EDL. Each clip lands as a marker spanning its length, coloured by its first tag."
        "CSV" -> "One row per clip with start, end, length, tags and note. Opens in a spreadsheet; most editors and marker tools can read it."
        else -> "A plain list to read through, or to paste into a script or description."
    }
}
