package me.luna.trollhack.module.modules.combat

import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.PacketEvent
import me.luna.trollhack.event.events.RunGameLoopEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.events.WorldEvent
import me.luna.trollhack.event.events.combat.CrystalSpawnEvent
import me.luna.trollhack.event.events.render.Render3DEvent
import me.luna.trollhack.event.listener
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.manager.managers.CombatManager
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.module.modules.player.PacketMine
import me.luna.trollhack.util.EntityUtils.betterPosition
import me.luna.trollhack.util.EntityUtils.eyePosition
import me.luna.trollhack.util.TickTimer
import me.luna.trollhack.util.accessor.id
import me.luna.trollhack.util.accessor.packetAction
import me.luna.trollhack.util.combat.CrystalUtils
import me.luna.trollhack.util.combat.CrystalUtils.hasValidSpaceForCrystal
import me.luna.trollhack.util.graphics.ESPRenderer
import me.luna.trollhack.util.graphics.color.ColorRGB
import me.luna.trollhack.util.inventory.equipBestTool
import me.luna.trollhack.util.inventory.inventoryTaskNow
import me.luna.trollhack.util.inventory.operation.action
import me.luna.trollhack.util.inventory.operation.pickUp
import me.luna.trollhack.util.inventory.removeHoldingItem
import me.luna.trollhack.util.inventory.slot.firstBlock
import me.luna.trollhack.util.inventory.slot.firstItem
import me.luna.trollhack.util.inventory.slot.offhandSlot
import me.luna.trollhack.util.inventory.slot.storageSlots
import me.luna.trollhack.util.math.vector.toVec3d
import me.luna.trollhack.util.world.*
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.client.CPacketAnimation
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.client.CPacketUseEntity
import net.minecraft.network.play.server.SPacketSoundEffect
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos

@Suppress("NOTHING_TO_INLINE")
@CombatManager.CombatModule
internal object CevBreaker : Module(
    name = "CevBreaker",
    description = "Troll module",
    category = Category.COMBAT,
    modulePriority = 250
) {
    private val minHealth by setting("Min Health", 8.0f, 0.0f..20.0f, 0.5f)
    private val placeDelay by setting("Place Delay", 500, 0..1000, 1)
    private val breakDelay by setting("Break Delay", 100, 0..1000, 1)
    private val range by setting("Range", 5.0f, 0.0f..6.0f, 0.25f)

    private val renderer = ESPRenderer().apply { aFilled = 31; aOutline = 233 }
    private val placeTimer = TickTimer()
    private val breakTimer = TickTimer()
    private val packetTimer = TickTimer()
    private var posInfo: Info? = null
    private var crystalID = -69420

    init {
        onEnable {
            PacketMine.enable()
        }

        onDisable {
            reset()
        }

        listener<Render3DEvent> {
            posInfo?.let {
                renderer.render(false)
            }
        }

        safeListener<WorldEvent.ServerBlockUpdate> { event ->
            val info = posInfo ?: return@safeListener

            if (event.pos == info.pos) {
                val current = event.oldState.block
                val new = event.newState.block

                if (new != current && new == Blocks.AIR) {
                    val id = crystalID
                    if (id != -69420 && safeCheck()) {
                        breakCrystal(id)
                    }
                }
            }
        }

        safeListener<PacketEvent.Receive> { event ->
            if (event.packet !is SPacketSoundEffect) return@safeListener
            val info = posInfo ?: return@safeListener

            if (event.packet.category == SoundCategory.BLOCKS
                && event.packet.sound == SoundEvents.ENTITY_GENERIC_EXPLODE
                && info.pos.distanceSqToCenter(event.packet.x, event.packet.y - 0.5, event.packet.z) < 0.25) {
                crystalID = -69420
            }
        }

        safeListener<CrystalSpawnEvent> {
            val info = posInfo ?: return@safeListener
            if (it.crystalDamage.blockPos == info.pos || CrystalUtils.getCrystalBB(it.crystalDamage.blockPos).intersects(info.placeBB)) {
                crystalID = it.entityID
            }
        }

        safeListener<TickEvent.Pre> {
            if (!safeCheck()) {
                posInfo = null
                return@safeListener
            }

            updateTarget()
            posInfo?.let {
                if (player.getDistanceSqToCenter(it.pos) > range * range) {
                    reset()
                } else {
                    equipBestTool(world.getBlockState(it.pos))
                }
            }
        }

        safeListener<RunGameLoopEvent.Tick> {
            if (!safeCheck()) {
                return@safeListener
            }

            val info = posInfo ?: return@safeListener
            val blockState = world.getBlockState(info.pos)

            if (blockState.block == Blocks.AIR) {
                var id = crystalID
                if (id == -69420) {
                    CombatManager.crystalList
                        .find { it.first.entityBoundingBox.intersects(info.placeBB) }
                        ?.let { id = it.first.entityId }
                }

                if (id != -69420) {
                    if (breakTimer.tickAndReset(breakDelay)) breakCrystal(id)
                } else {
                    if (placeTimer.tickAndReset(placeDelay)) place(info)
                }
            }
        }
    }

    private fun SafeClientEvent.safeCheck(): Boolean {
        return player.health >= minHealth
            && AutoOffhand.lastType != AutoOffhand.Type.TOTEM
    }

    private fun SafeClientEvent.updateTarget() {
        CombatManager.target?.let {
            val feetPos = it.betterPosition
            if (world.getBlockState(feetPos).getCollisionBoundingBox(world, feetPos) != null) {
                reset()
                return
            }

            val pos = BlockPos(it.posX, it.posY + 2.5, it.posZ)
            if (pos != posInfo?.pos) {
                if (player.getDistanceSqToCenter(pos) <= range * range
                    && world.canBreakBlock(pos)
                    && hasValidSpaceForCrystal(pos)
                    && wallCheck(pos)) {
                    val side = getMiningSide(pos) ?: EnumFacing.UP

                    reset()
                    posInfo = Info(pos, side)
                    renderer.clear()
                    renderer.add(AxisAlignedBB(pos), ColorRGB(255, 255, 255))
                    packetTimer.reset(-69420)

                    PacketMine.mineBlock(CevBreaker, pos, CevBreaker.modulePriority)
                    player.swingArm(EnumHand.MAIN_HAND)
                }
            }
        } ?: run {
            reset()
        }
    }

    private fun SafeClientEvent.wallCheck(pos: BlockPos): Boolean {
        val eyePos = player.eyePosition
        return eyePos.squareDistanceTo(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5) <= 9.0
            || world.rayTraceBlocks(eyePos, pos.toVec3d(0.5, 2.7, 0.5), false, true, false) == null
    }

    private fun SafeClientEvent.place(info: Info) {
        val obbySlot = player.storageSlots.firstBlock(Blocks.OBSIDIAN) ?: return
        val crystalSlot = player.storageSlots.firstItem(Items.END_CRYSTAL) ?: return
        val placeInfo = getNeighbor(info.pos, 3, 6.0f, sides = arrayOf(*EnumFacing.HORIZONTALS, EnumFacing.DOWN))
            ?: return

        inventoryTaskNow {
            pickUp(obbySlot)
            pickUp(player.offhandSlot)

            action {
                placeBlock(placeInfo, EnumHand.OFF_HAND)
            }

            pickUp(crystalSlot)
            pickUp(player.offhandSlot)
            pickUp(obbySlot)

            action {
                connection.sendPacket(CPacketPlayerTryUseItemOnBlock(info.pos, info.side, EnumHand.OFF_HAND, info.hitVecOffset.x, info.hitVecOffset.y, info.hitVecOffset.z))
                connection.sendPacket(CPacketAnimation(EnumHand.OFF_HAND))
            }

            runInGui()
            postDelay(100L)
        }

        removeHoldingItem()
    }

    private fun SafeClientEvent.breakCrystal(id: Int) {
        val packet = CPacketUseEntity().apply {
            this.id = id
            this.packetAction = CPacketUseEntity.Action.ATTACK
        }
        connection.sendPacket(packet)
        connection.sendPacket(CPacketAnimation(EnumHand.OFF_HAND))
    }

    private fun reset() {
        placeTimer.reset(-69420)
        breakTimer.reset(-69420)
        packetTimer.reset(-69420)
        posInfo = null
        crystalID = -69420
        PacketMine.reset(this)
    }

    private class Info(
        val pos: BlockPos,
        val side: EnumFacing,
    ) {
        val placeBB = AxisAlignedBB(
            pos.x - 1.0, pos.y + 0.0, pos.z - 1.0,
            pos.x + 2.0, pos.y + 3.0, pos.z + 2.0
        )
        val hitVecOffset = getHitVecOffset(side)
    }
}