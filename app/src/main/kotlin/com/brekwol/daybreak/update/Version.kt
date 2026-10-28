package com.brekwol.daybreak.update

/**
 * A release version such as `0.2.0` or `0.2.0-rc.1`, ordered like semantic
 * versioning: `0.2.0-rc.1 < 0.2.0-rc.2 < 0.2.0 < 0.2.1`.
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated suffix parts, e.g. `["rc", "1"]`. Empty for a final release. */
    val preRelease: List<String> = emptyList(),
) : Comparable<Version> {

    val isPreRelease: Boolean
        get() = preRelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A final release sorts after any pre-release of the same version.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val result = comparePart(preRelease[i], other.preRelease[i])
            if (result != 0) return result
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val pattern = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses `1.2.3`, `v1.2.3` or `1.2.3-rc.1`; returns null for anything else. */
        fun parse(text: String): Version? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, suffix) = match.destructured
            return Version(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = if (suffix.isEmpty()) emptyList() else suffix.split('.').filter { it.isNotEmpty() },
            )
        }

        // Numeric parts compare as numbers and sort before text parts.
        private fun comparePart(a: String, b: String): Int {
            val x = a.toIntOrNull()
            val y = b.toIntOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
