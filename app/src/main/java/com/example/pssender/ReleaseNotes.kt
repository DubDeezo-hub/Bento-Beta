package com.example.pssender

// ---------------------------------------------------------------------------------------------
// A payload's release notes, as its maker wrote them on GitHub (Markdown), turned into plain text that reads well in a
// small dialog: headings, links, pictures and formatting marks go, lists keep a dot, and very long notes are cut.
// ---------------------------------------------------------------------------------------------

/** The most characters of release notes shown; longer notes end with "…" (the full notes are on the release page). */
const val RELEASE_NOTES_MAX = 2000

/** [markdown] as plain text, at most [max] characters. Empty when there is nothing worth showing. */
fun plainReleaseNotes(markdown: String, max: Int = RELEASE_NOTES_MAX): String {
    var t = markdown.replace("\r\n", "\n").replace('\r', '\n')
    t = t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")      // hidden comments
    t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")                         // pictures
    t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")                      // links: keep their words
    t = t.replace(Regex("<[^>\\n]+>"), "")                                      // stray HTML tags
    val lines = t.lines().map { raw ->
        var l = raw.trimEnd()
        l = l.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")                         // headings
        l = l.replace(Regex("^(\\s*)[*+-]\\s+"), "$1• ")                       // list items
        l = l.replace(Regex("^\\s*>\\s?"), "")                                  // quotes
        if (Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(l)) "" else l      // dividers
    }
    t = lines.joinToString("\n")
    t = t.replace("**", "").replace("__", "").replace("`", "")
    t = t.replace(Regex("\n{3,}"), "\n\n").trim()
    if (t.length > max) t = t.take(max).trimEnd() + "…"
    return t
}
