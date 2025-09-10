/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amplifyframework.api.ApiCategory
import com.amplifyframework.api.graphql.GraphQLOperation
import com.amplifyframework.api.graphql.GraphQLRequest
import com.amplifyframework.api.graphql.GraphQLResponse
import com.amplifyframework.core.Consumer
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CreatePublicKeyInput
import com.sudoplatform.sudovirtualcards.graphql.type.KeyFormat
import io.kotlintest.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Test the operation of [DefaultPublicKeyService] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PublicKeyServiceRoboTest : BaseTests() {
    private val keyRingServiceName = "sudo-virtual-cards"
    private val owner = "mockSubject"

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn owner
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

    private val publicKey = "publicKey".toByteArray()
    private val deviceKeyPair =
        DeviceKey(
            keyId = "keyId",
            publicKey = publicKey,
        )

    private val queryResponse by before {
        JSONObject(
            """
            {
                'getPublicKeyForVirtualCards':
                    {
                        '__typename': 'PublicKey',
                        'id':'owner-keyId',
                        'keyId': 'keyId',
                        'keyRingId': 'keyRingId',
                        'algorithm': 'algoirithm',
                        'keyFormat': '${KeyFormat.RSA_PUBLIC_KEY}',
                        'publicKey': '${Base64.toBase64String(publicKey)}',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 1.0,
                        'owner': '$owner'
                    }
            }
            """.trimIndent(),
        )
    }

    private val mutationResponse by before {
        JSONObject(
            """
            {
                'createPublicKeyForVirtualCards':
                    {
                        '__typename': 'PublicKey',
                        'id':'owner-keyId',
                        'keyId': 'keyId',
                        'keyRingId': 'keyRingId',
                        'algorithm': 'algoirithm',
                        'keyFormat': '${KeyFormat.RSA_PUBLIC_KEY}',
                        'publicKey': '${Base64.toBase64String(publicKey)}',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 1.0,
                        'owner': '$owner'
                    }
            }
            """.trimIndent(),
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

            verifyNoMoreInteractions(mockApiCategory, mockDeviceKeyManager, mockUserClient)
        }

    @Test
    fun `getCurrentKey() should return key if present in device key manager`() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doReturn deviceKeyPair
            }

            publicKeyService.getCurrentKey() shouldBe
                PublicKey(
                    keyId = deviceKeyPair.keyId,
                    publicKey = deviceKeyPair.publicKey,
                )

            verify(mockDeviceKeyManager).getCurrentKey()
        }

    @Test
    fun `getCurrentKey() should return null if no key present in device key manager`() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doReturn null
            }

            publicKeyService.getCurrentKey() shouldBe null

            verify(mockDeviceKeyManager).getCurrentKey()
        }

    @Test
    fun `getCurrentRegisteredKey() should return key if present in key manager with key ring id from service`() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doReturn deviceKeyPair
            }
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(GetPublicKeyQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doAnswer {
                    val mockOperation: GraphQLOperation<String> = mock()
                    @Suppress("UNCHECKED_CAST")
                    (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                        GraphQLResponse(queryResponse.toString(), null),
                    )
                    mockOperation
                }
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    publicKeyService.getCurrentRegisteredKey()
                }

            deferredResult.start()
            delay(100)
            val result = deferredResult.await()

            with(result) {
                publicKey shouldBe publicKey
                keyRingId shouldBe "keyRingId"
                created shouldBe false
            }

            verify(mockUserClient).getSubject()
            verify(mockDeviceKeyManager).getCurrentKey()
            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetPublicKeyQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
        }

    @Test
    fun `getCurrentRegisteredKey() should create key if not present in key manager`() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doReturn null
                on { generateNewCurrentKeyPair() } doReturn deviceKeyPair
            }
            mockApiCategory.stub {
                on {
                    mutate<String>(
                        argThat { this.query.equals(CreatePublicKeyMutation.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doAnswer {
                    val mockOperation: GraphQLOperation<String> = mock()
                    @Suppress("UNCHECKED_CAST")
                    (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                        GraphQLResponse(mutationResponse.toString(), null),
                    )
                    mockOperation
                }
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    publicKeyService.getCurrentRegisteredKey()
                }

            deferredResult.start()
            delay(100)
            val result = deferredResult.await()

            with(result) {
                publicKey shouldBe publicKey
                keyRingId shouldBe "$keyRingServiceName.$owner"
                created shouldBe true
            }

            verify(mockDeviceKeyManager).getCurrentKey()
            verify(mockDeviceKeyManager).generateNewCurrentKeyPair()

            val mutationCaptor = argumentCaptor<GraphQLRequest<String>>()
            verify(mockApiCategory).mutate<String>(mutationCaptor.capture(), any(), any())
            val input = mutationCaptor.firstValue.variables["input"] as CreatePublicKeyInput?
            input?.keyRingId shouldBe "$keyRingServiceName.$owner"

            verify(mockUserClient).getSubject()
        }

    @Test
    fun `getCurrentRegisteredKey() should register key if present in key manager and not registered`() =
        runBlocking<Unit> {
            mockDeviceKeyManager.stub {
                on { getCurrentKey() } doReturn deviceKeyPair
            }
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(GetPublicKeyQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doAnswer {
                    val mockOperation: GraphQLOperation<String> = mock()
                    @Suppress("UNCHECKED_CAST")
                    (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                        GraphQLResponse(null, null),
                    )
                    mockOperation
                }
                on {
                    mutate<String>(
                        argThat { this.query.equals(CreatePublicKeyMutation.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doAnswer {
                    val mockOperation: GraphQLOperation<String> = mock()
                    @Suppress("UNCHECKED_CAST")
                    (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                        GraphQLResponse(mutationResponse.toString(), null),
                    )
                    mockOperation
                }
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    publicKeyService.getCurrentRegisteredKey()
                }

            deferredResult.start()
            delay(100)
            val result = deferredResult.await()
            with(result) {
                publicKey shouldBe publicKey
                keyRingId shouldBe "$keyRingServiceName.$owner"
                created shouldBe false
            }

            verify(mockDeviceKeyManager).getCurrentKey()
            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetPublicKeyQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )

            val mutationCaptor = argumentCaptor<GraphQLRequest<String>>()
            verify(mockApiCategory).mutate(mutationCaptor.capture(), any(), any())
            val input = mutationCaptor.firstValue.variables["input"] as CreatePublicKeyInput?
            input?.keyRingId shouldBe "$keyRingServiceName.$owner"

            verify(mockUserClient).getSubject()
        }
}
