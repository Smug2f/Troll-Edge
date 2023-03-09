package me.luna.trollhack.module.modules.player

import me.luna.trollhack.event.events.player.PlayerMoveEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.world.getGroundLevel
import net.minecraft.util.math.Vec3d

internal object AntiVoid : Module(
    name = "AntiVoid",
    description = "AntiVoid for hypixel",
    category = Category.PLAYER
) {
    private val fallDistance by setting("Fall Distance", 3.0f, 1.0f..10.0f, 0.5f)
    private val yOffset by setting("Y Offset", 0.2f, 0.0f..3.0f, 0.1f)

    private var lastPos: Vec3d? = null

    init {
        onDisable {
            lastPos = null
        }

        safeListener<PlayerMoveEvent.Post> {
            if (world.getGroundLevel(player) != -1.0) {
                if (player.onGround) {
                    lastPos = player.positionVector
                }
            } else if (player.fallDistance > fallDistance) {
                lastPos?.let {
                    player.setPosition(it.x, it.y + yOffset, it.z)
                    player.motionX = 0.0
                    player.motionY = 0.0
                    player.motionZ = 0.0
                }
            }
        }
    }
}