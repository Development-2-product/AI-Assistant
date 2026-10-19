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
}
