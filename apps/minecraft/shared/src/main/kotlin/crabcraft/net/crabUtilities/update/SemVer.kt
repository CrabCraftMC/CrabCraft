package crabcraft.net.crabUtilities.update

import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern

class SemVer(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val prerelease: String?,
) : Comparable<SemVer> {
    fun major(): Int = major

    fun minor(): Int = minor

    fun patch(): Int = patch

    fun prerelease(): String? = prerelease

    fun isPrerelease(): Boolean = prerelease != null

    override fun compareTo(other: SemVer): Int {
        var comparison = major.compareTo(other.major)
        if (comparison != 0) return comparison
        comparison = minor.compareTo(other.minor)
        if (comparison != 0) return comparison
        comparison = patch.compareTo(other.patch)
        if (comparison != 0) return comparison
        if (prerelease == null) return if (other.prerelease == null) 0 else 1
        if (other.prerelease == null) return -1
        val left = dot.split(prerelease)
        val right = dot.split(other.prerelease)
        for (i in 0 until minOf(left.size, right.size)) {
            comparison = compareIdentifier(left[i], right[i])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + (prerelease?.let { "-$it" } ?: "")

    override fun equals(other: Any?): Boolean =
        other is SemVer &&
            major == other.major &&
            minor == other.minor &&
            patch == other.patch &&
            prerelease == other.prerelease

    override fun hashCode(): Int = Objects.hash(major, minor, patch, prerelease)

    companion object {
        private val dot = Pattern.compile("\\.")
        private val digits = Pattern.compile("\\d+")

        @JvmStatic
        fun parse(raw: String?): SemVer? {
            var value = raw?.trim { it <= ' ' } ?: return null
            if (value.isEmpty()) return null
            if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1)
            if (value.uppercase(Locale.getDefault()).contains("SNAPSHOT")) return null
            value = value.substringBefore('+')
            val prerelease = value.indexOf('-').takeIf { it >= 0 }?.let { value.substring(it + 1) }
            value = value.substringBefore('-')
            val parts = dot.split(value)
            if (parts.size !in 1..3) return null
            return try {
                SemVer(
                    parts[0].toInt(),
                    if (parts.size > 1) parts[1].toInt() else 0,
                    if (parts.size > 2) parts[2].toInt() else 0,
                    prerelease,
                )
            } catch (_: NumberFormatException) {
                null
            }
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumeric = digits.matcher(left).matches()
            val rightNumeric = digits.matcher(right).matches()
            if (leftNumeric && rightNumeric) return left.toInt().compareTo(right.toInt())
            if (leftNumeric) return -1
            if (rightNumeric) return 1
            return left.compareTo(right)
        }
    }
}
