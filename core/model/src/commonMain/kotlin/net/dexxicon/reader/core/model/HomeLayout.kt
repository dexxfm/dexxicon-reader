package net.dexxicon.reader.core.model

/** One of the Home screen's shelves (issue #255). Declaration order is the default order. */
enum class HomeSection { CONTINUE_READING, CONTINUE_LISTENING, ON_DECK, DOWNLOADED }

/**
 * The user's arrangement of Home's shelves (issue #255): every [HomeSection] exactly once, in
 * display order, with [hidden] ones skipped.
 */
data class HomeLayout(
    val order: List<HomeSection> = HomeSection.entries,
    val hidden: Set<HomeSection> = emptySet(),
) {
    /** What Home actually draws, top to bottom. */
    val visible: List<HomeSection> get() = order.filter { it !in hidden }

    fun moved(from: Int, to: Int): HomeLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun withVisibility(section: HomeSection, visible: Boolean): HomeLayout =
        copy(hidden = if (visible) hidden - section else hidden + section)

    /** `CONTINUE_READING,!ON_DECK,…` — a leading `!` marks a hidden section. */
    fun encode(): String = order.joinToString(",") { if (it in hidden) "!${it.name}" else it.name }

    companion object {
        /**
         * Tolerant of anything a stored value could hold: unknown or repeated names are
         * dropped, and sections missing from it (e.g. one added in a later app version) are
         * appended, visible, so a new shelf is never silently lost to an old saved layout.
         */
        fun decode(raw: String?): HomeLayout {
            if (raw.isNullOrBlank()) return HomeLayout()
            val order = mutableListOf<HomeSection>()
            val hidden = mutableSetOf<HomeSection>()
            raw.split(',').map { it.trim() }.forEach { token ->
                val isHidden = token.startsWith("!")
                val section = HomeSection.entries.firstOrNull { it.name == token.removePrefix("!") }
                if (section != null && section !in order) {
                    order += section
                    if (isHidden) hidden += section
                }
            }
            HomeSection.entries.filter { it !in order }.forEach { order += it }
            return HomeLayout(order, hidden)
        }
    }
}
