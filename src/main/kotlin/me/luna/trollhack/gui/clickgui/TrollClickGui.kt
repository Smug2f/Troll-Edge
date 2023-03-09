package me.luna.trollhack.gui.clickgui

import me.luna.trollhack.gui.AbstractTrollGui
import me.luna.trollhack.gui.clickgui.component.ModuleButton
import me.luna.trollhack.gui.clickgui.window.ModuleSettingWindow
import me.luna.trollhack.gui.rgui.Component
import me.luna.trollhack.gui.rgui.windows.ListWindow
import me.luna.trollhack.module.AbstractModule
import me.luna.trollhack.module.ModuleManager
import me.luna.trollhack.module.modules.client.ClickGUI
import me.luna.trollhack.module.modules.client.GuiSetting
import me.luna.trollhack.util.extension.remove
import me.luna.trollhack.util.math.vector.Vec2f
import org.lwjgl.input.Keyboard

object TrollClickGui : AbstractTrollGui<ModuleSettingWindow, AbstractModule>() {

    private val moduleWindows = ArrayList<ListWindow>()

    init {
        val allButtons = ModuleManager.modules
            .groupBy { it.category.displayName }
            .mapValues { (_, modules) -> modules.map { ModuleButton(it) } }

        var posX = 0.0f
        var posY = 0.0f
        val screenWidth = mc.displayWidth / GuiSetting.scaleFactorFloat

        for ((category, buttons) in allButtons) {
            val window = ListWindow(category, posX, posY, 90.0f, 300.0f, Component.SettingGroup.CLICK_GUI)

            window.children.addAll(buttons)
            moduleWindows.add(window)
            posX += 90.0f

            if (posX > screenWidth) {
                posX = 0.0f
                posY += 100.0f
            }
        }

        windowList.addAll(moduleWindows)
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        setModuleButtonVisibility { true }
    }

    override fun newSettingWindow(element: AbstractModule, mousePos: Vec2f): ModuleSettingWindow {
        return ModuleSettingWindow(element, mousePos.x, mousePos.y)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == ClickGUI.bind.value.key && !searching && settingWindow?.listeningChild == null) {
            ClickGUI.disable()
        } else {
            super.keyTyped(typedChar, keyCode)

            val string = typedString.remove(' ')

            if (string.isNotEmpty()) {
                setModuleButtonVisibility { moduleButton ->
                    moduleButton.module.name.contains(string, true)
                        || moduleButton.module.alias.any { it.contains(string, true) }
                }
            } else {
                setModuleButtonVisibility { true }
            }
        }
    }

    private fun setModuleButtonVisibility(function: (ModuleButton) -> Boolean) {
        windowList.filterIsInstance<ListWindow>().forEach {
            for (child in it.children) {
                if (child !is ModuleButton) continue
                child.visible = function(child)
            }
        }
    }
}