/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudouser.SudoPlatformSignInCallback
import com.sudoplatform.sudouser.exceptions.SudoUserException
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.logging.Logger

/**
 * Integration tests for the sign-in guard / callback mechanism on [SudoVirtualCardsClient].
 *
 * Uses getVirtualCardsConfig as a simple method to exercise the guard since it requires
 * no additional setup (no funding sources, cards, etc.).
 */
@RunWith(AndroidJUnit4::class)
class SignInGuardIntegrationTest : BaseIntegrationTest() {
    private lateinit var vcClient: SudoVirtualCardsClient

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())
        Logger.getLogger(LogConstants.SUDOLOG_TAG).level = java.util.logging.Level.FINEST
        KeyManagerFactory(context).createAndroidKeyManager().removeAllKeys()

        vcClient =
            SudoVirtualCardsClient
                .builder()
                .setContext(context)
                .setSudoUserClient(userClient)
                .build()
    }

    @After
    fun fini() =
        runBlocking {
            if (userClient.isRegistered()) {
                if (!userClient.isSignedIn()) {
                    userClient.signInWithKey()
                }
                deregister()
            }
            vcClient.reset()
            sudoClient.reset()
            userClient.reset()
            Timber.uprootAll()
        }

    @Test
    fun signedInWithNoCallbackShouldSucceed() =
        runBlocking {
            registerSignInAndEntitle()

            val result = vcClient.getVirtualCardsConfig()
            result shouldNotBe null
        }

    @Test
    fun signedInWithCallbackSetShouldSucceedWithoutInvokingCallback() =
        runBlocking {
            registerSignInAndEntitle()

            var callbackInvoked = false
            vcClient.setSignInCallback(
                object : SudoPlatformSignInCallback {
                    override suspend fun signIn() {
                        callbackInvoked = true
                    }
                },
            )

            val result = vcClient.getVirtualCardsConfig()
            result shouldNotBe null
            callbackInvoked shouldBe false
        }

    @Test
    fun notSignedInWithNoCallbackShouldThrow() =
        runBlocking<Unit> {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                vcClient.getVirtualCardsConfig()
            }
        }

    @Test
    fun notSignedInWithCallbackShouldInvokeCallbackAndSucceedAfterSignIn() =
        runBlocking {
            // Register, sign in, and entitle first so the account is fully set up
            registerSignInAndEntitle()
            // Sign out
            userClient.globalSignOut()
            userClient.isSignedIn() shouldBe false

            var callbackInvoked = false
            vcClient.setSignInCallback(
                object : SudoPlatformSignInCallback {
                    override suspend fun signIn() {
                        callbackInvoked = true
                        // Actually sign in when the callback is invoked
                        registerSignInAndEntitle()
                    }
                },
            )

            // The guard should detect we're not signed in, invoke the callback
            // (which signs us in), then proceed with the operation
            val result = vcClient.getVirtualCardsConfig()
            result shouldNotBe null
            callbackInvoked shouldBe true
        }

    @Test
    fun notSignedInWithCallbackThatFailsToSignInShouldThrow() =
        runBlocking<Unit> {
            register()
            userClient.isRegistered() shouldBe true
            userClient.isSignedIn() shouldBe false

            var callbackInvoked = false
            vcClient.setSignInCallback(
                object : SudoPlatformSignInCallback {
                    override suspend fun signIn() {
                        callbackInvoked = true
                        // Intentionally do NOT sign in
                    }
                },
            )

            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                vcClient.getVirtualCardsConfig()
            }
            callbackInvoked shouldBe true
        }

    @Test
    fun callbackExceptionShouldPropagateToCallerWhenNotSignedIn() =
        runBlocking<Unit> {
            register()
            userClient.isRegistered() shouldBe true
            userClient.isSignedIn() shouldBe false

            vcClient.setSignInCallback(
                object : SudoPlatformSignInCallback {
                    override suspend fun signIn(): Unit = throw IllegalStateException("Simulated sign-in failure")
                },
            )

            val exception =
                shouldThrow<IllegalStateException> {
                    vcClient.getVirtualCardsConfig()
                }
            exception.message shouldBe "Simulated sign-in failure"
        }

    @Test
    fun clearingCallbackShouldStopEnforcement() =
        runBlocking {
            register()
            userClient.isRegistered() shouldBe true
            userClient.isSignedIn() shouldBe false

            var callbackInvoked = false
            vcClient.setSignInCallback(
                object : SudoPlatformSignInCallback {
                    override suspend fun signIn() {
                        callbackInvoked = true
                    }
                },
            )

            // Clear the callback
            vcClient.setSignInCallback(null)

            // Should not throw NotSignedInException even though not signed in
            try {
                vcClient.getVirtualCardsConfig()
            } catch (e: SudoUserException.NotSignedInException) {
                throw AssertionError("Should not throw NotSignedInException after callback cleared", e)
            } catch (_: Exception) {
                // Other exceptions from the service are expected
            }
            callbackInvoked shouldBe false
        }
}
