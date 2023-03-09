package me.luna.trollhack.module.modules.combat

import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.gui.mc.TrollGuiDisconnected
import me.luna.trollhack.manager.managers.CombatManager
import me.luna.trollhack.manager.managers.FriendManager
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.module.modules.combat.AutoLog.Reasons.*
import me.luna.trollhack.util.EntityUtils.isFakeOrSelf
import me.luna.trollhack.util.atTrue
import me.luna.trollhack.util.combat.CombatUtils.scaledHealth
import me.luna.trollhack.util.inventory.slot.allSlots
import me.luna.trollhack.util.inventory.slot.countItem
import me.luna.trollhack.util.math.MathUtils
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.entity.monster.EntityCreeper
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.util.text.TextComponentString
import java.time.LocalTime

internal object AutoLog : Module(
    name = "AutoLog",
    description = "Automatically log when in danger or on low health",
    category = Category.COMBAT,
    alwaysListening = true
) {
    private val disableMode by setting("Disable Mode", DisableMode.ALWAYS)
    private val health by setting("Health", 10, 6..36, 1)
    private val crystals by setting("Crystals", false)
    private val creeper0 = setting("Creepers", true)
    private val creeper by creeper0
    private val creeperDistance by setting("Creeper Distance", 5, 1..10, 1, creeper0.atTrue())
    private val totem0 = setting("Totem", false)
    private val totem by totem0
    private val minTotems by setting("Min Totems", 2, 1..10, 1, totem0.atTrue())
    private val players0 = setting("Players", false)
    private val players by players0
    private val playerDistance by setting("Player Distance", 64, 32..128, 4, players0.atTrue())
    private val friends by setting("Friends", false, players0.atTrue())

    @Suppress("UNUSED")
    private enum class DisableMode {
        NEVER, ALWAYS, NOT_PLAYER
    }

    init {
        safeListener<TickEvent.Post>(-1000) {
            if (isDisabled) return@safeListener

            when {
                player.scaledHealth < health -> log(HEALTH)
                totem && checkTotems() -> log(TOTEM)
                crystals && checkCrystals() -> log(END_CRYSTAL)
                creeper && checkCreeper() -> {
                    /* checkCreeper() does log() */
                }
                players && checkPlayers() -> {
                    /* checkPlayer() does log() */
                }
            }
        }
    }

    private fun SafeClientEvent.checkTotems(): Boolean {
        val slots = player.allSlots
        return slots.any { it.hasStack }
            && slots.countItem(Items.TOTEM_OF_UNDYING) < minTotems
    }

    private fun SafeClientEvent.checkCrystals(): Boolean {
        val maxSelfDamage = CombatManager.crystalMap.values.maxOfOrNull { it.selfDamage } ?: 0.0f
        return player.scaledHealth - maxSelfDamage < health
    }

    private fun SafeClientEvent.checkCreeper(): Boolean {
        for (entity in world.loadedEntityList) {
            if (entity !is EntityCreeper) continue
            if (player.getDistance(entity) > creeperDistance) continue
            log(CREEPER, MathUtils.round(entity.getDistance(player), 2).toString())
            return true
        }
        return false
    }

    private fun SafeClientEvent.checkPlayers(): Boolean {
        for (entity in world.loadedEntityList) {
            if (entity !is EntityPlayer) continue
            if (AntiBot.isBot(entity)) continue
            if (entity.isFakeOrSelf) continue
            if (player.getDistance(entity) > playerDistance) continue
            if (!friends && FriendManager.isFriend(entity.name)) continue
            log(PLAYER, entity.name)
            return true
        }
        return false
    }

    private fun SafeClientEvent.log(reason: Reasons, additionalInfo: String = "") {
        val reasonText = getReason(reason, additionalInfo)
        val screen = getScreen() // do this before disconnecting

        mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
        connection.networkManager.closeChannel(TextComponentString(""))
        mc.loadWorld(null as WorldClient?)

        mc.displayGuiScreen(TrollGuiDisconnected(reasonText, screen, disableMode == DisableMode.ALWAYS || (disableMode == DisableMode.NOT_PLAYER && reason != PLAYER), LocalTime.now()))
    }

    private fun getScreen() = if (mc.isIntegratedServerRunning) {
        GuiMainMenu()
    } else {
        GuiMultiplayer(GuiMainMenu())
    }

    private fun getReason(reason: Reasons, additionalInfo: String) = when (reason) {
        HEALTH -> arrayOf("Health went below ${health}!")
        TOTEM -> arrayOf("Less then ${totemMessage(minTotems)}!")
        CREEPER -> arrayOf("Creeper came near you!", "It was $additionalInfo blocks away")
        PLAYER -> arrayOf("Player $additionalInfo came within $playerDistance blocks range!")
        END_CRYSTAL -> arrayOf("An end crystal was placed too close to you!", "It would have done more then $health damage!")
    }

    private enum class Reasons {
        HEALTH, TOTEM, CREEPER, PLAYER, END_CRYSTAL
    }

    private fun totemMessage(amount: Int) = if (amount == 1) "one totem" else "$amount totems"
}