package com.AlchemistSlime.profanitycensor

/**
 * Поиск мата в тексте. Не зависит от Bukkit/Paper, поэтому легко тестируется отдельно.
 *
 * Текст режется на «слова» по пробелам. Каждое слово нормализуется несколькими способами
 * (замена похожих латинских букв на кириллические и наоборот, цифры-«леетспик», транслит,
 * выкидывание разделителей вроде «х.у.й», схлопывание повторов «хууууй») и проверяется
 * набором регулярок. Если слово признано матом — цензурируется слово целиком.
 */
class ProfanityFilter(
    extraWords: Collection<String> = emptyList(),
    whitelistWords: Collection<String> = emptyList(),
) {

    /** Полуоткрытый диапазон [start, end) в исходной строке. */
    data class Span(val start: Int, val end: Int)

    private enum class Mode { RU, EN }

    private class Custom(
        private val ruForm: String,
        private val enForm: String,
        private val leading: Boolean,
        private val trailing: Boolean,
    ) {
        fun matches(ru: String, en: String): Boolean = test(ruForm, ru) || test(enForm, en)

        private fun test(form: String, word: String): Boolean = when {
            form.isEmpty() -> false
            leading && trailing -> word.contains(form)
            trailing -> word.startsWith(form)
            leading -> word.endsWith(form)
            else -> word == form
        }
    }

    private val extra = ArrayList<Custom>()
    private val whitelist = HashSet<String>()

    init {
        // extraWords: «слово» — точное совпадение, «слово*» — начинается с,
        // «*слово» — заканчивается на, «*слово*» — содержит.
        for (raw in extraWords) {
            val w = raw.trim()
            val core = w.replace("*", "")
            if (core.isEmpty()) continue
            extra += Custom(
                normalize(core, Mode.RU),
                normalize(core, Mode.EN),
                leading = w.startsWith("*"),
                trailing = w.endsWith("*"),
            )
        }
        for (raw in whitelistWords) {
            if (raw.isBlank()) continue
            whitelist += normalize(raw, Mode.RU)
            whitelist += normalize(raw, Mode.EN)
        }
    }

    /** Слово без окружающей пунктуации: [start, end) в исходной строке. */
    private class Token(val start: Int, val end: Int, val raw: String)

    private enum class Variant { RU, EN, TRANSLIT }

    /**
     * Возвращает диапазоны слов-матов в порядке следования (без пересечений).
     *
     * Кроме отдельных слов ловит мат, разбитый пробелами: «х у й», «п и з д е ц», «ху йня»
     * и «яша лава» (= «я шалава»).
     */
    fun find(text: String): List<Span> {
        val tokens = tokenize(text)
        val result = ArrayList<Span>()

        for (t in tokens) {
            if (isBad(t.raw)) result += Span(t.start, t.end)
        }
        for (i in 0 until tokens.size - 1) {
            findSpacedLetters(tokens, i)?.let { result += it }
            findSplitRoot(tokens, i)?.let { result += it }
        }
        return merge(result)
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            val from = i
            while (i < n && !text[i].isWhitespace()) i++
            var a = from
            while (a < i && !isCore(text[a])) a++
            var b = i
            while (b > a && !isCore(text[b - 1])) b--
            if (a < b && b - a <= 128) tokens += Token(a, b, text.substring(a, b))
        }
        return tokens
    }

    /** Склеивает цепочки коротких слов (≤ 3 букв): «х у й», «п из да», «f u c k». */
    private fun findSpacedLetters(tokens: List<Token>, i: Int): Span? {
        if (tokens[i].raw.length > MAX_SHORT) return null
        var j = i + 1
        while (j < tokens.size && j - i < MAX_WINDOW && tokens[j].raw.length <= MAX_SHORT) {
            for (variant in Variant.entries) {
                val patterns = when (variant) {
                    Variant.RU -> RU
                    Variant.EN -> EN
                    Variant.TRANSLIT -> RU_TRANSLIT
                }
                crossingMatch(tokens, i, j, variant, patterns)?.let { return it }
            }
            j++
        }
        return null
    }

    /** Корни, которые нельзя случайно получить на стыке слов: «яша лава» -> «шалава». */
    private fun findSplitRoot(tokens: List<Token>, i: Int): Span? {
        var j = i + 1
        var total = tokens[i].raw.length
        while (j < tokens.size && j - i < 3) {
            total += tokens[j].raw.length
            if (total > 20) break
            crossingMatch(tokens, i, j, Variant.RU, STRONG_ROOTS)?.let { return it }
            j++
        }
        return null
    }

    /**
     * Склеивает нормализованные слова tokens[i.j] и ищет совпадение, которое пересекает
     * границу между словами. Возвращает диапазон только тех слов, которых совпадение касается.
     */
    private fun crossingMatch(tokens: List<Token>, i: Int, j: Int, variant: Variant, patterns: List<Regex>): Span? {
        if (variant == Variant.TRANSLIT && (i..j).any { hasCyrillic(tokens[it].raw) }) return null

        val parts = (i..j).map { k ->
            when (variant) {
                Variant.RU -> normalize(tokens[k].raw, Mode.RU)
                Variant.EN -> normalize(tokens[k].raw, Mode.EN)
                Variant.TRANSLIT -> translit(tokens[k].raw)
            }
        }
        val joined = StringBuilder()
        val bounds = ArrayList<Int>()
        for ((k, p) in parts.withIndex()) {
            joined.append(p)
            if (k < parts.size - 1) bounds += joined.length
        }
        val s = joined.toString()
        if (s.isEmpty()) return null

        for (re in patterns) {
            for (m in re.findAll(s)) {
                val a = m.range.first
                val b = m.range.last + 1
                if (bounds.none { it in (a + 1) until b }) continue

                var first = -1
                var last = -1
                var offset = 0
                for ((k, p) in parts.withIndex()) {
                    val ps = offset
                    offset += p.length
                    if (p.isNotEmpty() && offset > a && ps < b) {
                        if (first < 0) first = k
                        last = k
                    }
                }
                if (first >= 0) return Span(tokens[i + first].start, tokens[i + last].end)
            }
        }
        return null
    }

    private fun merge(spans: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedBy { it.start }
        val out = ArrayList<Span>()
        var cur = sorted[0]
        for (k in 1 until sorted.size) {
            val s = sorted[k]
            cur = if (s.start < cur.end) Span(cur.start, maxOf(cur.end, s.end)) else {
                out += cur
                s
            }
        }
        out += cur
        return out
    }

    private fun isBad(raw: String): Boolean {
        val ru = normalize(raw, Mode.RU)
        val en = normalize(raw, Mode.EN)

        if (ru in whitelist || en in whitelist) return false
        if (matchAny(RU, ru) || matchAny(EN, en)) return true
        if (!hasCyrillic(raw) && matchAny(RU_TRANSLIT, translit(raw))) return true
        return extra.any { it.matches(ru, en) }
    }

    private companion object {
        // ------------------------------------------------------------ русские паттерны
        // Применяются к нормализованному слову (нижний регистр, ё -> е, повторы схлопнуты в одну букву).
        const val EB_PREFIX =
            "(?:съ|ъ|за|на|по|вы|до|от|пере|при|с|у|об|раз|рас|под|про|вз|недо|пред|долбо|мудо|гондо|хуе|дебило|пиздо)?"

        val RU: List<Regex> = regexes(
            // хуй и производные
            "ху[йяюеи]",
            // пизда и производные
            "п[иеы]зд",
            // ебать и производные (с приставками, чтобы не ловить «небо», «хлеб», «себя», «ребёнок»)
            "^" + EB_PREFIX + "еб(?:а[нтлшр]|у$|у[нчт]|л[аоя]|н[уеи]|е[тшм]|и[тшмс]|ыр|ок$|ищ)",
            // долбоёб, мудоёб и т.п.
            "^(?:долбо|мудо|гондо|дебило|пиздо|хуе)еб",
            // бля
            "^бл(?:я(?:$|д|т)|е[ая]т|эт)",
            // сука (но не «сукно», «сучок», «сучья»)
            "^су(?:ка|ки|ке|ку|кой|кин|чка|чки|чке|чку|чара|чар)",
            // мудак
            "^муд(?:ак|ач|ил|озв|ла$|ло$|ень)",
            // гандон
            "^г[ао]нд[ао]н",
            // залупа
            "залуп",
            // пидор / педик / педераст (но не «педикюр»)
            "^(?:пидор|пидар|пидр|педик(?:$|и|а|у|ом|ов)|педераст)",
            // шлюха / шалава
            "^(?:шлюх|шалав)",
            // дрочить
            "^(?:на|по|вы)?дроч",
            // говно
            "^г[ао]вн(?:о|а|у|е|ю|я|ом|юк|ар|ян|иш)",
            // ублюдок
            "^(?:ублюд|выблядок)",
            // манда
            "^манд(?:а|ой|ою|у|ы|е|ов)$|^мандавош",
        )

        /** Подмножество паттернов для транслита (xuy, blyat, pizdec...), без рискованных вроде «hue». */
        val RU_TRANSLIT: List<Regex> = regexes(
            "ху(?:й|я|ев|ел|ил|ит)",
            "п[иеы]зд",
            "^бл(?:я(?:$|д|т)|е[ая]т|эт)",
            "^су(?:ка|чка|чара)",
            "^(?:на|по|вы|за|у|об|от|съ)?еба[нтл]",
            "^муд(?:ак|ач|ил)",
            "^г[ао]нд[ао]н",
            "^пид(?:ор|ар|р)",
            "залуп",
        )

        // --------------------------------------------------------- английские паттерны
        // Применяются к слову, где повторы схлопнуты до максимум двух одинаковых букв подряд.
        val EN: List<Regex> = regexes(
            "f+u+c+k",
            "^f+u+k+(?:er|ers|ing|ed|s)?$",
            "^f+c+k+(?:er|ers|ing|ed|s)?$",
            "^(?:bull|dip|horse|dumb|holy)?sh+i+t+(?:s|y|ty|ter|ting|head|face|hole|show)?$",
            "b+i+t+c+h",
            "^c+u+n+t+s?$",
            "^(?:dumb|jack)?a+s+h+o+l+e+s?$",
            "^d+i+c+k+(?:s|head|face)?$",
            "^p+u+s+y+$|^pussies$",
            "^wh+o+r+e+s?$",
            "^s+l+u+t+s?$",
            "^n+i+g{2,}(?:er|a|ah)s?$",
            "^f+a+g+o+t+s?$",
        )

        /** Для мата, разбитого пробелами между длинными словами: только то, что почти не бывает «случайным». */
        val STRONG_ROOTS: List<Regex> = regexes(
            "шалав", "шлюх", "п[иеы]зд", "мудак", "залуп", "г[ао]нд[ао]н",
            "пидор", "пидар", "ублюд", "бляд",
        )

        const val MAX_SHORT = 3
        const val MAX_WINDOW = 5

        fun regexes(vararg patterns: String): List<Regex> = patterns.map { Regex(it) }

        fun matchAny(patterns: List<Regex>, s: String): Boolean =
            s.isNotEmpty() && patterns.any { it.containsMatchIn(s) }

        fun isCore(c: Char): Boolean = c.isLetterOrDigit() || c == '@' || c == '$'

        fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }

        /**
         * Нормализация слова. Разделители (точки, звёздочки, подчёркивания и т.п.) выкидываются,
         * похожие символы приводятся к одному алфавиту, повторы схлопываются.
         */
        fun normalize(word: String, mode: Mode): String {
            val sb = StringBuilder(word.length)
            val maxRun = if (mode == Mode.RU) 1 else 2
            var run = 0
            var prev = '\u0000'
            for (ch in word) {
                val c = mapChar(ch.lowercaseChar(), mode)
                if (c == '\u0000') continue
                if (c == prev) {
                    run++
                    if (run > maxRun) continue
                } else {
                    run = 1
                    prev = c
                }
                sb.append(c)
            }
            return sb.toString()
        }

        fun mapChar(c: Char, mode: Mode): Char {
            if (mode == Mode.RU) {
                val mapped = when (c) {
                    'ё' -> 'е'
                    'a' -> 'а'
                    'e' -> 'е'
                    'o' -> 'о'
                    'p' -> 'р'
                    'c' -> 'с'
                    'x' -> 'х'
                    'y' -> 'у'
                    'k' -> 'к'
                    'm' -> 'м'
                    't' -> 'т'
                    'h' -> 'н'
                    'u' -> 'у'
                    'i' -> 'и'
                    '0' -> 'о'
                    else -> c
                }
                return if (mapped.isLetterOrDigit()) mapped else '\u0000'
            }
            val mapped = when (c) {
                'а' -> 'a'
                'в' -> 'b'
                'е', 'ё' -> 'e'
                'к' -> 'k'
                'м' -> 'm'
                'н' -> 'h'
                'о' -> 'o'
                'р' -> 'p'
                'с' -> 'c'
                'т' -> 't'
                'у' -> 'y'
                'х' -> 'x'
                'і' -> 'i'
                '0' -> 'o'
                '1', '!' -> 'i'
                '3' -> 'e'
                '4', '@' -> 'a'
                '5', '$' -> 's'
                '7' -> 't'
                else -> c
            }
            return if (mapped.isLetterOrDigit()) mapped else '\u0000'
        }

        /** Латиница -> кириллица по звучанию: xuy -> хуй, blyat -> блят, pizdec -> пиздец. */
        fun translit(word: String): String {
            val s = buildString { for (ch in word) { val c = ch.lowercaseChar(); if (c in 'a'..'z') append(c) } }
            val out = StringBuilder()
            var prev = '\u0000'
            var i = 0
            while (i < s.length) {
                val mapped: Char
                var len = 1
                if (s.startsWith("shch", i)) {
                    mapped = 'щ'
                    len = 4
                } else {
                    val d = if (i + 1 < s.length) digraph(s[i], s[i + 1]) else '\u0000'
                    if (d != '\u0000') {
                        mapped = d
                        len = 2
                    } else {
                        mapped = single(s[i])
                    }
                }
                i += len
                if (mapped != prev) {
                    out.append(mapped)
                    prev = mapped
                }
            }
            return out.toString()
        }

        fun digraph(a: Char, b: Char): Char = when ("$a$b") {
            "yu" -> 'ю'
            "ya" -> 'я'
            "ye", "yo" -> 'е'
            "ts" -> 'ц'
            "ch" -> 'ч'
            "sh" -> 'ш'
            "zh" -> 'ж'
            else -> '\u0000'
        }

        fun single(c: Char): Char = when (c) {
            'a' -> 'а'
            'b' -> 'б'
            'c' -> 'ц'
            'd' -> 'д'
            'e' -> 'е'
            'f' -> 'ф'
            'g' -> 'г'
            'h' -> 'х'
            'i' -> 'и'
            'j', 'y' -> 'й'
            'k', 'q' -> 'к'
            'l' -> 'л'
            'm' -> 'м'
            'n' -> 'н'
            'o' -> 'о'
            'p' -> 'п'
            'r' -> 'р'
            's' -> 'с'
            't' -> 'т'
            'u' -> 'у'
            'v', 'w' -> 'в'
            'x' -> 'х'
            'z' -> 'з'
            else -> c
        }
    }
}
