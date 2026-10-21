package com.iamode.app.domain

import com.iamode.app.ui.navigation.CommitmentNavigation
import com.iamode.app.ui.navigation.DeepLink
import com.iamode.app.ui.navigation.destination
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentNavigationTest {
    @Test fun `a commitment deep link targets its own detail route`() {
        val id = "commitment/with spaces"
        assertEquals("commitments/commitment%2Fwith%20spaces", CommitmentNavigation.route(id))
        assertEquals("commitments/commitment%2Fwith%20spaces", DeepLink.Commitment(id).destination())
    }

    @Test fun `a commitment deep link preserves unicode without adding a path separator`() {
        assertEquals("commitments/%E0%A4%AA%E0%A5%8D%E0%A4%B0%E0%A4%A4%E0%A4%BF%E0%A4%AC%E0%A4%A6%E0%A5%8D%E0%A4%A7%2F1", CommitmentNavigation.route("प्रतिबद्ध/1"))
    }
}
