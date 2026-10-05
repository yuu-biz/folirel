package io.github.yuubiz.folirel.model

/**
 * What the reader chose for one category, kept by the category's id (so a rename keeps it): where its tab stands, whether it is
 * pinned, and the three display settings it overrides (P1-ORG / P1-LAYOUT). A category without a row has the defaults: no
 * saved place, not pinned, every display setting inherited.
 *
 * [tabOrder] is one rank among all categories (null: no saved place, the path order applies). The pinned flag does not change
 * it, so unpinning gives the category its normal place back. The three overrides are null = inherit the global setting.
 * [cardColumns] 0 is a choice of its own (automatic), different from null (inherit).
 */
data class CategoryPreference(
    val categoryId: String,
    val tabOrder: Int? = null,
    val isPinned: Boolean = false,
    val listLayout: ListMode? = null,
    val cardStyle: CardLayoutStyle? = null,
    val cardColumns: Int? = null,
) {
    val overridesLayout: Boolean get() = listLayout != null || cardStyle != null || cardColumns != null
}

/** Display settings of one list after the category's overrides were applied to the global ones. */
data class ResolvedLayout(
    val listMode: ListMode,
    val cardStyle: CardLayoutStyle,
    /** 0 = automatic, otherwise the most columns wanted (the width and text size can still reduce them). */
    val cardColumns: Int,
)

object CategoryDisplay {
    /**
     * Which settings the list of [scope] uses. Only a category has its own: All, Uncategorized and a single feed use [global], a
     * parent category uses its own row (a child does not inherit from it), and a feed that belongs to two categories is shown
     * with the settings of the category that is open, not of any other.
     */
    fun resolve(scope: ArticleScope, global: ResolvedLayout, preferences: Map<String, CategoryPreference>): ResolvedLayout {
        val own = (scope as? ArticleScope.Category)?.let { preferences[it.categoryId] } ?: return global
        return ResolvedLayout(
            listMode = own.listLayout ?: global.listMode,
            cardStyle = own.cardStyle ?: global.cardStyle,
            cardColumns = own.cardColumns ?: global.cardColumns,
        )
    }
}

/**
 * The order of the category tabs (P1-ORG), one function for the tabs, the pager, the picker and the organizer. It is a flat order:
 * the category tree (paths, memberships) is never touched by it.
 *
 * All categories in the *normal* order: those with a saved place by that place, the others after them in path order (a new
 * category lands at the end; nothing already placed moves). The *tab* order puts the pinned categories first, each group in the
 * normal order.
 */
object TabOrder {
    /** [categories] must be in path order (as [Structure] keeps them); ties and unplaced categories keep that order. */
    fun normal(categories: List<CategoryNode>, preferences: Map<String, CategoryPreference>): List<CategoryNode> =
        categories.sortedBy { preferences[it.id]?.tabOrder ?: Int.MAX_VALUE }

    fun resolve(categories: List<CategoryNode>, preferences: Map<String, CategoryPreference>): List<CategoryNode> {
        val normal = normal(categories, preferences)
        return normal.filter { preferences[it.id]?.isPinned == true } + normal.filter { preferences[it.id]?.isPinned != true }
    }

    /** [group] with [id] moved by [delta] places (clamped to the ends); null when nothing changes. */
    fun move(group: List<String>, id: String, delta: Int): List<String>? {
        val from = group.indexOf(id)
        if (from < 0) return null
        val to = (from + delta).coerceIn(0, group.lastIndex)
        if (to == from) return null
        return group.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** [group] with [id] put just before [beforeId] (at the end when [beforeId] is null or not in the group); null when nothing changes. */
    fun moveBefore(group: List<String>, id: String, beforeId: String?): List<String>? {
        if (id !in group) return null
        val rest = group.filter { it != id }
        val at = beforeId?.let { rest.indexOf(it) }?.takeIf { it >= 0 } ?: rest.size
        val moved = rest.toMutableList().also { it.add(at, id) }
        return moved.takeIf { it != group }
    }

    /**
     * The normal order (ids) after the members of one group (pinned or not) were put in [wanted] order: they take the places the
     * group's members had, the other group does not move. Ids that are not in the group are ignored; members missing from
     * [wanted] (new meanwhile) follow in their old order.
     */
    fun reorderGroup(
        categories: List<CategoryNode>,
        preferences: Map<String, CategoryPreference>,
        pinned: Boolean,
        wanted: List<String>,
    ): List<String> {
        val normal = normal(categories, preferences).map { it.id }
        val members = normal.filter { (preferences[it]?.isPinned == true) == pinned }
        val memberSet = members.toSet()
        val given = wanted.filter { it in memberSet }.distinct()
        val order = given + members.filter { it !in given }
        var next = 0
        return normal.map { id -> if (id in memberSet) order[next++] else id }
    }
}
