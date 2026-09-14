package crabcraft.net.crabUtilities

object ReloadCommandRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(ReloadCommand.reloadingMessage("all") == "Reloading all...",
            "all reload start message changed")
        check(ReloadCommand.reloadingMessage("media") == "Reloading media...",
            "module reload start message changed")
        check(ReloadCommand.reloadedMessage(42) == "Reloaded (42 ms)",
            "reload completion message changed")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
