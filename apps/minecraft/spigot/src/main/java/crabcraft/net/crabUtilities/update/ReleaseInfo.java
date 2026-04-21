package crabcraft.net.crabUtilities.update;

public record ReleaseInfo(
        String tag,
        SemVer version,
        String jarAssetName,
        String jarUrl,
        String checksumUrl,
        long size,
        boolean prerelease
) {}
