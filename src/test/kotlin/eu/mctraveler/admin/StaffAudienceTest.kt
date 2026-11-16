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
        assertEquals(listOf(Rank.NEWBIE, Rank.TRAVELER, Rank.MODERATOR), Rank.entries.toList())
        assertTrue(Rank.MODERATOR.outranks(Rank.TRAVELER))
        assertFalse(Rank.TRAVELER.outranks(Rank.TRAVELER))
        assertFalse(Rank.NEWBIE.outranks(Rank.TRAVELER))
        assertEquals(listOf("newbie", "traveler", "moderator"), Rank.COMMAND_NAMES)
    }

    @Test
    fun moderatorsWearTheTravelerColour() {
        assertEquals(Rank.TRAVELER.chatColor("x"), Rank.MODERATOR.chatColor("x"))
    }

    @Test
    fun donatorIsNotARank() {
        assertEquals(null, Rank.parse("donator"))
        assertTrue(Rank.DONATOR_REGION_CAP > Rank.REGION_CAP)
    }

    @Test
    fun staffAudienceNeedsAdminModeNotJustARank() {
        // A moderator rank alone (staff = false) is not staff; admin mode makes them one.
        assertFalse(Audience.STAFF.permits(admin = false, rank = Rank.MODERATOR, staff = false))
        assertTrue(Audience.STAFF.permits(admin = false, rank = Rank.MODERATOR, staff = true))
        assertTrue(Audience.STAFF.permits(admin = true, rank = Rank.TRAVELER))
        assertFalse(Audience.STAFF.permits(admin = false, rank = Rank.TRAVELER, staff = false, donator = true))
    }

    @Test
    fun adminAudienceExcludesModerators() {
        assertFalse(Audience.ADMIN.permits(admin = false, rank = Rank.MODERATOR, staff = true))
        assertTrue(Audience.ADMIN.permits(admin = true, rank = Rank.TRAVELER))
    }

    @Test
    fun donatorPerksComeFromTheExtraNotTheRank() {
        assertFalse(Audience.DONATOR.permits(admin = false, rank = Rank.MODERATOR, staff = false))
        assertTrue(Audience.DONATOR.permits(admin = false, rank = Rank.MODERATOR, staff = false, donator = true))
        assertTrue(Audience.DONATOR.permits(admin = false, rank = Rank.NEWBIE, staff = false, donator = true))
        assertTrue(Audience.TRAVELER.permits(admin = false, rank = Rank.MODERATOR, staff = false))
    }

    @Test
    fun moderatorVanillaCommandsAreTheListedOnes() {
        assertEquals(
            setOf("tp", "teleport", "kill", "weather", "time", "whitelist", "recipe"),
            StaffAccess.MODERATOR_VANILLA_COMMANDS,
        )
    }
}
