package com.dot.gallery.core.sandbox

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class IsolatedDecoderConnectionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = mockk<Context>()
    private val serviceConnection = slot<ServiceConnection>()

    @Test
    fun transientDisconnect_keepsRegisteredBindingAndWaitsForReconnect() {
        runTest {
            every { context.packageName } returns "com.dot.gallery"
            every {
                context.bindService(
                    any<Intent>(),
                    capture(serviceConnection),
                    any<Int>(),
                )
            } returns true
            every { context.unbindService(any()) } just Runs
            val connection = IsolatedDecoderConnection(context = context)

            val firstRequest = async { connection.getMessenger() }
            runCurrent()
            serviceConnection.captured.onServiceConnected(null, Binder())
            assertNotNull(firstRequest.await())

            serviceConnection.captured.onServiceDisconnected(null)
            val reconnectingRequest = async { connection.getMessenger() }
            runCurrent()
            verify(exactly = 1) {
                context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
            }

            serviceConnection.captured.onServiceConnected(null, Binder())
            assertNotNull(reconnectingRequest.await())
            verify(exactly = 0) { context.unbindService(any()) }
        }
    }

    @Test
    fun bindingDeath_releasesBindingBeforeRegisteringAnother() {
        runTest {
            every { context.packageName } returns "com.dot.gallery"
            every {
                context.bindService(
                    any<Intent>(),
                    capture(serviceConnection),
                    any<Int>(),
                )
            } returns true
            every { context.unbindService(any()) } just Runs
            val connection = IsolatedDecoderConnection(context = context)

            val firstRequest = async { connection.getMessenger() }
            runCurrent()
            serviceConnection.captured.onServiceConnected(null, Binder())
            assertNotNull(firstRequest.await())

            serviceConnection.captured.onBindingDied(null)
            verify(exactly = 1) { context.unbindService(any()) }

            val replacementRequest = async { connection.getMessenger() }
            runCurrent()
            verify(exactly = 2) {
                context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
            }
            serviceConnection.captured.onServiceConnected(null, Binder())
            assertNotNull(replacementRequest.await())
        }
    }

    @Test
    fun bindingTimeout_releasesRegisteredBinding() {
        runTest {
            every { context.packageName } returns "com.dot.gallery"
            every {
                context.bindService(
                    any<Intent>(),
                    capture(serviceConnection),
                    any<Int>(),
                )
            } returns true
            every { context.unbindService(any()) } just Runs
            val connection = IsolatedDecoderConnection(context = context)

            val request = async { connection.getMessenger() }
            runCurrent()
            advanceTimeBy(delayTimeMillis = 5_000L)
            runCurrent()

            assertNull(request.await())
            verify(exactly = 1) { context.unbindService(serviceConnection.captured) }
        }
    }
}
