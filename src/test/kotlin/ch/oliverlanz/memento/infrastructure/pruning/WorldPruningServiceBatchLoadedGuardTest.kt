package ch.oliverlanz.memento.infrastructure.pruning

import ch.oliverlanz.memento.domain.renewal.projection.RegionKey
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldPruningServiceBatchLoadedGuardTest {

    @Test
    fun all_loaded_batch_returns_completed_without_admission() {
        GlobalAsyncExclusionGate.attach()
        WorldPruningService.detach()
        WorldPruningService.resetTestHooks()
        try {
            val world = overworld()
            val r1 = RegionKey("minecraft:overworld", 8, 8)
            val r2 = RegionKey("minecraft:overworld", 9, 9)

            WorldPruningService.testGuardOverride = { input ->
                if (input.phase == "preflight") {
                    WorldPruningService.Completion(
                        target = input.region,
                        category = WorldPruningService.OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        outcome = WorldPruningService.Outcome.rejected_loaded,
                        detail = "phase=preflight",
                    )
                } else {
                    null
                }
            }

            val result = WorldPruningService.submitBatch(listOf(world to r1, world to r2))
            val completed = assertIs<WorldPruningService.BatchSubmitResult.Completed>(result)
            assertEquals(2, completed.requested)
            assertEquals("no_eligible_targets", completed.detail)
            assertEquals(2, completed.completions.size)
            assertTrue(completed.completions.all { it.outcome == WorldPruningService.Outcome.rejected_loaded })
            assertTrue(completed.completions.all { it.detail == "phase=preflight" })
            assertTrue(WorldPruningService.isIdle())
            assertNull(WorldPruningService.lastBatchCompletionOrNull())
        } finally {
            WorldPruningService.detach()
            WorldPruningService.resetTestHooks()
            GlobalAsyncExclusionGate.detach()
        }
    }

    @Test
    fun submit_batch_reports_only_eligible_targets_in_submitted_metadata() {
        GlobalAsyncExclusionGate.attach()
        WorldPruningService.detach()
        WorldPruningService.resetTestHooks()
        try {
            val world = overworld()
            val loadedRegion = RegionKey("minecraft:overworld", 1, 1)
            val eligibleRegion = RegionKey("minecraft:overworld", 2, 2)

            WorldPruningService.testGuardOverride = { input ->
                when {
                    input.phase == "preflight" && input.region == loadedRegion ->
                        WorldPruningService.Completion(
                            target = input.region,
                            category = WorldPruningService.OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                            outcome = WorldPruningService.Outcome.rejected_loaded,
                            detail = "phase=preflight",
                        )

                    else -> null
                }
            }

            val result = WorldPruningService.submitBatch(listOf(world to loadedRegion, world to eligibleRegion))
            val submitted = assertIs<WorldPruningService.BatchSubmitResult.Submitted>(result)
            assertEquals(2, submitted.requested)
            assertEquals(1, submitted.submitted)
            assertEquals(listOf(eligibleRegion), submitted.acceptedTargets)
        } finally {
            WorldPruningService.detach()
            WorldPruningService.resetTestHooks()
            GlobalAsyncExclusionGate.detach()
        }
    }

    @Test
    fun execution_phase_rejected_loaded_prevents_any_mutation_attempt() {
        GlobalAsyncExclusionGate.attach()
        WorldPruningService.detach()
        WorldPruningService.resetTestHooks()
        try {
            val world = overworld()
            val region = RegionKey("minecraft:overworld", 3, 3)

            WorldPruningService.testGuardOverride = { input ->
                when (input.phase) {
                    "preflight" -> null
                    "execution" -> WorldPruningService.Completion(
                        target = input.region,
                        category = WorldPruningService.OutcomeCategory.SKIPPED_ERROR_RECOVERED,
                        outcome = WorldPruningService.Outcome.rejected_loaded,
                        detail = "phase=execution",
                    )

                    else -> null
                }
            }
            WorldPruningService.testAsyncMutationOverride = { _, _ ->
                WorldPruningService.Completion(
                    target = region,
                    category = WorldPruningService.OutcomeCategory.SUCCESS,
                    outcome = WorldPruningService.Outcome.success,
                )
            }

            val result = WorldPruningService.submitBatch(listOf(world to region))
            assertIs<WorldPruningService.BatchSubmitResult.Submitted>(result)

            repeat(4) { WorldPruningService.tickThreadProcess() }

            val completion = assertNotNull(WorldPruningService.lastBatchCompletionOrNull())
            assertEquals(1, completion.requested)
            assertEquals(1, completion.submitted)
            assertEquals(1, completion.failed)
            assertEquals(0, completion.succeeded)
            assertEquals(1, completion.completions.size)
            assertEquals(WorldPruningService.Outcome.rejected_loaded, completion.completions[0].outcome)
            assertEquals("phase=execution", completion.completions[0].detail)
            assertEquals(0, WorldPruningService.mutationAttemptsForTesting())
            assertTrue(WorldPruningService.isIdle())
        } finally {
            WorldPruningService.detach()
            WorldPruningService.resetTestHooks()
            GlobalAsyncExclusionGate.detach()
        }
    }

    @Test
    fun dispatch_time_gate_busy_rejects_remaining_targets_without_retry_queue() {
        GlobalAsyncExclusionGate.attach()
        WorldPruningService.detach()
        WorldPruningService.resetTestHooks()
        val busyLatch = CountDownLatch(1)
        try {
            val world = overworld()
            val r1 = RegionKey("minecraft:overworld", 10, 10)
            val r2 = RegionKey("minecraft:overworld", 11, 11)

            WorldPruningService.testGuardOverride = { null }

            val submittedResult = WorldPruningService.submitBatch(listOf(world to r1, world to r2))
            val submitted = assertIs<WorldPruningService.BatchSubmitResult.Submitted>(submittedResult)
            assertEquals(2, submitted.requested)
            assertEquals(2, submitted.submitted)

            val gateHold = GlobalAsyncExclusionGate.submitIfIdle(
                concept = ch.oliverlanz.memento.infrastructure.observability.MementoConcept.PRUNING,
                owner = "test-busy-holder",
            ) {
                Callable {
                    busyLatch.await(5, TimeUnit.SECONDS)
                    1
                }
            }
            assertIs<GlobalAsyncExclusionGate.SubmitResult.Accepted<Int>>(gateHold)

            WorldPruningService.tickThreadProcess()

            val completion = assertNotNull(WorldPruningService.lastBatchCompletionOrNull())
            assertEquals(2, completion.requested)
            assertEquals(2, completion.submitted)
            assertEquals(0, completion.succeeded)
            assertEquals(2, completion.failed)
            assertEquals(2, completion.completions.size)
            assertTrue(completion.completions.all { it.outcome == WorldPruningService.Outcome.rejected_busy })
            assertTrue(completion.completions.all { it.detail?.contains("phase=execution") == true })
            assertTrue(completion.completions.all { it.detail?.contains("activeOwner=test-busy-holder") == true })
            assertTrue(WorldPruningService.isIdle())
        } finally {
            busyLatch.countDown()
            WorldPruningService.detach()
            WorldPruningService.resetTestHooks()
            GlobalAsyncExclusionGate.detach()
        }
    }

    private fun overworld(): RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
}
