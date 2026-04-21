package crabcraft.net.crabUtilities.update;

import java.io.IOException;

public final class UpdateExceptions {
    private UpdateExceptions() {}

    public static class NoReleaseException extends IOException {
        public NoReleaseException(String msg) { super(msg); }
    }

    public static class RateLimitedException extends IOException {
        public RateLimitedException(String msg) { super(msg); }
    }

    public static class AssetNotFoundException extends IOException {
        public AssetNotFoundException(String msg) { super(msg); }
    }

    public static class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String msg) { super(msg); }
    }
}
