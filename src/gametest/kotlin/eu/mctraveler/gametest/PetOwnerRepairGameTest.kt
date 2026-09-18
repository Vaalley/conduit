package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.importer.OfflineUuid
import eu.mctraveler.importer.PetOwnerRepair
import eu.mctraveler.mixin.AbstractHorseOwnerAccessor
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityReference
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

class PetOwnerRepairGameTest {
    @GameTest
    fun repairsAnImportedWolfOwnerAndAllowsItToStand(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, uniqueName("PetWolf"))
        val names = checkNotNull(MCTraveler.persistence).names
        names.record(player.uuid, player.gameProfile.name)
        val wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, BlockPos(2, 2, 2))
        wolf.tame(player)
        wolf.setOwnerReference(EntityReference.of<LivingEntity>(OfflineUuid.of(player.gameProfile.name)))
        wolf.setOrderedToSit(true)

        helper.assertTrue(PetOwnerRepair.repair(wolf), "the imported wolf owner was not repaired")
        helper.assertTrue(wolf.isOwnedBy(player), "the wolf is not owned by the player after repair")
        player.interactOn(wolf, InteractionHand.MAIN_HAND, wolf.position())
        helper.assertFalse(wolf.isOrderedToSit, "the repaired wolf did not stand for its owner")

        player.leave()
        helper.succeed()
    }

    @GameTest
    fun repairsAnImportedHorseOwner(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, uniqueName("PetHorse"))
        val names = checkNotNull(MCTraveler.persistence).names
        names.record(player.uuid, player.gameProfile.name)
        val horse = helper.spawnWithNoFreeWill(EntityTypes.HORSE, BlockPos(2, 2, 2))
        (horse as AbstractHorseOwnerAccessor).`mctraveler$setOwner`(
            EntityReference.of<LivingEntity>(OfflineUuid.of(player.gameProfile.name)),
        )

        helper.assertTrue(PetOwnerRepair.repair(horse), "the imported horse owner was not repaired")
        helper.assertTrue(horse.ownerReference?.uuid == player.uuid, "the horse owner after repair")

        player.leave()
        helper.succeed()
    }

    private fun uniqueName(prefix: String): String =
        "$prefix${UUID.randomUUID().toString().replace("-", "").take(8)}"
}
