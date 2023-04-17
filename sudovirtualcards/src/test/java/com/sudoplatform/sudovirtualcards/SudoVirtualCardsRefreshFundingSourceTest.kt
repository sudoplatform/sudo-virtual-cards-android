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
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.type.RefreshFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL
import com.sudoplatform.sudovirtualcards.types.inputs.RefreshFundingSourceInput
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.RefreshFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType as BankAccountTypeGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource.Authorization as AuthorizationGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource as BankAccountFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource.InstitutionName as InstitutionNameGraphQL
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderRefreshData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountRefreshUserInteractionData
import com.sudoplatform.sudovirtualcards.types.ClientApplicationData
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.refreshFundingSource]
 * using mocks and spies.
 */
@RunWith(Parameterized::class)
class SudoVirtualCardsRefreshFundingSourceTest(private val provider: String) : BaseTests() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<String> {
            return listOf(
                "checkoutBankAccount"
            )
        }
    }
    private val authorizationText = AuthorizationText(
        "en-US",
        "content",
        "contentType",
        "hash",
        "hashAlgorithm"
    )
    private val providerRefreshData =
        mapOf(
            "checkoutBankAccount" to CheckoutBankAccountProviderRefreshData(
                "checkout",
                1,
                FundingSourceType.BANK_ACCOUNT,
                "account_id",
                authorizationText
            )
        )

    private val input by before {
        RefreshFundingSourceInput(
            "id",
            providerRefreshData[provider] ?: throw missingProvider(provider),
            ClientApplicationData("system-test-app"),
            "en-us"
        )
    }

    private val mutationRequest = RefreshFundingSourceRequest.builder()
        .id("id")
        .refreshData("refreshData")
        .language("en-us")
        .build()

    private val bankAccountResult by before {
        RefreshFundingSourceMutation.RefreshFundingSource(
            "BankAccountFundingSource",
            null,
            RefreshFundingSourceMutation.AsBankAccountFundingSource(
                "BankAccountFundingSource",
                RefreshFundingSourceMutation.AsBankAccountFundingSource.Fragments(
                    BankAccountFundingSourceGraphQL(
                        "BankAccountFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.ACTIVE,
                        "USD",
                        BankAccountFundingSourceGraphQL.TransactionVelocity(
                            "TransactionVelocity",
                            10000,
                            listOf("10000/P1D")
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
                            "data"
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
                                    mockSeal("base64EncodedSealedData")
                                )
                            )
                        ),
                        null
                    )
                )
            ),
        )
    }

    private val bankAccountResponse by before {
        Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
            .data(RefreshFundingSourceMutation.Data(bankAccountResult))
            .build()
    }

    private val mutationResponse by before {
        mapOf(
            "checkoutBankAccount" to bankAccountResponse
        )
    }

    private val mutationHolder = CallbackHolder<RefreshFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<RefreshFundingSourceMutation>()) } doReturn mutationHolder.mutationOperation
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
    fun `refreshFundingSource() should return results when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.refreshFundingSource(input)
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse[provider] ?: throw missingProvider(provider))

        val result = deferredResult.await()
        result shouldNotBe null

        when (result) {
            is BankAccountFundingSource -> {
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
                    bankAccountType shouldBe BankAccountFundingSource.BankAccountType.CHECKING
                    last4 shouldBe "last4"
                    institutionName shouldNotBe null
                    institutionLogo shouldBe null
                }
            }
            else -> {
                fail("Unexpected FundingSource type")
            }
        }

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.RefreshFailedException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when a funding source not found error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError")
            )
            Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when a funding source state error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceStateError")
            )
            Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when a completion data invalid error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceCompletionDataInvalidError")
            )
            Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionDataInvalidException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when a user interaction required funding source error occurs`() = runBlocking<Unit> {
        mutationHolder.callback shouldBe null
        val providerInteractionData = CheckoutBankAccountRefreshUserInteractionData(
            linkToken = "link-token",
            authorizationText = listOf(authorizationText)
        )
        val interactionData = SudoVirtualCardsClient.FundingSourceInteractionData(
            Base64.encode(Gson().toJson(providerInteractionData).toByteArray()).toString(Charsets.UTF_8)
        )

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf(
                    "errorType" to "FundingSourceRequiresUserInteractionError",
                    "errorInfo" to interactionData
                )
            )
            Response.builder<RefreshFundingSourceMutation.Data>(RefreshFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            val exception = shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceRequiresUserInteractionException> {
                client.refreshFundingSource(input)
            }
            println(exception.interactionData)
            exception.interactionData shouldBe providerInteractionData
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.RefreshFailedException> {
                client.refreshFundingSource(input)
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

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<RefreshFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `refreshFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<RefreshFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.refreshFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        if (provider == "checkoutBankAccount") {
            verify(mockAppSyncClient).mutate(any<RefreshFundingSourceMutation>())
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }
}
