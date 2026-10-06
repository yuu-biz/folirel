package io.github.yuubiz.folirel.ui.home

import java.text.Normalizer
import java.util.Locale

/**
 * The summary a Magazine card shows (MD-02). Feeds often put only a link label where the summary belongs ("続きを読む", "Read more");
 * such a summary tells nothing about the article, so the card shows none and keeps no room for it. Only a summary that consists of
 * nothing but one of these labels (with the decoration feeds add around them) is dropped; a sentence that merely contains one, such as
 * "続きを読む前に知っておくこと", stays as it is. This is display only: the stored summary, search and the article are unchanged.
 */
internal object CardSummary {
    /** Labels that, alone, are not a summary (compared after [normalize]). */
    private val LINK_LABELS = setOf(
        "続きを読む", "続きをよむ", "続きを見る", "続きはこちら", "続きはこちらから", "続き", "もっと読む", "もっと見る",
        "全文を読む", "全文を表示", "記事を読む", "記事の続きを読む", "記事全文を読む", "詳細はこちら", "詳しくはこちら",
        "read more", "continue reading", "read the full article", "read the full story", "more", "full article", "full story",
        "read on", "click here to read more",
    )

    /** Decoration around a label: arrows, brackets, ellipses, punctuation. */
    private val DECORATION = Regex("[\\s\\u3000.。…‥・:：>＞»›→⇒▶▸►\\-‐–—~〜…\\[\\]［］（）()「」『』【】<＜«‹]+")

    fun displayed(summary: String): String {
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return ""
        // Only a label, or only decoration ("…", "[...]"): nothing that tells what the article is about.
        val bare = normalize(trimmed)
        return if (bare.isEmpty() || bare in LINK_LABELS) "" else trimmed
    }

    /** Lower case, full-width forms folded, decoration removed: "【続きを読む】»" and "Read More…" become their bare label. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(DECORATION, " ").trim().replace(Regex("\\s+"), " ")
}
