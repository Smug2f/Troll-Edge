package me.luna.trollhack.module.modules.player

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.events.player.PlayerTravelEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.event.safeParallelListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.process.PauseProcess.pauseBaritone
import me.luna.trollhack.process.PauseProcess.unpauseBaritone
import me.luna.trollhack.setting.settings.impl.collection.MapSetting
import me.luna.trollhack.util.TimeUnit
import me.luna.trollhack.util.atTrue
import me.luna.trollhack.util.extension.fastCeil
import me.luna.trollhack.util.inventory.InventoryTask
import me.luna.trollhack.util.inventory.confirmedOrTrue
import me.luna.trollhack.util.inventory.inventoryTask
import me.luna.trollhack.util.inventory.operation.moveTo
import me.luna.trollhack.util.inventory.operation.quickMove
import me.luna.trollhack.util.inventory.operation.swapWith
import me.luna.trollhack.util.inventory.operation.throwAll
import me.luna.trollhack.util.inventory.slot.*
import me.luna.trollhack.util.items.id
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

internal object InventoryManager : Module(
    name = "InventoryManager",
    category = Category.PLAYER,
    description = "Manages your inventory automatically",
    modulePriority = 10
) {
    private val defaultEjectList = Object2IntOpenHashMap<String>().apply {
        defaultReturnValue(-1)
        put("minecraft:grass", 0)
        put("minecraft:dirt", 0)
        put("minecraft:netherrack", 0)
        put("minecraft:gravel", 0)
        put("minecraft:sand", 0)
        put("minecraft:stone", 0)
        put("minecraft:cobblestone", 0)
    }

    private val autoRefill0 = setting("Auto Refill", true)
    private val autoRefill by autoRefill0
    private val buildingMode by setting("Building Mode", false, autoRefill0.atTrue())
    var buildingBlockID by setting("Building Block ID", 0, 0..1000, 1, { false })
    private val refillThreshold by setting("Refill Threshold", 16, 1..63, 1, autoRefill0.atTrue())
    private val itemSaver0 = setting("Item Saver", false)
    private val itemSaver by itemSaver0
    private val duraThreshold by setting("Durability Threshold", 5, 1..50, 1, itemSaver0.atTrue())
    private val autoEject0 = setting("Auto Eject", false)
    private val autoEject by autoEject0
    private val fullOnly by setting("Only At Full", false, autoEject0.atTrue())
    private val pauseMovement by setting("Pause Movement", true)
    private val delay by setting("Delay Ticks", 1, 0..20, 1)
    val ejectMap = setting(MapSetting("Eject Map", defaultEjectList))

    enum class State {
        IDLE, SAVING_ITEM, REFILLING_BUILDING, REFILLING, EJECTING
    }

    private var currentState = State.IDLE
    private var lastTask: InventoryTask? = null

    override fun isActive(): Boolean {
        return isEnabled && currentState != State.IDLE
    }

    init {
        onDisable {
            lastTask = null
            unpauseBaritone()
        }

        safeListener<PlayerTravelEvent> {
            if (player.isSpectator || !pauseMovement) return@safeListener

            // Pause if it is not null and not confirmed
            val shouldPause = lastTask?.confirmed == false

            if (shouldPause) {
                player.setVelocity(0.0, mc.player.motionY, 0.0)
                it.cancel()
                pauseBaritone()
            } else {
                unpauseBaritone()
            }
        }

        safeParallelListener<TickEvent.Post> {
            if (player.isSpectator || mc.currentScreen is GuiContainer || !lastTask.confirmedOrTrue) return@safeParallelListener

            setState()

            lastTask = when (currentState) {
                State.SAVING_ITEM -> {
                    saveItem()
                }
                State.REFILLING_BUILDING -> {
                    refillBuilding()
                }
                State.REFILLING -> {
                    refill()
                }
                State.EJECTING -> {
                    eject()
                }
                State.IDLE -> {
                    null
                }
            }
        }
    }

    private fun SafeClientEvent.setState() {
        currentState = when {
            saveItemCheck() -> State.SAVING_ITEM
            refillBuildingCheck() -> State.REFILLING_BUILDING
            refillCheck() -> State.REFILLING
            ejectCheck() -> State.EJECTING
            else -> State.IDLE
        }
    }

    /* State checks */
    private fun SafeClientEvent.saveItemCheck(): Boolean {
        return itemSaver && checkDamage(player.heldItemMainhand)
    }

    private fun SafeClientEvent.refillBuildingCheck(): Boolean {
        if (!autoRefill || !buildingMode || buildingBlockID == 0) return false

        val totalCount = player.inventorySlots.countID(buildingBlockID)
        val hotbarCount = player.hotbarSlots.countID(buildingBlockID)

        return totalCount >= refillThreshold
            && (hotbarCount < refillThreshold
            || (getRefillableSlotBuilding() != null && currentState == State.REFILLING_BUILDING))
    }

    private fun SafeClientEvent.refillCheck(): Boolean {
        return autoRefill && getRefillableSlot() != null
    }

    private fun SafeClientEvent.ejectCheck(): Boolean {
        return autoEject && ejectMap.value.isNotEmpty()
            && (!fullOnly || player.inventorySlots.firstEmpty() == null)
            && getEjectSlot() != null
    }
    /* End of state checks */

    /* Tasks */
    private fun SafeClientEvent.saveItem(): InventoryTask? {
        val currentSlot = player.currentHotbarSlot
        val itemStack = player.heldItemMainhand

        val undamagedItem = getUndamagedItem(itemStack.item.id)
        val emptySlot = player.inventorySlots.firstEmpty()

        return when {
            autoRefill && undamagedItem != null -> {
                inventoryTask {
                    postDelay(delay, TimeUnit.TICKS)
                    swapWith(undamagedItem, currentSlot)
                }
            }
            emptySlot != null -> {
                inventoryTask {
                    postDelay(delay, TimeUnit.TICKS)
                    swapWith(emptySlot, currentSlot)
                }
            }
            else -> {
                player.dropItem(false)
                null
            }
        }
    }

    private fun SafeClientEvent.refillBuilding() =
        player.storageSlots.firstID(buildingBlockID)?.let {
            inventoryTask {
                postDelay(delay, TimeUnit.TICKS)
                quickMove(it)
            }
        }

    private fun SafeClientEvent.refill() =
        getRefillableSlot()?.let { slotTo ->
            getCompatibleStack(slotTo.stack)?.let { slotFrom ->
                inventoryTask {
                    postDelay(delay, TimeUnit.TICKS)
                    moveTo(slotFrom, slotTo)
                }
            }
        }

    private fun SafeClientEvent.eject() =
        getEjectSlot()?.let {
            inventoryTask {
                postDelay(delay, TimeUnit.TICKS)
                throwAll(it)
            }
        }
    /* End of tasks */

    /**
     * Finds undamaged item with given ID in inventory, and return its slot
     *
     * @return Full inventory slot if undamaged item found, else return null
     */
    private fun SafeClientEvent.getUndamagedItem(itemID: Int) =
        player.storageSlots.firstID(itemID) {
            !checkDamage(it)
        }

    private fun checkDamage(itemStack: ItemStack) =
        itemStack.isItemStackDamageable
            && itemStack.itemDamage > itemStack.maxDamage * (1.0f - duraThreshold / 100.0f)

    private fun SafeClientEvent.getRefillableSlotBuilding(): Slot? {
        if (player.storageSlots.firstID(buildingBlockID) == null) return null

        return player.hotbarSlots.firstID(buildingBlockID) {
            it.isStackable && it.count < it.maxStackSize
        }
    }

    private fun SafeClientEvent.getRefillableSlot(): Slot? {
        val slots = player.hotbarSlots + player.offhandSlot
        return slots.firstByStack {
            !it.isEmpty
                && (!buildingMode || it.item.id != buildingBlockID)
                && (!autoEject || !ejectMap.value.containsKey(it.item.registryName.toString()))
                && it.isStackable
                && it.count < (it.maxStackSize / 64.0f * refillThreshold).fastCeil()
                && getCompatibleStack(it) != null
        }
    }

    private fun SafeClientEvent.getCompatibleStack(stack: ItemStack): Slot? {
        return (player.craftingSlots + player.storageSlots).firstByStack {
            stack.isItemEqual(it) && ItemStack.areItemStackTagsEqual(stack, it)
        }
    }

    private fun SafeClientEvent.getEjectSlot(): Slot? {
        val countMap = Object2IntOpenHashMap<Item>()
        countMap.defaultReturnValue(0)

        for (slot in player.inventoryContainer.inventorySlots) {
            val stack = slot.stack
            val item = stack.item

            if (stack.isEmpty) continue
            if (buildingMode && item.id == buildingBlockID) continue

            val ejectThreshold = ejectMap.value[item.registryName.toString()] ?: continue
            countMap.put(item, countMap.getInt(item) + 1)
            if (countMap.getInt(item) > ejectThreshold) {
                return slot
            }
        }

        return null
    }
}