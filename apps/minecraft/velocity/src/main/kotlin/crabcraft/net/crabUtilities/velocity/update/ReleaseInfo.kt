package crabcraft.net.crabUtilities.velocity.update

data class ReleaseInfo(
    private val tag: String,
    private val version: SemVer?,
    private val jarAssetName: String,
    private val jarUrl: String,
    private val checksumUrl: String?,
    private val size: Long,
    private val prerelease: Boolean
) {
    fun tag(): String = tag
    fun version(): SemVer? = version
    fun jarAssetName(): String = jarAssetName
    fun jarUrl(): String = jarUrl
    fun checksumUrl(): String? = checksumUrl
    fun size(): Long = size
    fun prerelease(): Boolean = prerelease
}
