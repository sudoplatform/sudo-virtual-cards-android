/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalFundingSource as ProvisionalFundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProvisioningData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.ClientApplicationData
import com.sudoplatform.sudovirtualcards.types.LinkToken
import com.sudoplatform.sudovirtualcards.types.ProvisioningData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.FundingSourceType as FundingSourceTypeEntity
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.codec.binary.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

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
                "checkoutCard",
                "checkoutBankAccount"
            )
        }
    }

    private val input by before {
        mapOf(
            "stripe" to SetupFundingSourceInput(
                "USD",
                FundingSourceTypeEntity.CREDIT_CARD,
                ClientApplicationData("system-test-app"),
                listOf("stripe")
            ),
            "checkoutCard" to SetupFundingSourceInput(
                "USD",
                FundingSourceTypeEntity.CREDIT_CARD,
                ClientApplicationData("system-test-app"),
                listOf("checkout")
            ),
            "checkoutBankAccount" to SetupFundingSourceInput(
                "USD",
                FundingSourceTypeEntity.BANK_ACCOUNT,
                ClientApplicationData("system-test-app"),
                listOf("checkout"),
                "en-US"
            )
        )
    }

    private val mutationRequest = mapOf(
        "stripe" to SetupFundingSourceRequest.builder()
            .type(FundingSourceType.CREDIT_CARD)
            .currency("USD")
            .supportedProviders(listOf("stripe"))
            .setupData(
                com.amazonaws.util.Base64.encode(
                    Gson().toJson(
                        ClientApplicationData(
                            "system-test-app"
                        )
                    ).toByteArray()
                ).toString(Charsets.UTF_8)
            )
            .build(),
        "checkoutCard" to SetupFundingSourceRequest.builder()
            .type(FundingSourceType.CREDIT_CARD)
            .currency("USD")
            .supportedProviders(listOf("checkout"))
            .setupData(
                com.amazonaws.util.Base64.encode(
                    Gson().toJson(
                        ClientApplicationData(
                            "system-test-app"
                        )
                    ).toByteArray()
                ).toString(Charsets.UTF_8)
            )
            .build(),
        "checkoutBankAccount" to SetupFundingSourceRequest.builder()
            .type(FundingSourceType.BANK_ACCOUNT)
            .currency("USD")
            .supportedProviders(listOf("checkout"))
            .setupData(
                com.amazonaws.util.Base64.encode(
                    Gson().toJson(
                        ClientApplicationData(
                            "system-test-app"
                        )
                    ).toByteArray()
                ).toString(Charsets.UTF_8)
            )
            .build()
    )

    // Compile-time test of backwards compatibility.
    val backwardCompatibilityProviderProvisioningData = ProvisioningData("stripe", 1, "intent", "clientSecret")

    private val authorizationText = AuthorizationText(
        "en-US",
        "content",
        "contentType",
        "hash",
        "hashAlgorithm"
    )
    private val expectedProvisioningData = mapOf(
        "stripe" to StripeCardProvisioningData("stripe", 1, "intent", "clientSecret", FundingSourceTypeEntity.CREDIT_CARD),
        "checkoutCard" to CheckoutCardProvisioningData("checkout", 1, FundingSourceTypeEntity.CREDIT_CARD),
        "checkoutBankAccount" to CheckoutBankAccountProvisioningData(
            "checkout",
            1,
            FundingSourceTypeEntity.BANK_ACCOUNT,
            LinkToken(
                "linkToken",
                "expiration",
                "requestId"
            ),
            listOf(authorizationText)
        )
    )

    private val mutationResult by before {
        val stripeSetupData = StripeCardProvisioningData("stripe", 1, "intent", "clientSecret", FundingSourceTypeEntity.CREDIT_CARD)
        val checkoutCardSetupData = CheckoutCardProvisioningData("checkout", 1, FundingSourceTypeEntity.CREDIT_CARD)
        val checkoutBankAccountSetupData = CheckoutBankAccountProvisioningData(
            "checkout",
            1,
            FundingSourceTypeEntity.BANK_ACCOUNT,
            LinkToken(
                "linkToken",
                "expiration",
                "requestId"
            ),
            listOf(authorizationText)
        )

        mapOf(
            "stripe" to
                SetupFundingSourceMutation.SetupFundingSource(
                    "SetupFundingSource",
                    SetupFundingSourceMutation.SetupFundingSource.Fragments(
                        ProvisionalFundingSourceFragment(
                            "ProvisionalFundingSource",
                            "id",
                            "owner",
                            1,
                            1.0,
                            10.0,
                            FundingSourceType.CREDIT_CARD,
                            Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray()),
                            ProvisionalFundingSourceState.PROVISIONING
                        )
                    )
                ),
            "checkoutCard" to
                SetupFundingSourceMutation.SetupFundingSource(
                    "SetupFundingSource",
                    SetupFundingSourceMutation.SetupFundingSource.Fragments(
                        ProvisionalFundingSourceFragment(
                            "ProvisionalFundingSource",
                            "id",
                            "owner",
                            1,
                            1.0,
                            10.0,
                            FundingSourceType.CREDIT_CARD,
                            Base64.encodeBase64String(Gson().toJson(checkoutCardSetupData).toByteArray()),
                            ProvisionalFundingSourceState.PROVISIONING
                        )
                    )
                ),
            "checkoutBankAccount" to
                SetupFundingSourceMutation.SetupFundingSource(
                    "SetupFundingSource",
                    SetupFundingSourceMutation.SetupFundingSource.Fragments(
                        ProvisionalFundingSourceFragment(
                            "ProvisionalFundingSource",
                            "id",
                            "owner",
                            1,
                            1.0,
                            10.0,
                            FundingSourceType.BANK_ACCOUNT,
                            Base64.encodeBase64String(Gson().toJson(checkoutBankAccountSetupData).toByteArray()),
                            ProvisionalFundingSourceState.PROVISIONING
                        )
                    )
                )
        )
    }

    private val mutationResponse by before {
        val stripeRequest = mutationRequest["stripe"] ?: throw AssertionError("Invalid stripe setup")
        val stripeResult = mutationResult["stripe"] ?: throw AssertionError("Invalid stripe setup")
        val checkoutCardRequest = mutationRequest["checkoutCard"] ?: throw AssertionError("Invalid checkout setup")
        val checkoutCardResult = mutationResult["checkoutCard"] ?: throw AssertionError("Invalid checkout setup")
        val checkoutBankAccountRequest = mutationRequest["checkoutBankAccount"] ?: throw AssertionError("Invalid checkout setup")
        val checkoutBankAccountResult = mutationResult["checkoutBankAccount"] ?: throw AssertionError("Invalid checkout setup")

        mapOf(
            "stripe" to
                Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(stripeRequest))
                    .data(SetupFundingSourceMutation.Data(stripeResult))
                    .build(),
            "checkoutCard" to
                Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(checkoutCardRequest))
                    .data(SetupFundingSourceMutation.Data(checkoutCardResult))
                    .build(),
            "checkoutBankAccount" to
                Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(checkoutBankAccountRequest))
                    .data(SetupFundingSourceMutation.Data(checkoutBankAccountResult))
                    .build()
        )
    }

    private val mutationHolder = CallbackHolder<SetupFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doReturn mutationHolder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @Before
    fun init() {
        mutationHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockKeyManager,
            mockAppSyncClient
        )
    }

    @Test
    fun `setupFundingSource() should return results when no error present`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse[provider] ?: throw missingProvider(provider))

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

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when response is null`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response
                .builder<SetupFundingSourceMutation.Data>(
                    SetupFundingSourceMutation(mutationRequest[provider] ?: throw missingProvider(provider))
                )
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an unsupported currency error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val input by before {
            SetupFundingSourceInput(
                "AUD",
                FundingSourceTypeEntity.CREDIT_CARD,
                ClientApplicationData("system-test-app")
            )
        }

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnsupportedCurrencyError")
            )
            Response
                .builder<SetupFundingSourceMutation.Data>(
                    SetupFundingSourceMutation(mutationRequest[provider] ?: throw missingProvider(provider))
                )
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "AUD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an account locked error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError")
            )
            Response
                .builder<SetupFundingSourceMutation.Data>(
                    SetupFundingSourceMutation(mutationRequest[provider] ?: throw missingProvider(provider))
                )
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an entitlements exceeded error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "EntitlementExceededError")
            )
            Response
                .builder<SetupFundingSourceMutation.Data>(
                    SetupFundingSourceMutation(mutationRequest[provider] ?: throw missingProvider(provider))
                )
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.EntitlementExceededException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when a velocity exceeded error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "VelocityExceededError")
            )
            Response
                .builder<SetupFundingSourceMutation.Data>(
                    SetupFundingSourceMutation(mutationRequest[provider] ?: throw missingProvider(provider))
                )
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.VelocityExceededException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)

        val request = okhttp3.Request.Builder()
            .get()
            .url("http://www.smh.com.au")
            .build()
        val responseBody = "{}".toResponseBody("application/json; charset=utf-8".toMediaType())
        val forbidden = okhttp3.Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .code(HttpURLConnection.HTTP_FORBIDDEN)
            .request(request)
            .message("Forbidden")
            .body(responseBody)
            .build()

        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }

    @Test
    fun `setupFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.setupFundingSource(input[provider] ?: throw missingProvider(provider))
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe mutationRequest[provider]?.type()
            }
        )
    }
}
