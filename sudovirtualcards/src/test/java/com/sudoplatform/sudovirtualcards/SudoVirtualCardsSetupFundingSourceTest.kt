/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amplifyframework.api.ApiCategory
import com.amplifyframework.api.graphql.GraphQLOperation
import com.amplifyframework.api.graphql.GraphQLResponse
import com.amplifyframework.core.Consumer
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProvisioningData
import com.sudoplatform.sudovirtualcards.types.ClientApplicationData
import com.sudoplatform.sudovirtualcards.types.LinkToken
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisioningData
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.binary.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import com.sudoplatform.sudovirtualcards.types.FundingSourceType as FundingSourceTypeEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.setupFundingSource]
 * using mocks and spies.
 */
@RunWith(Parameterized::class)
class SudoVirtualCardsSetupFundingSourceTest(private val provider: String) : BaseTests() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<String> {
            return listOf(
                "stripe",
                "checkoutBankAccount",
            )
        }
    }

    private val input by before {
        mapOf(
            "stripe" to SetupFundingSourceInput(
                "USD",
                FundingSourceTypeEntity.CREDIT_CARD,
                ClientApplicationData("system-test-app"),
                listOf("stripe"),
            ),
            "checkoutBankAccount" to SetupFundingSourceInput(
                "USD",
                FundingSourceTypeEntity.BANK_ACCOUNT,
                ClientApplicationData("system-test-app"),
                listOf("checkout"),
                "en-US",
            ),
        )
    }

    // Compile-time test of backwards compatibility.
    val backwardCompatibilityProviderProvisioningData = ProvisioningData("stripe", 1, "intent", "clientSecret")

    private val authorizationText = AuthorizationText(
        "en-US",
        "content",
        "contentType",
        "hash",
        "hashAlgorithm",
    )
    private val expectedProvisioningData = mapOf(
        "stripe" to StripeCardProvisioningData("stripe", 1, "intent", "clientSecret", FundingSourceTypeEntity.CREDIT_CARD),
        "checkoutBankAccount" to CheckoutBankAccountProvisioningData(
            "checkout",
            1,
            FundingSourceTypeEntity.BANK_ACCOUNT,
            LinkToken(
                "linkToken",
                "expiration",
                "requestId",
            ),
            listOf(authorizationText),
        ),
    )

    private val mutationResponse by before {
        val stripeSetupData = StripeCardProvisioningData("stripe", 1, "intent", "clientSecret", FundingSourceTypeEntity.CREDIT_CARD)
        val checkoutBankAccountSetupData = CheckoutBankAccountProvisioningData(
            "checkout",
            1,
            FundingSourceTypeEntity.BANK_ACCOUNT,
            LinkToken(
                "linkToken",
                "expiration",
                "requestId",
            ),
            listOf(authorizationText),
        )

        mapOf(
            "stripe" to
                JSONObject(
                    """
                {
                    'setupFundingSource': {
                        '__typename': 'ProvisionalFundingSource',
                        'id':'id',
                        'owner': 'owner',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 10.0,
                        'type': '${FundingSourceType.CREDIT_CARD}',
                        'provisioningData': '${Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray())}',
                        'state': '${ProvisionalFundingSourceState.PROVISIONING}',
                        'last4':''
                    }
                }
                    """.trimIndent(),
                ),
            "checkoutBankAccount" to
                JSONObject(
                    """
                {
                    'setupFundingSource': {
                        '__typename': 'ProvisionalFundingSource',
                        'id':'id',
                        'owner': 'owner',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 10.0,
                        'type': '${FundingSourceType.BANK_ACCOUNT}',
                        'provisioningData': '${Base64.encodeBase64String(Gson().toJson(checkoutBankAccountSetupData).toByteArray())}',
                        'state': '${ProvisionalFundingSourceState.PROVISIONING}',
                        'last4':''
                    }
                }
                    """.trimIndent(),
                ),
        )
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockApiCategory by before {
        mock<ApiCategory>().stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(), any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                val responseToUse = mutationResponse[provider] ?: throw missingProvider(provider)
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(responseToUse.toString(), null),
                )
                mockOperation
            }
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockKeyManager,
            mockApiCategory,
        )
    }

    @Test
    fun `setupFundingSource() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
        }
        deferredResult.start()

        delay(100L)
        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
            state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
            provisioningData shouldBe (expectedProvisioningData[provider] ?: throw missingProvider(provider))
        }

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, null),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when an unsupported currency error occurs`() = runBlocking<Unit> {
        val input by before {
            SetupFundingSourceInput(
                "AUD",
                input[provider]?.type ?: throw missingProvider(provider),
                ClientApplicationData("system-test-app"),
            )
        }

        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "UnsupportedCurrencyError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }
        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation("AUD")
    }

    @Test
    fun `setupFundingSource() should throw when an account locked error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "AccountLockedError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }
        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when an entitlements exceeded error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "EntitlementExceededError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.EntitlementExceededException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when a velocity exceeded error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "VelocityExceededError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.VelocityExceededException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("httpStatus" to HttpURLConnection.HTTP_FORBIDDEN),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }
        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    @Test
    fun `setupFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(SetupFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verifySetupFundingSourceMutation()
    }

    private fun verifySetupFundingSourceMutation(expectedCurrency: String = "USD") {
        verify(mockApiCategory).mutate<String>(
            check {
                assertEquals(SetupFundingSourceMutation.OPERATION_DOCUMENT, it.query)
                val mutationInput = it.variables["input"] as SetupFundingSourceRequest?
                mutationInput?.currency shouldBe expectedCurrency
                mutationInput?.type?.toString() shouldBe input[provider]?.type?.toString()
            },
            any(),
            any(),
        )
    }
}
