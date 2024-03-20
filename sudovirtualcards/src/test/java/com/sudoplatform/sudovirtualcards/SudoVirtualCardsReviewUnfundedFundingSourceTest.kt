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
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.ReviewUnfundedFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
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
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource as BankAccountFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.CreditCardFundingSource as CreditCardFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceFlags as GraphQLFlags
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL

/**
 * Test the correct operation of [SudoVirtualCardsClient.reviewUnfundedFundingSource]
 * using mocks and spies.
 */
@RunWith(Parameterized::class)
class SudoVirtualCardsReviewUnfundedFundingSourceTest(private val provider: String) : BaseTests() {
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

    private val idInput = IdInput.builder()
        .id("id")
        .build()

    private val creditCardResult by before {
        ReviewUnfundedFundingSourceMutation.ReviewUnfundedFundingSource(
            "CreditCardFundingSource",
            ReviewUnfundedFundingSourceMutation.AsCreditCardFundingSource(
                "CreditCardFundingSource",
                ReviewUnfundedFundingSourceMutation.AsCreditCardFundingSource.Fragments(
                    CreditCardFundingSourceGraphQL(
                        "CreditCardFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.INACTIVE,
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
        ReviewUnfundedFundingSourceMutation.ReviewUnfundedFundingSource(
            "BankAccountFundingSource",
            null,
            ReviewUnfundedFundingSourceMutation.AsBankAccountFundingSource(
                "BankAccountFundingSource",
                ReviewUnfundedFundingSourceMutation.AsBankAccountFundingSource.Fragments(
                    BankAccountFundingSourceGraphQL(
                        "BankAccountFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.INACTIVE,
                        listOf(GraphQLFlags.UNFUNDED),
                        "USD",
                        BankAccountFundingSourceGraphQL.TransactionVelocity(
                            "TransactionVelocity",
                            10000,
                            listOf("10000/P1D"),
                        ),
                        BankAccountType.CHECKING,
                        BankAccountFundingSourceGraphQL.Authorization(
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
                        BankAccountFundingSourceGraphQL.InstitutionName(
                            "InstitutionName",
                            BankAccountFundingSourceGraphQL.InstitutionName.Fragments(
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
        Response.builder<ReviewUnfundedFundingSourceMutation.Data>(ReviewUnfundedFundingSourceMutation(idInput))
            .data(ReviewUnfundedFundingSourceMutation.Data(creditCardResult))
            .build()
    }

    private val bankAccountResponse by before {
        Response.builder<ReviewUnfundedFundingSourceMutation.Data>(ReviewUnfundedFundingSourceMutation(idInput))
            .data(ReviewUnfundedFundingSourceMutation.Data(bankAccountResult))
            .build()
    }

    private val mutationResponse by before {
        mapOf(
            "stripe" to creditCardResponse,
            "checkoutCard" to creditCardResponse,
            "checkoutBankAccount" to bankAccountResponse,
        )
    }

    private val holder = CallbackHolder<ReviewUnfundedFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<ReviewUnfundedFundingSourceMutation>()) } doReturn holder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doReturn ByteArray(42)
            on { decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>()) } doReturn "42".toByteArray()
        }
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
        holder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `ReviewUnfundedFundingSource() should return results when no error present`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.reviewUnfundedFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(mutationResponse[provider] ?: throw missingProvider(provider))

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
                    state shouldBe FundingSourceState.INACTIVE
                    currency shouldBe "USD"
                    last4 shouldBe "last4"
                    network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
                }
            }
            is BankAccountFundingSource -> {
                with(result) {
                    id shouldBe "id"
                    owner shouldBe "owner"
                    version shouldBe 1
                    createdAt shouldNotBe null
                    updatedAt shouldNotBe null
                    state shouldBe FundingSourceState.INACTIVE
                    flags shouldBe listOf(FundingSourceFlags.UNFUNDED)
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
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }
        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }

    @Test
    fun `ReviewUnfundedFundingSource() should throw when mutation response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<ReviewUnfundedFundingSourceMutation.Data>(ReviewUnfundedFundingSourceMutation(idInput))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ReviewFailedException> {
                client.reviewUnfundedFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }

    @Test
    fun `ReviewUnfundedFundingSource() should throw when response has a funding source not found error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorReviewResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError"),
            )
            Response.builder<ReviewUnfundedFundingSourceMutation.Data>(ReviewUnfundedFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.reviewUnfundedFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorReviewResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }

    @Test
    fun `ReviewUnfundedFundingSource() should throw when response has an account locked error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorReviewResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError"),
            )
            Response.builder<ReviewUnfundedFundingSourceMutation.Data>(ReviewUnfundedFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.reviewUnfundedFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorReviewResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }

    @Test
    fun `ReviewUnfundedFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ReviewFailedException> {
                client.reviewUnfundedFundingSource("id")
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

        holder.callback shouldNotBe null
        holder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }

    @Test
    fun `ReviewUnfundedFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<ReviewUnfundedFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.reviewUnfundedFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<ReviewUnfundedFundingSourceMutation>())
    }
}
