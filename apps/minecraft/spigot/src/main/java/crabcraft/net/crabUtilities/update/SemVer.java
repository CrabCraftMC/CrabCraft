package crabcraft.net.crabUtilities.update;

import java.util.Objects;

public final class SemVer implements Comparable<SemVer> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;

    public SemVer(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static SemVer parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.toUpperCase().contains("SNAPSHOT")) return null;

        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);

        String pre = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1);
            s = s.substring(0, dash);
        }

        String[] parts = s.split("\\.");
        if (parts.length < 1 || parts.length > 3) return null;
        try {
            int maj = Integer.parseInt(parts[0]);
            int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int pat = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new SemVer(maj, min, pat, pre);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public String prerelease() { return prerelease; }
    public boolean isPrerelease() { return prerelease != null; }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (prerelease == null && o.prerelease == null) return 0;
        if (prerelease == null) return 1;
        if (o.prerelease == null) return -1;
        return comparePrerelease(prerelease, o.prerelease);
    }

    private static int comparePrerelease(String a, String b) {
        String[] ap = a.split("\\.");
        String[] bp = b.split("\\.");
        int len = Math.min(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int c = compareIdentifier(ap[i], bp[i]);
            if (c != 0) return c;
        }
        return Integer.compare(ap.length, bp.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNum = a.matches("\\d+");
        boolean bNum = b.matches("\\d+");
        if (aNum && bNum) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        if (aNum) return -1;
        if (bNum) return 1;
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return prerelease != null ? base + "-" + prerelease : base;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SemVer s)) return false;
        return major == s.major && minor == s.minor && patch == s.patch
                && Objects.equals(prerelease, s.prerelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }
}
