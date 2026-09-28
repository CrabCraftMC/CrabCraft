package crabcraft.net.crabUtilities.update

import java.io.IOException

object UpdateExceptions {
    open class NoReleaseException(message: String) : IOException(message)

    open class RateLimitedException(message: String) : IOException(message)

    open class AssetNotFoundException(message: String) : IOException(message)

    open class ChecksumMismatchException(message: String) : IOException(message)
}
