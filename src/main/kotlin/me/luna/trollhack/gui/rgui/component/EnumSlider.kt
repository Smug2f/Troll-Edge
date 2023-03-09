package me.luna.trollhack.gui.rgui.component

import me.luna.trollhack.module.modules.client.GuiSetting
import me.luna.trollhack.setting.settings.impl.primitive.EnumSetting
import me.luna.trollhack.util.extension.readableName
import me.luna.trollhack.util.graphics.font.renderer.MainFontRenderer
import me.luna.trollhack.util.math.vector.Vec2f
import kotlin.math.floor

class EnumSlider(val setting: EnumSetting<*>) : Slider(setting.name, setting.description, setting.visibility) {
    private val enumValues = setting.enumValues

    override var progress = 0.0f
        get() {
            if (mouseState == MouseState.DRAG) {
                return field
            }

            val settingValue = setting.value.ordinal
            return if (roundInput(renderProgress.current) != settingValue) {
                field = (settingValue + settingValue / (enumValues.size - 1.0f)) / enumValues.size.toFloat()
                field
            } else {
                Float.NaN
            }
        }

    override fun onRelease(mousePos: Vec2f, buttonId: Int) {
        super.onRelease(mousePos, buttonId)
        if (prevState != MouseState.DRAG) setting.nextValue()
    }

    override fun onDrag(mousePos: Vec2f, clickPos: Vec2f, buttonId: Int) {
        super.onDrag(mousePos, clickPos, buttonId)
        updateValue(mousePos)
    }

    private fun updateValue(mousePos: Vec2f) {
        progress = (mousePos.x / width).coerceIn(0.0f, 1.0f)
        setting.setValue(enumValues[roundInput(progress)].name)
    }

    private fun roundInput(input: Float) = floor(input * enumValues.size).toInt().coerceIn(0, enumValues.size - 1)

    override fun onRender(absolutePos: Vec2f) {
        val valueText = setting.value.readableName()
        protectedWidth = MainFontRenderer.getWidth(valueText, 0.75f)

        super.onRender(absolutePos)
        val posX = renderWidth - protectedWidth - 2.0f
        val posY = renderHeight - 2.0f - MainFontRenderer.getHeight(0.75f)
        MainFontRenderer.drawString(valueText, posX, posY, GuiSetting.text, 0.75f)
    }
}