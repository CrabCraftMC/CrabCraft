package crabcraft.net.crabUtilities.velocity.update

import java.io.IOException

class UpdateExceptions private constructor() {
    open class NoReleaseException(message: String) : IOException(message)
    open class RateLimitedException(message: String) : IOException(message)
    open class AssetNotFoundException(message: String) : IOException(message)
    open class ChecksumMismatchException(message: String) : IOException(message)
}
