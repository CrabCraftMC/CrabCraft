package crabcraft.net.crabUtilities.bingo;

import java.util.function.IntPredicate;

/** Bounded vertical-run check shared by the live detector and dependency-free regression tests. */
final class DripleafColumnDetector {
    private DripleafColumnDetector() {}

    static boolean hasOwnedRunThrough(int anchorY, int requiredHeight, IntPredicate isOwnedDripleaf) {
        if (requiredHeight <= 0) {
            throw new IllegalArgumentException("requiredHeight must be positive");
        }
        if (!isOwnedDripleaf.test(anchorY)) {
            return false;
        }

        int bottomY = anchorY;
        for (int offset = 1; offset < requiredHeight && isOwnedDripleaf.test(bottomY - 1); offset++) {
            bottomY--;
        }

        for (int offset = 0; offset < requiredHeight; offset++) {
            if (!isOwnedDripleaf.test(bottomY + offset)) {
                return false;
            }
        }
        return true;
    }
}
