package org.bogsnebes.engines

import kotlin.test.Test
import kotlin.test.assertEquals

class MentionFormatterTest {
    @Test
    fun `escapes html in display names`() {
        assertEquals(
            "<a href=\"tg://user?id=77\">Sim &lt;Lead&gt; &amp; &quot;Ops&quot;</a>",
            MentionFormatter.renderTarget(
                userId = 77L,
                username = null,
                displayNameSnapshot = "Sim <Lead> & \"Ops\"",
            ),
        )
    }

    @Test
    fun `renders username target without link`() {
        assertEquals(
            "@alice",
            MentionFormatter.renderTarget(
                userId = null,
                username = "alice",
                displayNameSnapshot = "@alice",
            ),
        )
    }
}
