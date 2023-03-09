package me.luna.trollhack.module.modules.player

import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.RunGameLoopEvent
import me.luna.trollhack.event.safeConcurrentListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.collections.IntBitSet
import me.luna.trollhack.util.inventory.InventoryTask
import me.luna.trollhack.util.inventory.executedOrTrue
import me.luna.trollhack.util.inventory.inventoryTask
import me.luna.trollhack.util.inventory.operation.pickUp
import me.luna.trollhack.util.inventory.slot.inventorySlots
import me.luna.trollhack.util.text.NoSpamMessage
import me.luna.trollhack.util.threads.runSafe
import net.minecraft.init.Items
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

internal object InventorySorter : Module(
    name = "InventorySorter",
    category = Category.PLAYER,
    description = "Sort out items in inventory",
    modulePriority = 20
) {
    private val clickDelay by setting("Click Delay", 10, 0..1000, 1)
    private val postDelay by setting("Post Delay", 50, 0..1000, 1)

    private val checkSet = IntBitSet()
    private var itemArray: Array<Item>? = null
    private var lastTask: InventoryTask? = null
    private var lastIndex = 35

    override fun getHudInfo(): String {
        return Kit.kitName
    }

    init {
        onEnable {
            runSafe {
                itemArray = Kit.getKitItemArray() ?: run {
                    NoSpamMessage.sendError(InventorySorter, "No kit named ${Kit.kitName} was not found!")
                    disable()
                    return@onEnable
                }
            } ?: disable()
        }

        onDisable {
            itemArray = null
            lastTask?.cancel()
            lastTask = null
            lastIndex = 35
            checkSet.clear()
        }

        safeConcurrentListener<RunGameLoopEvent.Tick> {
            val itemArray = itemArray
            if (itemArray == null) {
                disable()
                return@safeConcurrentListener
            }

            if (!lastTask.executedOrTrue) return@safeConcurrentListener
            if (lastIndex == 0) {
                NoSpamMessage.sendMessage(InventorySorter, "Finished sorting!")
                disable()
                return@safeConcurrentListener
            }

            runSorting(itemArray)
        }
    }

    private fun SafeClientEvent.runSorting(itemArray: Array<Item>) {
        val slots = player.inventorySlots
        for (index in 35 downTo 0) {
            lastIndex = index
            if (checkSet.contains(index)) continue

            val item = itemArray[index]
            if (item == Items.AIR) continue
            val slotTo = slots[index]
            val itemStack = slotTo.stack

            if (itemStack.item != item || (itemStack.isStackable && itemStack.count < itemStack.maxStackSize)) {
                val slot = slots.getCompatibleStack(slotTo, item, itemStack)
                if (slot != null) {
                    lastTask = moveItem(slot, slotTo, itemStack)
                    return
                } else if (itemStack.item == item) {
                    checkSet.add(index)
                }
            } else {
                checkSet.add(index)
            }
        }
    }

    private fun List<Slot>.getCompatibleStack(slotTo: Slot, itemTo: Item, stackTo: ItemStack): Slot? {
        var maxSlot: Slot? = null
        var maxSize = 0

        val isEmpty = stackTo.isEmpty
        val neededSize = if (isEmpty) 64 else stackTo.maxStackSize - stackTo.count

        for ((index, slotFrom) in this.withIndex()) {
            if (index + 9 == slotTo.slotNumber) continue
            if (checkSet.contains(index)) continue

            val stackFrom = slotFrom.stack
            if (stackFrom.item != itemTo) continue

            val size = stackFrom.count
            if (!isEmpty && stackTo.item == itemTo) {
                if (!stackTo.isItemEqual(stackFrom)) continue
                if (!ItemStack.areItemStackTagsEqual(stackTo, stackFrom)) continue
                if (size == neededSize) return slotFrom
            }

            if (size == stackFrom.maxStackSize) {
                return slotFrom
            } else if (size > maxSize) {
                maxSlot = slotFrom
                maxSize = size
            }
        }

        return maxSlot
    }

    private fun moveItem(slotFrom: Slot, slotTo: Slot, itemStack: ItemStack): InventoryTask {
        val sizeTo = itemStack.count
        val sizeFrom = slotFrom.stack.count

        return if (sizeTo == 0 || itemStack.maxStackSize - sizeTo >= sizeFrom) {
            inventoryTask {
                pickUp(slotFrom)
                pickUp(slotTo)
                runInGui()
                delay(clickDelay)
                postDelay(postDelay)
            }
        } else {
            inventoryTask {
                pickUp(slotFrom)
                pickUp(slotTo)
                pickUp(slotFrom)
                runInGui()
                delay(clickDelay)
                postDelay(postDelay)
            }
        }
    }

}
