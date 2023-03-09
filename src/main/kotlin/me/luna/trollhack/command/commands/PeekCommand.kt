package me.luna.trollhack.command.commands

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.luna.trollhack.command.ClientCommand
import me.luna.trollhack.util.text.MessageSendUtils
import me.luna.trollhack.util.threads.defaultScope
import me.luna.trollhack.util.threads.onMainThreadSafeSuspend
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.gui.inventory.GuiShulkerBox
import net.minecraft.item.ItemShulkerBox
import net.minecraft.tileentity.TileEntityShulkerBox

object PeekCommand : ClientCommand(
    name = "peek",
    alias = arrayOf("shulkerpeek"),
    description = "Look inside the contents of a shulker box without opening it."
) {
    init {
        executeSafe {
            val itemStack = player.inventory.getCurrentItem()
            val item = itemStack.item

            if (item is ItemShulkerBox) {
                val entityBox = TileEntityShulkerBox().apply {
                    this.world = this@executeSafe.world
                }

                val nbtTag = itemStack.tagCompound ?: return@executeSafe
                entityBox.readFromNBT(nbtTag.getCompoundTag("BlockEntityTag"))

                val scaledResolution = ScaledResolution(mc)
                val gui = GuiShulkerBox(player.inventory, entityBox)
                gui.setWorldAndResolution(mc, scaledResolution.scaledWidth, scaledResolution.scaledHeight)

                defaultScope.launch {
                    delay(50L)
                    onMainThreadSafeSuspend {
                        mc.displayGuiScreen(gui)
                    }
                }
            } else {
                MessageSendUtils.sendNoSpamErrorMessage("You aren't holding a shulker box.")
            }
        }
    }
}