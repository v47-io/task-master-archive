/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2020, Alex Katlein
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.v47.taskMaster

import io.v47.taskMaster.events.TaskHandleEvent
import kotlinx.coroutines.*
import mocks.MockSuspendableTask
import mocks.MockTask
import mocks.MockTaskFactory
import mocks.MockTaskInput
import org.junit.jupiter.api.*
import utils.deferredOnce
import utils.record
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions as A

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskHandleImplTest {
    private val factory = MockTaskFactory()
    private val coroutineScope = object : CoroutineScope {
        override val coroutineContext = Executors.newFixedThreadPool(3).asCoroutineDispatcher() + SupervisorJob()
    }

    private lateinit var taskHandle: TaskHandleImpl<out Any, out Any>

    @Test
    fun `it runs`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput())
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)

        taskHandle.run()

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        assertThrows<IllegalStateException> {
            taskHandle.error
        }

        val output = assertDoesNotThrow {
            taskHandle.output
        }

        A.assertEquals(Unit, output)
    }

    @Test
    fun `it suspends and resumes`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput(suspendable = true))
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        delay(50)

        A.assertEquals(TaskHandleImpl.SuspendResult.Suspended, taskHandle.suspend())
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        delay(50)

        A.assertEquals(TaskHandleImpl.ResumeResult.Resumed, taskHandle.resume())
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)
        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running),
                TaskHandleEvent.StateChanged(TaskState.Suspended),
                TaskHandleEvent.StateChanged(TaskState.Running),
                TaskHandleEvent.StateChanged(TaskState.Complete)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to run`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput(failWhileRunning = true))
        val failedEvent = taskHandle.deferredOnce(TaskHandleEvent.Failed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        failedEvent.await()

        A.assertEquals(TaskState.Failed, taskHandle.state)

        assertThrows<IllegalStateException> {
            taskHandle.output
        }

        val error = assertDoesNotThrow {
            taskHandle.error
        }

        A.assertEquals("This is a random failure", error.message)
        A.assertTrue(error is IllegalArgumentException)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running),
                TaskHandleEvent.StateChanged(TaskState.Failed)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to cleanup after failing`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput(failWhileRunning = true, failDuringCleanUp = true))
        val failedEvent = taskHandle.deferredOnce(TaskHandleEvent.Failed)

        taskHandle.run()

        failedEvent.await()

        A.assertEquals(TaskState.Failed, taskHandle.state)
    }

    @Test
    fun `it fails to suspend`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput(suspendable = true, setSuspended = true, failToSuspend = true))
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        delay(50)

        A.assertEquals(TaskHandleImpl.SuspendResult.Failed, taskHandle.suspend())
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running),
                TaskHandleEvent.StateChanged(TaskState.Complete)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to resume`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput(suspendable = true, setSuspended = true, failToResume = true))

        taskHandle.run()

        delay(50)

        A.assertEquals(TaskHandleImpl.SuspendResult.Suspended, taskHandle.suspend())
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        A.assertEquals(TaskHandleImpl.ResumeResult.Failed, taskHandle.resume())
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        taskHandle.kill()

        A.assertEquals(TaskState.Killed, taskHandle.state)
    }

    @Test
    fun `it reruns a killed task`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput())

        taskHandle.run()

        delay(50)

        A.assertEquals(TaskState.Running, taskHandle.state)

        taskHandle.kill()

        A.assertEquals(TaskState.Killed, taskHandle.state)

        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)

        taskHandle.run()
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)
    }

    @Test
    fun `it is prevented from running by the runCondition`() = runBlocking {
        var doRun = false
        taskHandle = createTaskHandle(MockTaskInput(runCondition = { doRun }))

        taskHandle.run()

        A.assertEquals(TaskState.Waiting, taskHandle.state)

        doRun = true

        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)

        taskHandle.run()

        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)
    }

    @Test
    fun `it shouldn't be broken by calling run multiple times`() = runBlocking {
        taskHandle = createTaskHandle(MockTaskInput())
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        repeat(10) {
            taskHandle.run()

            delay(10)
        }

        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running),
                TaskHandleEvent.StateChanged(TaskState.Complete)
            ),
            recordedEvents
        )
    }

    private fun createTaskHandle(input: MockTaskInput): TaskHandleImpl<MockTaskInput, Unit> {
        val handle = TaskHandleImpl(
            factory,
            coroutineScope,
            if (!input.suspendable)
                MockTask::class.java
            else
                MockSuspendableTask::class.java,
            input,
            0,
            input.runCondition,
            input.cost
        )

        println("Testing with handle $handle")

        return handle
    }

    @AfterEach
    fun cleanup() {
        taskHandle.clear()
    }
}
