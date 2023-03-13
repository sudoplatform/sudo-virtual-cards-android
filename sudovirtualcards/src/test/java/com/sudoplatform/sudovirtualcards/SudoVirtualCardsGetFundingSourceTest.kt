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
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource as BankAccountFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType
import com.sudoplatform.sudovirtualcards.graphql.fragment.CreditCardFundingSource as CreditCardFundingSourceGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
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
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.getFundingSource]
 * using mocks and spies.
 */
@RunWith(Parameterized::class)
class SudoVirtualCardsGetFundingSourceTest(private val provider: String) : BaseTests() {
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

    private val creditCardResult by before {
        GetFundingSourceQuery.GetFundingSource(
            "CreditCardFundingSource",
            GetFundingSourceQuery.AsCreditCardFundingSource(
                "CreditCardFundingSource",
                GetFundingSourceQuery.AsCreditCardFundingSource.Fragments(
                    CreditCardFundingSourceGraphQL(
                        "CreditCardFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.ACTIVE,
                        "USD",
                        CreditCardFundingSourceGraphQL.TransactionVelocity(
                            "TransactionVelocity",
                            10000,
                            listOf("10000/P1D")
                        ),
                        "last4",
                        CreditCardNetwork.VISA,
                        CardType.CREDIT
                    )
                )
            ),
            null
        )
    }

    private val bankAccountResult by before {
        GetFundingSourceQuery.GetFundingSource(
            "BankAccountFundingSource",
            null,
            GetFundingSourceQuery.AsBankAccountFundingSource(
                "BankAccountFundingSource",
                GetFundingSourceQuery.AsBankAccountFundingSource.Fragments(
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
                        BankAccountType.CHECKING,
                        BankAccountFundingSourceGraphQL.Authorization(
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
                        BankAccountFundingSourceGraphQL.InstitutionName(
                            "InstitutionName",
                            BankAccountFundingSourceGraphQL.InstitutionName.Fragments(
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
            )
        )
    }

    private val creditCardResponse by before {
        Response.builder<GetFundingSourceQuery.Data>(GetFundingSourceQuery("id"))
            .data(GetFundingSourceQuery.Data(creditCardResult))
            .build()
    }

    private val bankAccountResponse by before {
        Response.builder<GetFundingSourceQuery.Data>(GetFundingSourceQuery("id"))
            .data(GetFundingSourceQuery.Data(bankAccountResult))
            .build()
    }

    private val queryResponse by before {
        mapOf(
            "stripe" to creditCardResponse,
            "checkoutCard" to creditCardResponse,
            "checkoutBankAccount" to bankAccountResponse
        )
    }

    private var holder = CallbackHolder<GetFundingSourceQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetFundingSourceQuery>()) } doReturn holder.queryOperation
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
    fun `getFundingSource() should return results when no error present`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(queryResponse[provider] ?: throw missingProvider(provider))

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
            }
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
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }
        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }

    @Test
    fun `getFundingSource() should return null result when query result data is null`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val responseWithNullResult by before {
            Response.builder<GetFundingSourceQuery.Data>(GetFundingSourceQuery("id"))
                .data(GetFundingSourceQuery.Data(null))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(responseWithNullResult)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }

    @Test
    fun `getFundingSource() should return null result when query response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<GetFundingSourceQuery.Data>(GetFundingSourceQuery("id"))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }

    @Test
    fun `getFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.getFundingSource("id")
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

        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }

    @Test
    fun `getFundingSource() should throw when unknown error occurs()`() = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.getFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }

    @Test
    fun `getFundingSource() should not suppress CancellationException()`
    () = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.getFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceQuery>())
    }
}
