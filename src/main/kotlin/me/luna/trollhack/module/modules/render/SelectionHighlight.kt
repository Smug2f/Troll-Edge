package me.luna.trollhack.module.modules.render

import me.luna.trollhack.event.events.render.Render3DEvent
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.atTrue
import me.luna.trollhack.util.graphics.ESPRenderer
import me.luna.trollhack.util.graphics.RenderUtils3D
import me.luna.trollhack.util.graphics.color.ColorRGB
import me.luna.trollhack.util.graphics.mask.EnumFacingMask
import me.luna.trollhack.util.world.getSelectedBox
import net.minecraft.util.math.RayTraceResult.Type

internal object SelectionHighlight : Module(
    name = "SelectionHighlight",
    description = "Highlights object you are looking at",
    category = Category.RENDER
) {
    private val entity by setting("Entity", true)
    private val hitSideOnly by setting("Hit Side Only", false)
    private val depth by setting("Depth", false)
    private val filled = setting("Filled", true)
    private val outline = setting("Outline", true)
    private val color by setting("Color", ColorRGB(255, 255, 255))
    private val aFilled by setting("Filled Alpha", 63, 0..255, 1, filled.atTrue())
    private val aOutline by setting("Outline Alpha", 200, 0..255, 1, outline.atTrue())
    private val width by setting("Width", 2.0f, 0.25f..5.0f, 0.25f)

    private val renderer = ESPRenderer()

    init {
        safeListener<Render3DEvent> {
            val hitObject = mc.objectMouseOver ?: return@safeListener

            when (hitObject.typeOfHit) {
                Type.ENTITY -> {
                    if (entity) {
                        val viewEntity = mc.renderViewEntity ?: player
                        val eyePos = viewEntity.getPositionEyes(RenderUtils3D.partialTicks)
                        val entity = hitObject.entityHit ?: return@safeListener
                        val lookVec = viewEntity.lookVec
                        val sightEnd = eyePos.add(lookVec.scale(6.0))
                        val hitSide = entity.entityBoundingBox.calculateIntercept(eyePos, sightEnd)?.sideHit
                            ?: return@safeListener
                        val side = if (hitSideOnly) EnumFacingMask.getMaskForSide(hitSide) else EnumFacingMask.ALL
                        renderer.add(hitObject.entityHit, color, side)
                    }
                }
                Type.BLOCK -> {
                    val box = world.getSelectedBox(hitObject.blockPos)
                    val side = if (hitSideOnly) EnumFacingMask.getMaskForSide(hitObject.sideHit) else EnumFacingMask.ALL
                    renderer.add(box.grow(0.002), color, side)
                }
                else -> {
                }
            }

            renderer.aFilled = if (filled.value) aFilled else 0
            renderer.aOutline = if (outline.value) aOutline else 0
            renderer.through = !depth
            renderer.thickness = width
            renderer.render(true)
        }
    }
}