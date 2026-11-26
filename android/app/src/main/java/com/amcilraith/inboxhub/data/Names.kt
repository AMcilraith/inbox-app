package com.amcilraith.inboxhub.data

import android.content.Context
import kotlin.math.ln

/**
 * Readable sender names from bare addresses — the same rules as the desktop app's names.rs:
 * "meadowoodpremierdentistry@gmail.com" -> "Meadowood Premier Dentistry", "jkolapo@…" -> "J Kolapo",
 * "orders@shopify.com" -> "Shopify". Uses a bundled, ranked English word list (assets/words-en.txt).
 */
object Names {
    @Volatile private var ranks: Map<String, Int> = emptyMap()

    fun init(context: Context) {
        if (ranks.isNotEmpty()) return
        ranks = context.assets.open("words-en.txt").bufferedReader().useLines { lines ->
            lines.withIndex().associate { (i, w) -> w to i + 1 }
        }
    }

    private val SHORT_OK = setOf("a", "an", "of", "the", "my", "to", "in", "on", "at", "by", "for", "and", "or", "me", "we", "us", "go", "do", "no", "up", "it", "is", "be")
    private val GENERIC = setOf(
        "info", "contact", "hello", "hi", "support", "help", "noreply", "no-reply", "donotreply", "do-not-reply", "notifications",
        "notification", "orders", "order", "team", "admin", "mail", "email", "office", "billing", "sales", "news", "newsletter",
        "updates", "alerts", "service", "services", "customerservice", "reply", "accounts", "account", "receipts", "marketing",
        "hr", "careers", "jobs", "security", "store", "shop", "bounce", "mailer-daemon", "postmaster", "calendar", "invitations",
    )
    private val ACRONYMS = setOf("ceo", "cfo", "cto", "hr", "it", "pr", "ai", "api", "dmv", "usa", "uk", "eu", "ca", "rbc", "td", "cibc", "bmo")
    private val SLD = setOf("co", "com", "org", "net", "gov", "ac", "edu")
    private val ONSETS = setOf(
        "bl", "br", "ch", "cl", "cr", "dr", "dw", "fl", "fr", "gh", "gl", "gr", "kh", "kl", "kn", "kr", "ph", "pl", "pr", "qu", "rh", "sc",
        "sh", "sk", "sl", "sm", "sn", "sp", "st", "sw", "th", "tr", "tw", "wh", "wr", "zh",
    )

    private fun known(w: String): Int? {
        val r = ranks[w] ?: return null
        if (w.length <= 2 && w !in SHORT_OK) return null
        if (w.length <= 4 && r > 15_000) return null
        return r
    }

    private fun cost(w: String): Double {
        val r = known(w)
        return when {
            r != null -> ln((r + 1).toDouble()) + 6.0
            w.length < 4 -> Double.POSITIVE_INFINITY
            else -> 19.0 + 2.0 * w.length
        }
    }

    private fun segment(s: String): Pair<List<String>, Double> {
        val n = s.length
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val back = IntArray(n + 1)
        val unk = BooleanArray(n + 1)
        best[0] = 0.0
        for (i in 1..n) for (j in maxOf(0, i - 24) until i) {
            if (best[j].isInfinite()) continue
            val piece = s.substring(j, i)
            val isUnk = known(piece) == null
            if (isUnk && unk[j]) continue
            val c = best[j] + cost(piece)
            if (c < best[i]) { best[i] = c; back[i] = j; unk[i] = unk[j] || isUnk }
        }
        if (best[n].isInfinite()) return listOf(s) to Double.POSITIVE_INFINITY
        val out = ArrayList<String>()
        var i = n
        while (i > 0) { out.add(s.substring(back[i], i)); i = back[i] }
        return out.reversed() to best[n]
    }

    private fun vowel(c: Char) = c in "aeiouy"

    private fun splitToken(t: String, allowPrefix: Boolean = true): List<String> {
        if (t.length <= 5 || known(t) != null || !t.all { it in 'a'..'z' }) return listOf(t)
        if (allowPrefix && !vowel(t[0]) && !vowel(t[1]) && t.substring(0, 2) !in ONSETS) {
            if (known(t.substring(2)) != null) return listOf(t.substring(0, 2), t.substring(2))
            return listOf(t.substring(0, 1)) + splitToken(t.substring(1), false)
        }
        val (parts, c) = segment(t)
        return if (parts.size in 2..5 && c < cost(t)) parts else listOf(t)
    }

    private fun cap(w: String): String = when {
        w in ACRONYMS || (w.length <= 2 && known(w) == null) -> w.uppercase()
        w.startsWith("mc") && w.length > 4 -> "Mc" + w.substring(2, 3).uppercase() + w.substring(3)
        else -> w.replaceFirstChar { it.uppercase() }
    }

    fun displayName(addr: String): String {
        var a = addr.trim()
        val lt = a.indexOf('<')
        if (lt >= 0) {
            val n = a.substring(0, lt).trim().trim('"').trim()
            if (n.isNotEmpty()) return n
            a = a.substring(lt + 1)
        }
        a = a.trim('<', '>').lowercase()
        val local = a.substringBefore('@')
        val domain = a.substringAfter('@', "")
        val tokens = local.split(Regex("[._\\-+0-9]+")).filter { it.isNotEmpty() }
        val generic = tokens.isEmpty() || local in GENERIC || local.replace("-", "").replace(".", "") in GENERIC || tokens.all { it in GENERIC }
        val words = if (generic && domain.isNotEmpty()) {
            val labels = domain.split('.').filter { it.isNotEmpty() }.dropLast(1).toMutableList()
            while (labels.size > 1 && (labels.last() in SLD || labels.last().length <= 2)) labels.removeAt(labels.size - 1)
            splitToken(labels.lastOrNull() ?: local)
        } else tokens.flatMap { splitToken(it) }
        return words.joinToString(" ") { cap(it) }.ifEmpty { addr }
    }
}
