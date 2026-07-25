package crabcraft.net.crabUtilities;

final class ReloadCommandRegressionTest {

    public static void main(String[] args) {
        check(ReloadCommand.reloadingMessage("all").equals("Reloading all..."),
                "all reload start message changed");
        check(ReloadCommand.reloadingMessage("media").equals("Reloading media..."),
                "module reload start message changed");
        check(ReloadCommand.reloadedMessage(42).equals("Reloaded (42 ms)"),
                "reload completion message changed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
