/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amplifyframework.api.ApiCategory
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Test the operation of [DefaultPublicKeyService] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PublicKeyServiceExceptionTest : BaseTests() {
    private val keyRingServiceName = "sudo-virtual-cards"

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "mockSubject"
        }
    }

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>()
    }

    private val mockApiCategory by before {
        mock<ApiCategory>()
    }

    private val publicKeyService by before {
        DefaultPublicKeyService(
            keyRingServiceName = keyRingServiceName,
            userClient = mockUserClient,
            deviceKeyManager = mockDeviceKeyManager,
            graphQLClient = GraphQLClient(mockApiCategory),
            logger = mock<Logger>(),
        )
    }

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())
    }

    @After
    fun fini() =
        runBlocking {
            Timber.uprootAll()
        }

    @Test
    fun shouldThrowIfNotRegistered() =
        runBlocking<Unit> {
            // given
            mockUserClient.stub {
                on { getSubject() } doReturn null
            }

            shouldThrow<PublicKeyService.PublicKeyServiceException.UserIdNotFoundException> {
                publicKeyService.getCurrentRegisteredKey()
            }
        }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows1() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
            }
            shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
                publicKeyService.getCurrentKey()
            }
        }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows2() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { generateNewCurrentKeyPair() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
            }
            shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
                publicKeyService.getCurrentRegisteredKey()
            }
        }

    @Test
    fun shouldThrowIfAppSyncThrows1() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        check {
                            assertEquals(GetPublicKeyQuery.OPERATION_DOCUMENT, it.query)
                        },
                        any(),
                        any(),
                    )
                } doThrow RuntimeException("mock")
            }
            shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
                publicKeyService.get("id")
            }
        }

    @Test
    fun shouldThrowIfAppSyncThrows2() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    mutate<String>(
                        check {
                            assertEquals(CreatePublicKeyMutation.OPERATION_DOCUMENT, it.query)
                        },
                        any(),
                        any(),
                    )
                } doThrow RuntimeException("mock")
            }
            shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
                publicKeyService.create("id", "ringId", ByteArray(42))
            }
        }
}
