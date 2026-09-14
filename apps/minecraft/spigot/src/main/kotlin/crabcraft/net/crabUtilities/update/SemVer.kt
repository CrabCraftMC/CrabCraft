package crabcraft.net.crabUtilities.update

import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern

class SemVer(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val prerelease: String?
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
        if (prerelease == null && other.prerelease == null) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch"
        return if (prerelease != null) "$base-$prerelease" else base
    }

    override fun equals(other: Any?): Boolean =
        other is SemVer && major == other.major && minor == other.minor && patch == other.patch && prerelease == other.prerelease

    override fun hashCode(): Int = Objects.hash(major, minor, patch, prerelease)

    companion object {
        @JvmStatic
        fun parse(raw: String?): SemVer? {
            if (raw == null) return null
            var value = raw.trim { it <= ' ' }
            if (value.isEmpty()) return null
            if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1)
            if (value.uppercase(Locale.getDefault()).contains("SNAPSHOT")) return null

            val plus = value.indexOf('+')
            if (plus >= 0) value = value.substring(0, plus)

            var prerelease: String? = null
            val dash = value.indexOf('-')
            if (dash >= 0) {
                prerelease = value.substring(dash + 1)
                value = value.substring(0, dash)
            }

            val parts = Pattern.compile("\\.").split(value)
            if (parts.size < 1 || parts.size > 3) return null
            return try {
                val major = parts[0].toInt()
                val minor = if (parts.size > 1) parts[1].toInt() else 0
                val patch = if (parts.size > 2) parts[2].toInt() else 0
                SemVer(major, minor, patch, prerelease)
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun comparePrerelease(first: String, second: String): Int {
            val firstParts = Pattern.compile("\\.").split(first)
            val secondParts = Pattern.compile("\\.").split(second)
            for (index in 0 until minOf(firstParts.size, secondParts.size)) {
                val comparison = compareIdentifier(firstParts[index], secondParts[index])
                if (comparison != 0) return comparison
            }
            return firstParts.size.compareTo(secondParts.size)
        }

        private fun compareIdentifier(first: String, second: String): Int {
            val firstNumeric = first.matches(Regex("\\d+"))
            val secondNumeric = second.matches(Regex("\\d+"))
            if (firstNumeric && secondNumeric) return first.toInt().compareTo(second.toInt())
            if (firstNumeric) return -1
            if (secondNumeric) return 1
            return first.compareTo(second)
        }
    }
}
