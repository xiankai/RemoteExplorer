package com.bespectacledcyborg.remoteexplorer.data

/** The listing's orders, each with the label a sort menu gives it. */
enum class SortType(
    val label: String,
    /** Whether the order reads what has been viewed (#76), so the totals need fetching. */
    val usesViews: Boolean = false,
) {
    NAME_ASC("Name, A to Z"),
    NAME_DESC("Name, Z to A"),
    LAST_MODIFIED_ASC("Oldest first"),
    LAST_MODIFIED_DESC("Newest first"),

    /** Popularity: most opens first, the most recently opened breaking a tie. */
    MOST_VIEWED("Most viewed", usesViews = true),

    /** Novelty: fewest opens first, and never opened (0) before anything. */
    LEAST_VIEWED("Least viewed", usesViews = true),

    /** Most recently opened first; never opened last. */
    RECENTLY_VIEWED("Recently viewed", usesViews = true),

    /** Return frequency: opened on the most distinct viewing days first. */
    MOST_DAYS_VIEWED("Viewed on most days", usesViews = true),

    /** Most time spent playing first. */
    MOST_WATCHED("Longest watched", usesViews = true),
}
