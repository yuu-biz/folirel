package io.github.yuubiz.folirel.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** MD-02: only a summary that is nothing but a "read more" label is dropped from a Magazine card; real text stays as it is. */
class CardSummaryTest {
    @Test
    fun aLabelAloneIsNotASummary() {
        for (label in listOf("続きを読む", " 続きを読む ", "続きを読む…", "【続きを読む】", "続きを読む »", "続きはこちら→", "もっと読む", "Read more", "Read More...", "READ MORE »", "Continue reading →", "[...]", "…", "   ", "")) {
            assertEquals("'$label'", "", CardSummary.displayed(label))
        }
    }

    @Test
    fun textThatOnlyContainsALabelStays() {
        for (text in listOf("続きを読む前に知っておくこと", "この記事の続きを読むには会員登録が必要です", "Read more about the new phone", "短い概要です。", "更新しました", "More than a phone")) {
            assertEquals(text, CardSummary.displayed(text))
        }
    }

    @Test
    fun ordinaryTextIsOnlyTrimmed() {
        assertEquals("通常の概要です。続きを読む", CardSummary.displayed("  通常の概要です。続きを読む  "))
    }
}
