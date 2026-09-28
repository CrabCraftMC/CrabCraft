package crabcraft.net.crabUtilities.appleskin

object AppleSkinLifecycleRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        shutdownInvalidatesSyncGenerations()
    }

    private fun shutdownInvalidatesSyncGenerations() {
        val before = AppleSkinIntegration.currentGeneration()
        AppleSkinIntegration.invalidateTasks()
        val after = AppleSkinIntegration.currentGeneration()

        check(after > before, "AppleSkin shutdown did not advance its task generation")
        check(
            !AppleSkinIntegration.isCurrentGeneration(after),
            "AppleSkin considered a task generation active after shutdown",
        )
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }
}
