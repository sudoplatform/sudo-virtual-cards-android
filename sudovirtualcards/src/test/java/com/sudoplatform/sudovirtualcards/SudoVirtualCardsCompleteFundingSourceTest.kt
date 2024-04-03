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
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.extensions.isUnfunded
import com.sudoplatform.sudovirtualcards.extensions.needsRefresh
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardUserInteractionData
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.StripeCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
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
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.anyString
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
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource as BankAccountFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource.Authorization as AuthorizationGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource.InstitutionName as InstitutionNameGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.CreditCardFundingSource as CreditCardFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType as BankAccountTypeGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceFlags as FundingSourceFlagsGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL

/**
 * Test the correct operation of [SudoVirtualCardsClient.completeFundingSource]
 * using mocks and spies.
 */
@RunWith(Parameterized::class)
class SudoVirtualCardsCompleteFundingSourceTest(private val provider: String) : BaseTests() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<String> {
            return listOf(
                "stripe",
                "checkoutCard",
                "checkoutBankAccount",
            )
        }
    }

    private val providerCompletionData =
        mapOf(
            "stripe" to StripeCardProviderCompletionData(
                "stripe",
                1,
                "paymentMethod",
                FundingSourceType.CREDIT_CARD,
            ),
            "checkoutCard" to CheckoutCardProviderCompletionData(
                "checkout",
                1,
                FundingSourceType.CREDIT_CARD,
                "payment_token",
            ),
            "checkoutBankAccount" to CheckoutBankAccountProviderCompletionData(
                "checkout",
                1,
                FundingSourceType.BANK_ACCOUNT,
                "public_token",
                "account_id",
                "institutionId",
                AuthorizationText(
                    "language",
                    "content",
                    "contentType",
                    "hash",
                    "hashAlgorithm",
                ),
            ),
        )

    private val input by before {
        CompleteFundingSourceInput(
            "id",
            providerCompletionData[provider] ?: throw missingProvider(provider),
            null,
        )
    }

    private val encodedCompletionData by before {
        val encodedCompletionDataString = Gson().toJson(input.completionData)
        Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
    }

    private val mutationRequest = CompleteFundingSourceRequest.builder()
        .id("id")
        .completionData("completionData")
        .build()

    private val creditCardResult by before {
        CompleteFundingSourceMutation.CompleteFundingSource(
            "CreditCardFundingSource",
            CompleteFundingSourceMutation.AsCreditCardFundingSource(
                "CreditCardFundingSource",
                CompleteFundingSourceMutation.AsCreditCardFundingSource.Fragments(
                    CreditCardFundingSourceGraphQL(
                        "CreditCardFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.ACTIVE,
                        emptyList(),
                        "USD",
                        CreditCardFundingSourceGraphQL.TransactionVelocity(
                            "TransactionVelocity",
                            10000,
                            listOf("10000/P1D"),
                        ),
                        "last4",
                        CreditCardNetwork.VISA,
                        CardType.CREDIT,
                    ),
                ),
            ),
            null,
        )
    }

    private val bankAccountResult by before {
        CompleteFundingSourceMutation.CompleteFundingSource(
            "BankAccountFundingSource",
            null,
            CompleteFundingSourceMutation.AsBankAccountFundingSource(
                "BankAccountFundingSource",
                CompleteFundingSourceMutation.AsBankAccountFundingSource.Fragments(
                    BankAccountFundingSourceGraphQL(
                        "BankAccountFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.ACTIVE,
                        listOf(FundingSourceFlagsGraphQL.UNFUNDED),
                        "USD",
                        BankAccountFundingSourceGraphQL.TransactionVelocity(
                            "TransactionVelocity",
                            10000,
                            listOf("10000/P1D"),
                        ),
                        BankAccountTypeGraphQL.CHECKING,
                        AuthorizationGraphQL(
                            "Authorization",
                            "language",
                            "content",
                            "contentType",
                            "signature",
                            "keyId",
                            "algorithm",
                            "data",
                        ),
                        "last4",
                        InstitutionNameGraphQL(
                            "InstitutionName",
                            InstitutionNameGraphQL.Fragments(
                                SealedAttribute(
                                    "typename",
                                    "keyId",
                                    "algorithm",
                                    "string",
                                    mockSeal("base64EncodedSealedData"),
                                ),
                            ),
                        ),
                        null,
                        null,
                    ),
                ),
            ),
        )
    }

    private val creditCardResponse by before {
        Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
            .data(CompleteFundingSourceMutation.Data(creditCardResult))
            .build()
    }

    private val bankAccountResponse by before {
        Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
            .data(CompleteFundingSourceMutation.Data(bankAccountResult))
            .build()
    }

    private val mutationResponse by before {
        mapOf(
            "stripe" to creditCardResponse,
            "checkoutCard" to creditCardResponse,
            "checkoutBankAccount" to bankAccountResponse,
        )
    }

    private val mutationHolder = CallbackHolder<CompleteFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doReturn mutationHolder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
            on { generateSignatureWithPrivateKey(anyString(), any()) } doReturn ByteArray(42)
            on { decryptWithPrivateKey(anyString(), any(), any()) } doReturn ByteArray(42)
            on { decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>()) } doReturn "42".toByteArray()
        }
    }

    private val currentKey = PublicKey(
        keyId = "keyId",
        publicKey = "publicKey".toByteArray(),
    )

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getCurrentKey() } doReturn currentKey
        }
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setPublicKeyService(mockPublicKeyService)
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
            mockAppSyncClient,
        )
    }

    @Test
    fun `completeFundingSource() should return results when no error present`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.completeFundingSource(input)
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse[provider] ?: throw missingProvider(provider))

        val result = deferredResult.await()
        result shouldNotBe null

        when (result) {
            is CreditCardFundingSource -> {
                with(result) {
                    id shouldBe "id"
                    owner shouldBe "owner"
                    version shouldBe 1
                    createdAt shouldNotBe null
                    updatedAt shouldNotBe null
                    state shouldBe FundingSourceState.ACTIVE
                    currency shouldBe "USD"
                    transactionVelocity?.maximum shouldBe 10000
                    transactionVelocity?.velocity shouldBe listOf("10000/P1D")
                    last4 shouldBe "last4"
                    network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
                }
                result.isUnfunded() shouldBe false
                result.needsRefresh() shouldBe false
            }
            is BankAccountFundingSource -> {
                with(result) {
                    id shouldBe "id"
                    owner shouldBe "owner"
                    version shouldBe 1
                    createdAt shouldNotBe null
                    updatedAt shouldNotBe null
                    state shouldBe FundingSourceState.ACTIVE
                    flags shouldBe listOf(FundingSourceFlags.UNFUNDED)
                    currency shouldBe "USD"
                    transactionVelocity?.maximum shouldBe 10000
                    transactionVelocity?.velocity shouldBe listOf("10000/P1D")
                    bankAccountType shouldBe BankAccountFundingSource.BankAccountType.CHECKING
                    last4 shouldBe "last4"
                    institutionName shouldNotBe null
                    institutionLogo shouldBe null
                }
                result.isUnfunded() shouldBe true
                result.needsRefresh() shouldBe false
            }
            else -> {
                fail("Unexpected FundingSource type")
            }
        }

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }
    }

    @Test
    fun `completeFundingSource() should throw when response is null`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a provisional funding source not found error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "ProvisionalFundingSourceNotFoundError"),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a funding source state error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceStateError"),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a funding source not setup error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotSetupErrorCode"),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a completion data invalid error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceCompletionDataInvalidError"),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionDataInvalidException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when an unacceptable funding source error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnacceptableFundingSourceError"),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a user interaction required funding source error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null
        val providerInteractionData = CheckoutCardUserInteractionData(redirectUrl = "https://some.url.com/session")
        val interactionData = SudoVirtualCardsClient.FundingSourceInteractionData(
            Base64.encode(Gson().toJson(providerInteractionData).toByteArray()).toString(Charsets.UTF_8),
        )

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf(
                    "errorType" to "FundingSourceRequiresUserInteractionError",
                    "errorInfo" to interactionData,
                ),
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            val exception = shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceRequiresUserInteractionException> {
                client.completeFundingSource(input)
            }
            exception.interactionData shouldBe providerInteractionData
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
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

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        if (provider == "stripe" || provider == "checkoutCard") {
            verify(mockAppSyncClient).mutate<
                CompleteFundingSourceMutation.Data,
                CompleteFundingSourceMutation,
                CompleteFundingSourceMutation.Variables,
                >(
                check {
                    it.variables().input().id() shouldBe "id"
                    it.variables().input().completionData() shouldBe encodedCompletionData
                    it.variables().input().updateCardFundingSource() shouldBe null
                },
            )
        }
        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }
}
