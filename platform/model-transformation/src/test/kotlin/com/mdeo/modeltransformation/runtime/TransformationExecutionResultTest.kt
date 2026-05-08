package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.compiler.VariableBinding
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TransformationExecutionResultTest {

    @Nested
    inner class SuccessTests {

        @Test
        fun `Success with empty context and no modifications`() {
            val result = TransformationExecutionResult.Success.empty()
            
            assertTrue(result.isSuccess())
            assertFalse(result.isFailure())
            assertFalse(result.isStopped())
            assertTrue(result.createdNodes.isEmpty())
            assertTrue(result.deletedNodes.isEmpty())
        }



        @Test
        fun `Success with created nodes`() {
            val result = TransformationExecutionResult.Success(
                createdNodes = setOf("new1", "new2"),
                deletedNodes = emptySet(),
                edgesModified = false
            )
            
            assertEquals(2, result.createdNodes.size)
            assertTrue(result.createdNodes.contains("new1"))
        }



        @Test
        fun `withCreatedNodes adds nodes`() {
            val result = TransformationExecutionResult.Success.empty()
                .withCreatedNodes("new1")
            
            assertEquals(1, result.createdNodes.size)
            assertTrue(result.createdNodes.contains("new1"))
        }

        @Test
        fun `withDeletedNodes adds nodes`() {
            val result = TransformationExecutionResult.Success.empty()
                .withDeletedNodes("del1", "del2")
            
            assertEquals(2, result.deletedNodes.size)
        }

        @Test
        fun `sideeffectsOnly preserves side effects`() {
            val result = TransformationExecutionResult.Success(
                createdNodes = setOf("new1"),
                deletedNodes = setOf("del1"),
                edgesModified = false
            )
            
            val sideEffectsOnly = result
            
            assertEquals(1, sideEffectsOnly.createdNodes.size)
            assertEquals(1, sideEffectsOnly.deletedNodes.size)
        }

        @Test
        fun `merge combines two Success results`() {
            val result1 = TransformationExecutionResult.Success(
                createdNodes = setOf("new1"),
                deletedNodes = emptySet(),
                edgesModified = false
            )
            val result2 = TransformationExecutionResult.Success(
                createdNodes = emptySet(),
                deletedNodes = setOf("del1"),
                edgesModified = false
            )
            
            val merged = result1.merge(result2)  // result1 is new, result2 is acc
            
            assertEquals(1, merged.createdNodes.size)
            assertEquals(1, merged.deletedNodes.size)
        }

        @Test
        fun `successOrNull returns Success`() {
            val result: TransformationExecutionResult = TransformationExecutionResult.Success.empty()
            
            val success = result.successOrNull()
            assertIs<TransformationExecutionResult.Success>(success)
        }
    }

    @Nested
    inner class FailureTests {

        @Test
        fun `Failure with reason`() {
            val result = TransformationExecutionResult.Failure(
                reason = "Pattern did not match",
                failedAt = null,
                isDeterministic = false,
                changesWereMade = false
            )
            
            assertFalse(result.isSuccess())
            assertTrue(result.isFailure())
            assertFalse(result.isStopped())
            assertEquals("Pattern did not match", result.reason)
            assertNull(result.failedAt)
        }

        @Test
        fun `Failure with failedAt`() {
            val result = TransformationExecutionResult.Failure(
                reason = "Error",
                failedAt = "match house: House",
                isDeterministic = false,
                changesWereMade = false
            )
            
            assertEquals("match house: House", result.failedAt)
        }

        @Test
        fun `at method adds location`() {
            val result = TransformationExecutionResult.Failure(
                    reason = "Error", failedAt = null, isDeterministic = false, changesWereMade = false
                ).at("line 42")
            
            assertEquals("line 42", result.failedAt)
        }

        @Test
        fun `failureOrNull returns Failure`() {
            val result: TransformationExecutionResult = TransformationExecutionResult.Failure(
                reason = "error", failedAt = null, isDeterministic = false, changesWereMade = false
            )
            
            val failure = result.failureOrNull()
            assertIs<TransformationExecutionResult.Failure>(failure)
        }

        @Test
        fun `failureOrNull returns null for Success`() {
            val result: TransformationExecutionResult = TransformationExecutionResult.Success.empty()
            
            assertNull(result.failureOrNull())
        }
    }

    @Nested
    inner class StoppedTests {

        @Test
        fun `Stopped with stop keyword`() {
            val result = TransformationExecutionResult.Stopped(
                keyword = "stop"
            )
            
            assertFalse(result.isSuccess())
            assertFalse(result.isFailure())
            assertTrue(result.isStopped())
            assertTrue(result.isNormalStop)
            assertFalse(result.isKill)
        }

        @Test
        fun `Stopped with kill keyword`() {
            val result = TransformationExecutionResult.Stopped(
                keyword = "kill"
            )
            
            assertTrue(result.isStopped())
            assertFalse(result.isNormalStop)
            assertTrue(result.isKill)
        }

    }

    @Nested
    inner class ExtensionFunctionTests {

        @Test
        fun `isSuccess returns true only for Success`() {
            val success: TransformationExecutionResult = TransformationExecutionResult.Success.empty()
            val failure: TransformationExecutionResult = TransformationExecutionResult.Failure(reason = "err", failedAt = null, isDeterministic = false, changesWereMade = false)
            val stopped: TransformationExecutionResult = TransformationExecutionResult.Stopped("stop")
            
            assertTrue(success.isSuccess())
            assertFalse(failure.isSuccess())
            assertFalse(stopped.isSuccess())
        }

        @Test
        fun `isFailure returns true only for Failure`() {
            val success: TransformationExecutionResult = TransformationExecutionResult.Success.empty()
            val failure: TransformationExecutionResult = TransformationExecutionResult.Failure(reason = "err", failedAt = null, isDeterministic = false, changesWereMade = false)
            val stopped: TransformationExecutionResult = TransformationExecutionResult.Stopped("stop")
            
            assertFalse(success.isFailure())
            assertTrue(failure.isFailure())
            assertFalse(stopped.isFailure())
        }

        @Test
        fun `isStopped returns true only for Stopped`() {
            val success: TransformationExecutionResult = TransformationExecutionResult.Success.empty()
            val failure: TransformationExecutionResult = TransformationExecutionResult.Failure(reason = "err", failedAt = null, isDeterministic = false, changesWereMade = false)
            val stopped: TransformationExecutionResult = TransformationExecutionResult.Stopped("stop")
            
            assertFalse(success.isStopped())
            assertFalse(failure.isStopped())
            assertTrue(stopped.isStopped())
        }

        @Test
        fun `successOrNull returns null for non-Success`() {
            val failure: TransformationExecutionResult = TransformationExecutionResult.Failure(reason = "err", failedAt = null, isDeterministic = false, changesWereMade = false)
            
            assertNull(failure.successOrNull())
        }
    }
}
