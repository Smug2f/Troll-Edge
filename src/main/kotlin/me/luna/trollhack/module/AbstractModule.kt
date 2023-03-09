package me.luna.trollhack.module

import me.luna.trollhack.event.ListenerOwner
import me.luna.trollhack.event.events.ModuleToggleEvent
import me.luna.trollhack.setting.configs.NameableConfig
import me.luna.trollhack.setting.settings.AbstractSetting
import me.luna.trollhack.setting.settings.SettingRegister
import me.luna.trollhack.setting.settings.impl.other.BindSetting
import me.luna.trollhack.setting.settings.impl.primitive.BooleanSetting
import me.luna.trollhack.setting.settings.impl.primitive.EnumSetting
import me.luna.trollhack.translation.ITranslateSrc
import me.luna.trollhack.translation.TranslateSrc
import me.luna.trollhack.translation.TranslateType
import me.luna.trollhack.util.Bind
import me.luna.trollhack.util.IDRegistry
import me.luna.trollhack.util.interfaces.Alias
import me.luna.trollhack.util.interfaces.DisplayEnum
import me.luna.trollhack.util.interfaces.Nameable
import me.luna.trollhack.util.text.MessageSendUtils
import net.minecraft.client.Minecraft

@Suppress("UNCHECKED_CAST")
open class AbstractModule(
    name: String,
    override val alias: Array<String> = emptyArray(),
    val category: Category,
    description: String,
    val modulePriority: Int = -1,
    var alwaysListening: Boolean = false,
    visible: Boolean = true,
    val alwaysEnabled: Boolean = false,
    val enabledByDefault: Boolean = false,
    private val config: NameableConfig<out Nameable>
) : ListenerOwner(), Nameable, Alias, SettingRegister<Nameable>, ITranslateSrc by TranslateSrc(name), Comparable<AbstractModule> {

    override val name = TranslateType.SPECIFIC key ("name" to name)
    val description = TranslateType.SPECIFIC key ("description" to description)

    val id = idRegistry.register()

    private val enabled = BooleanSetting(settingName("Enabled"), false, { false }).also(::addSetting)
    val bind = BindSetting(settingName("Bind"), Bind(), { !alwaysEnabled }, {
        when (onHold.value) {
            OnHold.OFF -> if (it) toggle()
            OnHold.ENABLE -> toggle(it)
            OnHold.DISABLE -> toggle(!it)
        }
    }).also(::addSetting)
    private val onHold = EnumSetting(settingName("On Hold"), OnHold.OFF).also(::addSetting)
    private val visible = BooleanSetting(settingName("Visible"), visible).also(::addSetting)
    private val default = BooleanSetting(settingName("Default"), false, { settingList.isNotEmpty() }).also(::addSetting)

    private enum class OnHold(override val displayName: CharSequence) : DisplayEnum {
        OFF(TranslateType.COMMON commonKey "Off"),
        ENABLE(TranslateType.COMMON commonKey "Enable"),
        DISABLE(TranslateType.COMMON commonKey "Disable")
    }

    val fullSettingList get() = config.getSettings(this)
    val settingList: List<AbstractSetting<*>> get() = fullSettingList.filter { it != bind && it != enabled && it != enabled && it != visible && it != default }

    val isEnabled: Boolean get() = enabled.value || alwaysEnabled
    val isDisabled: Boolean get() = !isEnabled
    val chatName: String get() = "[${name}]"
    val isVisible: Boolean get() = visible.value

    private fun addSetting(setting: AbstractSetting<*>) {
        (config as NameableConfig<Nameable>).addSettingToConfig(this, setting)
    }

    internal fun postInit() {
        enabled.value = enabledByDefault || alwaysEnabled
        if (alwaysListening) {
            subscribe()
        }
    }

    fun toggle(state: Boolean) {
        enabled.value = state
    }

    fun toggle() {
        enabled.value = !enabled.value
    }

    fun enable() {
        enabled.value = true
    }

    fun disable() {
        enabled.value = false
    }

    open fun isActive(): Boolean {
        return isEnabled || alwaysListening
    }

    open fun getHudInfo(): String {
        return ""
    }

    protected fun onEnable(block: (Boolean) -> Unit) {
        enabled.valueListeners.add { _, input ->
            if (input) {
                block(input)
            }
        }
    }

    protected fun onDisable(block: (Boolean) -> Unit) {
        enabled.valueListeners.add { _, input ->
            if (!input) {
                block(input)
            }
        }
    }

    protected fun onToggle(block: (Boolean) -> Unit) {
        enabled.valueListeners.add { _, input ->
            block(input)
        }
    }

    override fun <S : AbstractSetting<*>> Nameable.setting(setting: S): S {
        (config as NameableConfig<Nameable>).addSettingToConfig(this, setting)
        return setting
    }

    final override fun settingName(input: CharSequence): CharSequence {
        return if (input is String) TranslateType.COMMON key input
        else input
    }

    override fun compareTo(other: AbstractModule): Int {
        val result = this.modulePriority.compareTo(other.modulePriority)
        if (result != 0) return result
        return this.id.compareTo(other.id)
    }

    init {
        enabled.consumers.add { prev, input ->
            val enabled = alwaysEnabled || input

            if (prev != input && !alwaysEnabled) {
                ModuleToggleEvent(this).post()
            }

            if (enabled || alwaysListening) {
                subscribe()
            } else {
                unsubscribe()
            }

            enabled
        }

        default.valueListeners.add { _, it ->
            if (it) {
                settingList.forEach { it.resetValue() }
                default.value = false
                MessageSendUtils.sendNoSpamChatMessage("$chatName $defaultMessage!")
            }
        }
    }

    protected companion object : ITranslateSrc by TranslateSrc("Module") {
        val defaultMessage = TranslateType.LONG key ("setToDefault" to "Set to defaults")

        private val idRegistry = IDRegistry()
        val mc: Minecraft = Minecraft.getMinecraft()
    }
}
