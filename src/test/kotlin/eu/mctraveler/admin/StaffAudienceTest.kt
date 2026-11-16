package eu.mctraveler.admin

import eu.mctraveler.command.Audience
import eu.mctraveler.rank.Rank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaffAudienceTest {

    @Test
    fun theLadderRunsFromNewbieToModerator() {
        assertEquals(
            listOf(Rank.NEWBIE, Rank.TRAVELER, Rank.DONATOR, Rank.MODERATOR),
            Rank.entries.toList(),
        )
        assertTrue(Rank.MODERATOR.outranks(Rank.DONATOR))
        assertFalse(Rank.DONATOR.outranks(Rank.DONATOR))
        assertFalse(Rank.TRAVELER.outranks(Rank.DONATOR))
    }

    @Test
    fun moderatorsKeepTheDonatorPerks() {
        assertTrue(Rank.MODERATOR.hasDonatorPerks)
        assertTrue(Rank.DONATOR.hasDonatorPerks)
        assertFalse(Rank.TRAVELER.hasDonatorPerks)
        assertEquals(Rank.DONATOR.regionAreaCap, Rank.MODERATOR.regionAreaCap)
        assertEquals("moderator", Rank.COMMAND_NAMES.last())
    }

    @Test
    fun staffAudienceNeedsAdminModeNotJustARank() {
        // A moderator rank alone (staff = false) is not staff; admin mode makes them one.
        assertFalse(Audience.STAFF.permits(admin = false, rank = Rank.MODERATOR, staff = false))
        assertTrue(Audience.STAFF.permits(admin = false, rank = Rank.MODERATOR, staff = true))
        assertTrue(Audience.STAFF.permits(admin = true, rank = Rank.TRAVELER))
        assertFalse(Audience.STAFF.permits(admin = false, rank = Rank.DONATOR, staff = false))
    }

    @Test
    fun adminAudienceExcludesModerators() {
        assertFalse(Audience.ADMIN.permits(admin = false, rank = Rank.MODERATOR, staff = true))
        assertTrue(Audience.ADMIN.permits(admin = true, rank = Rank.TRAVELER))
    }

    @Test
    fun moderatorsStillPassTheLowerAudiencesOutsideAdminMode() {
        assertTrue(Audience.DONATOR.permits(admin = false, rank = Rank.MODERATOR, staff = false))
        assertTrue(Audience.TRAVELER.permits(admin = false, rank = Rank.MODERATOR, staff = false))
        assertFalse(Audience.DONATOR.permits(admin = false, rank = Rank.TRAVELER, staff = false))
    }

    @Test
    fun moderatorVanillaCommandsAreTheListedOnes() {
        assertEquals(
            setOf("tp", "teleport", "kill", "weather", "time", "whitelist", "recipe"),
            StaffAccess.MODERATOR_VANILLA_COMMANDS,
        )
    }
}
