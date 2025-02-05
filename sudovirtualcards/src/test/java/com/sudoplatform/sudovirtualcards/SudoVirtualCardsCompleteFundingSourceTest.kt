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
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.extensions.isUnfunded
import com.sudoplatform.sudovirtualcards.extensions.needsRefresh
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
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
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
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
        if (provider == "checkoutBankAccount") {
            null
        } else {
            val encodedCompletionDataString = Gson().toJson(input.completionData)
            Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
        }
    }

    private val creditCardResponse by before {
        JSONObject(
            """
                {
                    'completeFundingSource': {
                            '__typename': 'CreditCardFundingSource',
                            'id':'id',
                            'owner': 'owner',
                            'version': 1,
                            'createdAtEpochMs': 1.0,
                            'updatedAtEpochMs': 10.0,
                            'state': '${FundingSourceStateGraphQL.ACTIVE}',
                            'flags': [],
                            'currency':'USD',
                            'transactionVelocity': {
                                'maximum': 10000,
                                'velocity': ['10000/P1D']
                            },
                            'last4':'last4',
                            'network':'${CreditCardNetwork.VISA}',
                            'cardType': '${CardType.CREDIT}'
                        }
                }
            """.trimIndent(),
        )
    }

    private val bankAccountResponse by before {
        JSONObject(
            """
                {
                    'completeFundingSource': {
                        '__typename': 'BankAccountFundingSource',
                        'id':'id',
                        'owner': 'owner',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 10.0,
                        'state': '${FundingSourceStateGraphQL.ACTIVE}',
                        'flags': ['${FundingSourceFlagsGraphQL.UNFUNDED}'],
                        'currency':'USD',
                        'transactionVelocity': {
                            'maximum': 10000,
                            'velocity': ['10000/P1D']
                        },
                        'bankAccountType': '${BankAccountType.CHECKING}',
                        'authorization': {
                            'language': 'language',
                            'content': 'content',
                            'algorithm': 'algorithm',
                            'contentType': 'contentType',
                            'signature': 'signature',
                            'keyId': 'keyId',
                            'data': 'data'
                        },
                        'last4':'last4',
                        'institutionName': {
                            '__typename': 'InstitutionName',
                            'algorithm': 'algorithm',
                            'plainTextType': 'string',
                            'keyId': 'keyId',
                            'base64EncodedSealedData': '${mockSeal("base64EncodedSealedData")}'
                        }
                    }
                }
            """.trimIndent(),
        )
    }

    private val mutationResponse by before {
        mapOf(
            "stripe" to creditCardResponse,
            "checkoutBankAccount" to bankAccountResponse,
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
                    argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setPublicKeyService(mockPublicKeyService)
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
    fun `completeFundingSource() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.completeFundingSource(input)
        }
        deferredResult.start()

        delay(100L)

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

        verifyCompleteFundingSourceMutation()

        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }
    }

    @Test
    fun `completeFundingSource() should throw when response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a provisional funding source not found error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "ProvisionalFundingSourceNotFoundError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a funding source state error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "FundingSourceStateError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a funding source not setup error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "FundingSourceNotSetupErrorCode"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when a completion data invalid error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "FundingSourceCompletionDataInvalidError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionDataInvalidException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when an unacceptable funding source error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "UnacceptableFundingSourceError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
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
                argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verifyCompleteFundingSourceMutation()
        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    @Test
    fun `completeFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CompleteFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifyCompleteFundingSourceMutation()

        if (provider == "checkoutBankAccount") {
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager).generateSignatureWithPrivateKey(anyString(), any())
        }
    }

    private fun verifyCompleteFundingSourceMutation() {
        verify(mockApiCategory).mutate<String>(
            check {
                assertEquals(CompleteFundingSourceMutation.OPERATION_DOCUMENT, it.query)
                val input = it.variables["input"] as CompleteFundingSourceRequest?
                input?.id shouldBe "id"
                if (encodedCompletionData != null) {
                    // we are not recreating the bank account completion data in any of these tests...
                    input?.completionData shouldBe encodedCompletionData
                }
                input?.updateCardFundingSource?.getOrNull() shouldBe null
            },
            any(),
            any(),
        )
    }
}
