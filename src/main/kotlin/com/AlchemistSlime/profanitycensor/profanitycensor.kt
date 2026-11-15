package com.AlchemistSlime.profanitycensor

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

/**
 * Alchemist's Profanity Censor — цензура мата в чате.
 *
 * Режимы (config.yml -> mode):
 *  - stars      — буквы мата заменяются на `*` (по одной звёздочке на букву);
 *  - obfuscated — «магический» текст (&k): символы мерцают. В пакет уходят только звёздочки со стилем
 *                 obfuscated, поэтому само слово игрокам не отправляется.
 */
class profanitycensor : JavaPlugin(), Listener, CommandExecutor, TabCompleter {

    private data class Settings(
        val enabled: Boolean = true,
        val obfuscated: Boolean = false,
        val warnSender: Boolean = false,
        val warnMessage: String = "",
        val hideModifiedNotice: Boolean = true,
        val filter: ProfanityFilter = ProfanityFilter(),
    )

    // AsyncChatEvent приходит из асинхронного потока, поэтому настройки меняем целиком и атомарно.
    @Volatile
    private var settings = Settings()

    override fun onEnable() {
        // Плагину нужен Paper (или его форк: Purpur, Folia и т.п.) — на чистом Spigot этого события нет.
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent")
        } catch (e: ClassNotFoundException) {
            logger.severe("Alchemist's Profanity Censor работает только на Paper и его форках (Purpur, Folia...). Плагин отключён.")
            server.pluginManager.disablePlugin(this)
            return
        }

        saveDefaultConfig()
        loadSettings()
        server.pluginManager.registerEvents(this, this)
        getCommand("profanitycensor")?.let {
            it.setExecutor(this)
            it.tabCompleter = this
        }
        logger.info("Alchemist's Profanity Censor включён (режим: ${if (settings.obfuscated) "obfuscated (&k)" else "stars (****)"})")
    }

    private fun loadSettings() {
        reloadConfig()
        val c = config
        val mode = (c.getString("mode") ?: "stars").trim().lowercase()
        settings = Settings(
            enabled = c.getBoolean("enabled", true),
            obfuscated = mode in OBFUSCATED_ALIASES,
            warnSender = c.getBoolean("warn-sender", false),
            warnMessage = c.getString("warn-message") ?: "",
            hideModifiedNotice = c.getBoolean("hide-modified-notice", true),
            filter = ProfanityFilter(c.getStringList("extra-words"), c.getStringList("whitelist")),
        )
    }

    // ------------------------------------------------------------------------------ чат

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val s = settings
        if (!s.enabled || event.player.hasPermission("profanitycensor.bypass")) return

        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        val spans = s.filter.find(text)
        if (spans.isEmpty()) return

        val censored = if (s.obfuscated) obfuscate(text, spans) else stars(text, spans)

        if (s.hideModifiedNotice) {
            // Подписанное сообщение с изменённым текстом клиент помечает «Сообщение изменено сервером».
            // Чтобы плашки не было, отменяем обычную отправку и рассылаем уже готовую (отрендеренную)
            // строку как системное сообщение — без подписи. Касается только зацензуренных сообщений.
            event.isCancelled = true
            val source = event.player
            for (viewer in event.viewers()) {
                viewer.sendMessage(event.renderer().render(source, source.displayName(), censored, viewer))
            }
        } else {
            event.message(censored)
        }

        if (s.warnSender && s.warnMessage.isNotEmpty()) {
            event.player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(s.warnMessage))
        }
    }

    /** «хуй» -> «***». Длина сохраняется. */
    private fun stars(text: String, spans: List<ProfanityFilter.Span>): Component {
        val sb = StringBuilder(text)
        for (span in spans) {
            for (i in span.start until span.end) sb.setCharAt(i, '*')
        }
        return Component.text(sb.toString())
    }

    /** «хуй» -> §k-текст из звёздочек. */
    private fun obfuscate(text: String, spans: List<ProfanityFilter.Span>): Component {
        var result: Component = Component.empty()
        var pos = 0
        for (span in spans) {
            if (span.start > pos) result = result.append(Component.text(text.substring(pos, span.start)))
            result = result.append(
                Component.text("*".repeat(span.end - span.start)).decorate(TextDecoration.OBFUSCATED)
            )
            pos = span.end
        }
        if (pos < text.length) result = result.append(Component.text(text.substring(pos)))
        return result
    }

    // ------------------------------------------------------------------------- команды

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            loadSettings()
            sender.sendMessage(Component.text("APC: настройки перезагружены."))
        } else {
            sender.sendMessage(Component.text("Использование: /$label reload"))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> =
        if (args.size == 1) listOf("reload").filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()

    private companion object {
        val OBFUSCATED_ALIASES = setOf("obfuscated", "obf", "magic", "k", "&k", "§k")
    }
}
