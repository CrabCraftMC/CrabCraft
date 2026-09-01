package crabcraft.net.crabUtilities.velocity;

public final class AnalyticsIdentityRegressionTest {

    private AnalyticsIdentityRegressionTest() {}

    public static void main(String[] args) {
        String expected = "cc_dbb943fa5348a97b451a8496c14fd88a699eb513165e7b16c3436e6c6bcfdf72";
        String dashed = AnalyticsService.analyticsId(
                "123e4567-e89b-12d3-a456-426614174000", "test-salt");
        String undashed = AnalyticsService.analyticsId(
                "123E4567E89B12D3A456426614174000", "test-salt");

        if (!expected.equals(dashed) || !expected.equals(undashed)) {
            throw new AssertionError("Java and TypeScript analytics identities must match");
        }
        if (AnalyticsService.analyticsId("not-a-uuid", "test-salt") != null
                || AnalyticsService.analyticsId(
                        "123e4567-e89b-12d3-a456-426614174000", "") != null) {
            throw new AssertionError("Invalid analytics identities must be rejected");
        }
    }
}
