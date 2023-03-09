package me.luna.trollhack.module.modules.combat

import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.RunGameLoopEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.events.WorldEvent
import me.luna.trollhack.event.events.combat.CrystalSetDeadEvent
import me.luna.trollhack.event.events.combat.CrystalSpawnEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.event.safeParallelListener
import me.luna.trollhack.manager.managers.CombatManager
import me.luna.trollhack.manager.managers.HoleManager
import me.luna.trollhack.manager.managers.HotbarManager.spoofHotbar
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.module.modules.player.PacketMine
import me.luna.trollhack.util.EntityUtils.betterPosition
import me.luna.trollhack.util.EntityUtils.eyePosition
import me.luna.trollhack.util.EntityUtils.spoofSneak
import me.luna.trollhack.util.TickTimer
import me.luna.trollhack.util.accessor.id
import me.luna.trollhack.util.accessor.packetAction
import me.luna.trollhack.util.combat.CalcContext
import me.luna.trollhack.util.combat.CrystalUtils
import me.luna.trollhack.util.combat.CrystalUtils.canPlaceCrystal
import me.luna.trollhack.util.extension.sq
import me.luna.trollhack.util.inventory.slot.firstBlock
import me.luna.trollhack.util.inventory.slot.firstItem
import me.luna.trollhack.util.inventory.slot.hotbarSlots
import me.luna.trollhack.util.world.FastRayTraceAction
import me.luna.trollhack.util.world.fastRaytrace
import me.luna.trollhack.util.world.getBlock
import me.luna.trollhack.util.world.isReplaceable
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.network.play.client.CPacketAnimation
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.client.CPacketUseEntity
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos

@CombatManager.CombatModule
internal object AutoCity : Module(
    name = "AutoCity",
    category = Category.COMBAT,
    description = "Trolling",
    modulePriority = 100
) {
    private val useAnvil by setting("Use Anvil", false)
    private val placeDelay by setting("Place Delay", 100, 0..1000, 5)
    private val breakDelay by setting("Break Delay", 100, 0..1000, 5)
    private val minDamage by setting("Min Damage", 4.0f, 1.0f..10.0f, 0.1f)
    private val minUpdateDamage by setting("Min Update Damage", 8.0f, 1.0f..10.0f, 0.1f)
    private val updateDelay by setting("Update Delay", 200, 0..1000, 5)
    private val packetBreakDelay by setting("Packet Break Delay", 100, 0..1000, 5)
    private val range by setting("Range", 4.5f, 1.0f..6.0f, 0.1f)

    private val placeTimer = TickTimer()
    private val breakTimer = TickTimer()
    private val updateTimer = TickTimer()
    private val packetBreakTimer = TickTimer()

    private var anvilPos: BlockPos? = null
    private var crystalPos: BlockPos? = null
    private var holePos: BlockPos? = null

    private var anvilPlaced = false
    private var crystalID = -1

    override fun isActive(): Boolean {
        return isEnabled && anvilPos != null
    }

    init {
        onDisable {
            placeTimer.reset(-114514L)
            breakTimer.reset(-114514L)

            anvilPos = null
            crystalPos = null
            holePos = null

            anvilPlaced = false
            crystalID = -1
        }

        safeParallelListener<TickEvent.Post> {
            updateTargetPos()

            val anvilPos = anvilPos
            val crystalPos = crystalPos

            if (anvilPos == null || crystalPos == null) {
                PacketMine.reset(AutoCity)
                return@safeParallelListener
            }

            val posUp = anvilPos.up()
            val block = world.getBlock(posUp)

            anvilPlaced = block != Blocks.AIR

            crystalID = CombatManager.crystalList
                .find {
                    !it.first.isDead
                        && CrystalUtils.crystalIntersects(it.second.crystalPos, crystalPos)
                }?.first?.entityId ?: -1

            if (block != Blocks.AIR) {
                PacketMine.mineBlock(AutoCity, posUp, AutoCity.modulePriority)
            }
        }

        safeListener<CrystalSpawnEvent> {
            val anvilPos = anvilPos ?: return@safeListener
            val crystalPos = crystalPos ?: return@safeListener

            if (CrystalUtils.crystalIntersects(crystalPos, it.crystalDamage.blockPos)) {
                crystalID = it.entityID
                if (!anvilPlaced && breakTimer.tick(0)) {
                    breakCrystal(it.entityID)
                    placeAnvil(anvilPos)
                    placeCrystal(crystalPos)
                }
            }
        }

        safeListener<WorldEvent.ServerBlockUpdate> {
            val entityID = crystalID

            val anvilPos = anvilPos ?: return@safeListener
            val crystalPos = crystalPos ?: return@safeListener
            val posUp = anvilPos.up()

            if (it.pos != posUp) return@safeListener

            if (it.newState.block == Blocks.AIR) {
                anvilPlaced = false

                if (entityID != -1
                    && packetBreakTimer.tick(packetBreakDelay)
                    && breakTimer.tick(0)) {
                    breakCrystal(entityID)
                    placeAnvil(anvilPos)
                    placeCrystal(crystalPos)
                    packetBreakTimer.reset()
                }
            } else {
                PacketMine.mineBlock(AutoCity, posUp, AutoCity.modulePriority)
            }
        }

        safeListener<CrystalSetDeadEvent> { event ->
            val anvilPos = anvilPos ?: return@safeListener
            val crystalPos = crystalPos ?: return@safeListener
            if (event.crystals.none { CrystalUtils.crystalIntersects(it, crystalPos) }) return@safeListener
            crystalID = -1

            placeAnvil(anvilPos)
            placeCrystal(crystalPos)
        }

        safeListener<RunGameLoopEvent.Tick> {
            val anvilPos = anvilPos ?: return@safeListener
            val crystalPos = crystalPos ?: return@safeListener
            val entityID = crystalID

            if (!anvilPlaced && entityID != -1 && breakTimer.tick(breakDelay)) {
                breakCrystal(entityID)
                placeAnvil(anvilPos)
                placeCrystal(crystalPos)

                placeTimer.reset()
            }

            if (placeTimer.tick(placeDelay)) {
                if (!anvilPlaced) {
                    placeAnvil(anvilPos)
                } else {
                    val anvilPosUp = anvilPos.up()
                    if (world.getBlock(anvilPosUp) != Blocks.ANVIL
                        && world.getBlockState(anvilPosUp.up()).isReplaceable) {
                        placeAnvil(anvilPosUp)
                    }
                }

                if (entityID == -1) {
                    placeCrystal(crystalPos)
                }
            }
        }
    }

    private fun SafeClientEvent.updateTargetPos() {
        val flag = anvilPos != null
        val rangeSq = range.sq
        val target = CombatManager.target

        if (target != null) {
            val targetPos = target.betterPosition

            val result = if (flag || targetPos == holePos || HoleManager.getHoleInfo(target).isHole) {
                val playerPos = player.betterPosition
                val eyePos = player.eyePosition
                val mutableBlockPos = BlockPos.MutableBlockPos()

                val anvilPos = anvilPos
                val crystalPos = crystalPos

                CombatManager.contextTarget?.let { context ->
                    if (anvilPos != null && crystalPos != null) {
                        val damage = calcDamage(context, anvilPos.up(), crystalPos, mutableBlockPos)
                        if (damage > minUpdateDamage && !updateTimer.tick(updateDelay)) return
                    }

                    val sequence = EnumFacing.HORIZONTALS.asSequence()
                        .flatMap { mainSide ->
                            val opposite = mainSide.opposite
                            val pos1 = targetPos.offset(mainSide)
                            EnumFacing.HORIZONTALS.asSequence()
                                .filter {
                                    it != opposite
                                }.map {
                                    pos1 to pos1.offset(it).down()
                                }
                        }.filter {
                            playerPos.distanceSq(it.first) <= rangeSq
                        }
                        .filter {
                            val dist = playerPos.distanceSq(it.second)
                            dist <= rangeSq
                                && (dist <= 9
                                || !world.fastRaytrace(eyePos, it.second.x + 0.5, it.second.y + 2.7, it.second.z + 0.5, 20, mutableBlockPos) { rayTracePos, blockState ->
                                if (rayTracePos != it.first && blockState.block != Blocks.AIR && CrystalUtils.isResistant(blockState)) {
                                    FastRayTraceAction.CALC
                                } else {
                                    FastRayTraceAction.SKIP
                                }
                            })
                        }
                        .filter { (anvilPos, crystalPos) ->
                            world.getBlock(anvilPos) != Blocks.BEDROCK
                                && canPlaceCrystal(crystalPos)
                        }

                    var maxDamage = minDamage
                    var result: Pair<BlockPos, BlockPos>? = null

                    for (pair in sequence) {
                        val damage = calcDamage(context, pair.first, pair.second, mutableBlockPos)

                        if (damage > maxDamage) {
                            maxDamage = damage
                            result = pair
                        }
                    }

                    val prevAnvil = this@AutoCity.anvilPos
                    val prevAnvilUp = this@AutoCity.anvilPos?.up()
                    val prevCrystal = this@AutoCity.crystalPos

                    if (result != null
                        && prevAnvil != null && prevAnvilUp != null && prevCrystal != null
                        && (result.first != prevAnvilUp || result.second != prevCrystal)) {
                        val blockState = world.getBlockState(prevAnvil)
                        if ((blockState.block == Blocks.ANVIL || !CrystalUtils.isResistant(blockState))
                            || maxDamage - calcDamage(context, prevAnvilUp, prevCrystal, mutableBlockPos) < 2.0f) {
                            result = prevAnvilUp to prevCrystal
                        }
                    }


                    result
                }
            } else {
                null
            }

            if (result != null) {
                anvilPos = result.first.down()
                crystalPos = result.second
                holePos = target.betterPosition
                return
            }
        }

        anvilPos = null
        crystalPos = null
        holePos = null
    }

    private fun calcDamage(context: CalcContext, anvilPos: BlockPos, pos: BlockPos, mutableBlockPos: BlockPos.MutableBlockPos): Float {
        return context.calcDamage(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, false, 6.0f, mutableBlockPos) { rayTracePos, blockState ->
            if (rayTracePos != anvilPos && blockState.block != Blocks.AIR && CrystalUtils.isResistant(blockState)) {
                FastRayTraceAction.CALC
            } else {
                FastRayTraceAction.SKIP
            }
        }
    }

    private fun SafeClientEvent.placeCrystal(targetPos: BlockPos) {
        player.hotbarSlots.firstItem(Items.END_CRYSTAL)?.let {
            spoofHotbar(it) {
                connection.sendPacket(CPacketPlayerTryUseItemOnBlock(targetPos, EnumFacing.UP, EnumHand.MAIN_HAND, 0.5f, 1.0f, 0.5f))
            }
            connection.sendPacket(CPacketAnimation(EnumHand.MAIN_HAND))
            placeTimer.reset()
        }
    }

    private fun SafeClientEvent.placeAnvil(targetPos: BlockPos) {
        if (!useAnvil) return
        player.hotbarSlots.firstBlock(Blocks.ANVIL)?.let {
            player.spoofSneak {
                spoofHotbar(it) {
                    connection.sendPacket(CPacketPlayerTryUseItemOnBlock(targetPos, EnumFacing.UP, EnumHand.MAIN_HAND, 0.5f, 1.0f, 0.5f))
                }
            }
            connection.sendPacket(CPacketAnimation(EnumHand.MAIN_HAND))
            placeTimer.reset()
        }
    }

    private fun SafeClientEvent.breakCrystal(entityID: Int) {
        connection.sendPacket(
            CPacketUseEntity().apply {
                packetAction = CPacketUseEntity.Action.ATTACK
                id = entityID
            }
        )
        connection.sendPacket(CPacketAnimation(EnumHand.MAIN_HAND))
        breakTimer.reset()
    }
}