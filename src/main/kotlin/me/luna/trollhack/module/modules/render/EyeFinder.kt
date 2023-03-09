package me.luna.trollhack.module.modules.render

import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.events.render.Render3DEvent
import me.luna.trollhack.event.listener
import me.luna.trollhack.event.safeParallelListener
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.EntityUtils.eyePosition
import me.luna.trollhack.util.EntityUtils.getInterpolatedAmount
import me.luna.trollhack.util.EntityUtils.getTargetList
import me.luna.trollhack.util.and
import me.luna.trollhack.util.atTrue
import me.luna.trollhack.util.atValue
import me.luna.trollhack.util.graphics.ESPRenderer
import me.luna.trollhack.util.graphics.RenderUtils3D
import me.luna.trollhack.util.graphics.color.ColorRGB
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.Entity
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.RayTraceResult
import org.lwjgl.opengl.GL11.GL_LINES
import kotlin.math.min

internal object EyeFinder : Module(
    name = "EyeFinder",
    description = "Draw lines from entity's heads to where they are looking",
    category = Category.RENDER
) {
    private val page = setting("Page", Page.ENTITY_TYPE)

    /* Entity type settings */
    private val players = setting("Players", true, page.atValue(Page.ENTITY_TYPE))
    private val friends = setting("Friends", false, page.atValue(Page.RENDERING) and players.atTrue())
    private val sleeping = setting("Sleeping", false, page.atValue(Page.RENDERING) and players.atTrue())
    private val mobs = setting("Mobs", true, page.atValue(Page.ENTITY_TYPE))
    private val passive = setting("Passive Mobs", false, page.atValue(Page.RENDERING) and mobs.atTrue())
    private val neutral = setting("Neutral Mobs", true, page.atValue(Page.RENDERING) and mobs.atTrue())
    private val hostile = setting("Hostile Mobs", true, page.atValue(Page.RENDERING) and mobs.atTrue())
    private val invisible = setting("Invisible", true, page.atValue(Page.ENTITY_TYPE))
    private val range = setting("Range", 64, 8..128, 8, page.atValue(Page.ENTITY_TYPE))

    /* Rendering settings */
    private val r = setting("Red", 255, 0..255, 1, page.atValue(Page.RENDERING))
    private val g = setting("Green", 255, 0..255, 1, page.atValue(Page.RENDERING))
    private val b = setting("Blue", 255, 0..255, 1, page.atValue(Page.RENDERING))
    private val a = setting("Alpha", 200, 0..255, 1, page.atValue(Page.RENDERING))
    private val thickness = setting("Thickness", 2.0f, 0.25f..5.0f, 0.25f, page.atValue(Page.RENDERING))

    private enum class Page {
        ENTITY_TYPE, RENDERING
    }

    private var resultMap = emptyMap<Entity, Pair<RayTraceResult, Float>>()
    private val renderer = ESPRenderer().apply {
        aFilled = 85
        aOutline = 255
        through = true
    }

    init {
        listener<Render3DEvent> {
            if (resultMap.isEmpty()) return@listener
            renderer.thickness = thickness.value

            for ((entity, pair) in resultMap) {
                drawLine(entity, pair)
            }

            GlStateManager.glLineWidth(thickness.value)
            RenderUtils3D.draw(GL_LINES)
            renderer.render(true)
        }

        safeParallelListener<TickEvent.Post> {
            alwaysListening = resultMap.isNotEmpty()

            val player = arrayOf(players.value, friends.value, sleeping.value)
            val mob = arrayOf(mobs.value, passive.value, neutral.value, hostile.value)
            val entityList = if (isEnabled) {
                getTargetList(player, mob, invisible.value, range.value.toFloat(), ignoreSelf = false)
            } else {
                emptyList()
            }

            val newMap = HashMap<Entity, Pair<RayTraceResult, Float>>()
            for (entity in entityList) {
                val result = getRaytraceResult(entity) ?: continue
                newMap[entity] = Pair(result, 0.0f)
            }
            for ((entity, pair) in resultMap) {
                val result = getRaytraceResult(entity) ?: continue
                newMap.computeIfPresent(entity) { _, cachePair -> Pair(cachePair.first, min(pair.second + 0.07f, 1f)) }
                newMap.computeIfAbsent(entity) { Pair(result, pair.second - 0.05f) }
                if (pair.second < 0f) newMap.remove(entity)
            }

            resultMap = newMap
        }
    }

    private fun SafeClientEvent.getRaytraceResult(entity: Entity): RayTraceResult? {
        val eyePos = entity.getPositionEyes(RenderUtils3D.partialTicks)
        val entityLookVec = entity.getLook(RenderUtils3D.partialTicks).scale(5.0)
        val entityLookEnd = eyePos.add(entityLookVec)

        var result = world.rayTraceBlocks(entity.eyePosition, entityLookEnd, false, false, true)
            ?: return null

        if (result.typeOfHit == RayTraceResult.Type.MISS) {
            for (otherEntity in world.loadedEntityList) {
                if (otherEntity.getDistance(entity) > 10.0) continue
                if (otherEntity == entity || otherEntity == mc.renderViewEntity) continue
                val box = otherEntity.entityBoundingBox
                result = box.calculateIntercept(eyePos, entityLookEnd) ?: continue
                result.typeOfHit = RayTraceResult.Type.ENTITY
                result.entityHit = otherEntity
            }
        }
        return result
    }

    private fun drawLine(entity: Entity, pair: Pair<RayTraceResult, Float>) {
        val eyePos = entity.getPositionEyes(RenderUtils3D.partialTicks)
        val result = pair.first
        val color = ColorRGB(r.value, g.value, b.value, (a.value * pair.second).toInt())

        /* Render line */
        RenderUtils3D.putVertex(eyePos.x, eyePos.y, eyePos.z, color)
        RenderUtils3D.putVertex(result.hitVec.x, result.hitVec.y, result.hitVec.z, color)

        /* Render hit position */
        if (result.typeOfHit != RayTraceResult.Type.MISS) {
            val box = if (result.typeOfHit == RayTraceResult.Type.BLOCK) {
                AxisAlignedBB(result.blockPos).grow(0.002)
            } else {
                val offset = getInterpolatedAmount(result.entityHit, RenderUtils3D.partialTicks)
                result.entityHit.renderBoundingBox.offset(offset)
            }
            renderer.add(box, color)
        }
    }
}