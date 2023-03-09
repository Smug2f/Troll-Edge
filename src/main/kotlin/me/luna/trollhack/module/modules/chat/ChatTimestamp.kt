package me.luna.trollhack.module.modules.chat

import me.luna.trollhack.event.events.PacketEvent
import me.luna.trollhack.event.listener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.TimeUtils
import me.luna.trollhack.util.accessor.textComponent
import me.luna.trollhack.util.graphics.color.EnumTextColor
import me.luna.trollhack.util.interfaces.DisplayEnum
import me.luna.trollhack.util.text.format
import net.minecraft.network.play.server.SPacketChat
import net.minecraft.util.text.TextComponentString

internal object ChatTimestamp : Module(
    name = "ChatTimestamp",
    category = Category.CHAT,
    description = "Shows the time a message was sent beside the message",
    visible = false
) {
    private val color by setting("Color", EnumTextColor.GRAY)
    private val separator by setting("Separator", Separator.ARROWS)
    private val timeFormat by setting("Time Format", TimeUtils.TimeFormat.HHMM)
    private val timeUnit by setting("Time Unit", TimeUtils.TimeUnit.H12)

    init {
        listener<PacketEvent.Receive> {
            if (it.packet is SPacketChat) {
                it.packet.textComponent = TextComponentString(formattedTime).appendSibling(it.packet.textComponent)
            }
        }
    }

    val formattedTime: String
        get() = "${separator.left}${color format TimeUtils.getTime(timeFormat, timeUnit)}${separator.right} "

    val time: String
        get() = "${separator.left}${TimeUtils.getTime(timeFormat, timeUnit)}${separator.right} "

    @Suppress("unused")
    private enum class Separator(override val displayName: CharSequence, val left: String, val right: String) :
        DisplayEnum {
        ARROWS("< >", "<", ">"),
        SQUARE_BRACKETS("[ ]", "[", "]"),
        CURLY_BRACKETS("{ }", "{", "}"),
        ROUND_BRACKETS("( )", "(", ")"),
        NONE("None", "", "")
    }
}
