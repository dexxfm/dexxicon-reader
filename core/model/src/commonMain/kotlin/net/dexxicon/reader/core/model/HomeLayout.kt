package net.dexxicon.reader.core.model

/** One of the Home screen's built-in shelves (issue #255). Declaration order is the default order. */
enum class HomeSection { CONTINUE_READING, CONTINUE_LISTENING, ON_DECK, DOWNLOADED }

/** Anything Home can show as a shelf: a built-in one, or a group the user pinned (issue #254). */
sealed interface HomeShelf {
    /** Stable identity — what [HomeLayout.hidden] and reordering track. */
    val key: String

    data class Section(val section: HomeSection) : HomeShelf {
        override val key: String get() = section.name
    }

    /** A library, collection or smart shelf pinned from the Library (issue #254). */
    data class Pinned(val group: BookGroup) : HomeShelf {
        override val key: String get() = PIN_PREFIX + group.key
    }

    companion object {
        internal const val PIN_PREFIX = "PIN:"
    }
}

/**
 * The user's arrangement of Home's shelves (issues #255, #254): every built-in [HomeSection]
 * exactly once plus any pinned groups, in display order, with [hidden] ones (by
 * [HomeShelf.key]) skipped.
 */
data class HomeLayout(
    val order: List<HomeShelf> = HomeSection.entries.map { HomeShelf.Section(it) },
    val hidden: Set<String> = emptySet(),
) {
    /** What Home actually draws, top to bottom. */
    val visible: List<HomeShelf> get() = order.filter { it.key !in hidden }

    val pinned: List<BookGroup> get() = order.filterIsInstance<HomeShelf.Pinned>().map { it.group }

    fun isPinned(group: BookGroup): Boolean = order.any { it.key == HomeShelf.Pinned(group).key }

    fun moved(from: Int, to: Int): HomeLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun withVisibility(key: String, visible: Boolean): HomeLayout =
        copy(hidden = if (visible) hidden - key else hidden + key)

    fun withVisibility(section: HomeSection, visible: Boolean): HomeLayout =
        withVisibility(section.name, visible)

    /** Pins [group] as the last shelf; a no-op when it's already pinned. */
    fun pinned(group: BookGroup): HomeLayout =
        if (isPinned(group)) this else copy(order = order + HomeShelf.Pinned(group))

    /** Drops pins whose server is no longer configured — a removed server's shelves have
     *  nothing left to show and nowhere to load from. Everything else is untouched. */
    fun forServers(serverIds: Set<String>): HomeLayout {
        val stale = order.filter { it is HomeShelf.Pinned && it.group.serverId !in serverIds }
        if (stale.isEmpty()) return this
        val staleKeys = stale.map { it.key }.toSet()
        return copy(order = order - stale.toSet(), hidden = hidden - staleKeys)
    }

    fun unpinned(group: BookGroup): HomeLayout {
        val key = HomeShelf.Pinned(group).key
        return copy(order = order.filterNot { it.key == key }, hidden = hidden - key)
    }

    /**
     * Comma-separated, a leading `!` marks a hidden shelf: `CONTINUE_READING,!ON_DECK,…`.
     * A pinned group is `PIN|serverId|KIND|id|name`, each field escaped (see [escape]) so a
     * name holding `,` or `|` round-trips intact.
     */
    fun encode(): String = order.joinToString(",") { shelf ->
        val token = when (shelf) {
            is HomeShelf.Section -> shelf.section.name
            is HomeShelf.Pinned -> with(shelf.group) {
                listOf("PIN", serverId, kind.name, id, name).joinToString("|") { escape(it) }
            }
        }
        if (shelf.key in hidden) "!$token" else token
    }

    companion object {
        /**
         * Tolerant of anything a stored value could hold: unknown, malformed or repeated entries
         * are dropped, and built-in sections missing from it (e.g. one added in a later app
         * version) are appended, visible, so a new shelf is never silently lost to an old
         * saved layout.
         */
        fun decode(raw: String?): HomeLayout {
            if (raw.isNullOrBlank()) return HomeLayout()
            val order = mutableListOf<HomeShelf>()
            val hidden = mutableSetOf<String>()
            raw.split(',').map { it.trim() }.forEach { token ->
                val isHidden = token.startsWith("!")
                val shelf = parse(token.removePrefix("!")) ?: return@forEach
                if (order.none { it.key == shelf.key }) {
                    order += shelf
                    if (isHidden) hidden += shelf.key
                }
            }
            HomeSection.entries
                .filter { section -> order.none { it.key == section.name } }
                .forEach { order += HomeShelf.Section(it) }
            return HomeLayout(order, hidden)
        }

        private fun parse(token: String): HomeShelf? {
            if (!token.startsWith("PIN|")) {
                return HomeSection.entries.firstOrNull { it.name == token }?.let { HomeShelf.Section(it) }
            }
            val parts = token.split('|').map { unescape(it) }
            if (parts.size != 5) return null
            val kind = BookGroupKind.entries.firstOrNull { it.name == parts[2] } ?: return null
            if (parts[1].isEmpty() || parts[3].isEmpty()) return null
            return HomeShelf.Pinned(BookGroup(serverId = parts[1], kind = kind, id = parts[3], name = parts[4]))
        }

        private fun escape(value: String): String =
            value.replace("%", "%25").replace(",", "%2C").replace("|", "%7C")

        private fun unescape(value: String): String =
            value.replace("%7C", "|").replace("%2C", ",").replace("%25", "%")
    }
}
