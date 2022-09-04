/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCurrencyAmountAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedMarkupAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransactionDetailChargeAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
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
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.getTransaction]
 * using mocks and spies.
 */
class SudoVirtualCardsGetTransactionTest : BaseTests() {

    private fun mockSeal(value: String): String {
        val valueBytes = value.toByteArray()
        val data = ByteArray(256)
        valueBytes.copyInto(data)
        return String(Base64.encode(data), Charsets.UTF_8)
    }

    private val queryResult by before {
        GetTransactionQuery.GetTransaction(
            "GetTransaction",
            GetTransactionQuery.GetTransaction.Fragments(
                SealedTransaction(
                    "SealedTransaction",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    1.0,
                    "algorithm",
                    "keyId",
                    "cardId",
                    "sequenceId",
                    TransactionType.COMPLETE,
                    mockSeal("transactedAt"),
                    mockSeal("settledAt"),
                    SealedTransaction.BilledAmount(
                        "BilledAmount",
                        SealedTransaction.BilledAmount.Fragments(
                            SealedCurrencyAmountAttribute(
                                "CurrencyAmount",
                                mockSeal("USD"),
                                mockSeal("billedAmount")
                            )
                        )
                    ),
                    SealedTransaction.TransactedAmount(
                        "TransactedAmount",
                        SealedTransaction.TransactedAmount.Fragments(
                            SealedCurrencyAmountAttribute(
                                "CurrencyAmount",
                                mockSeal("USD"),
                                mockSeal("transactedAmount")
                            )
                        )
                    ),
                    mockSeal("description"),
                    null,
                    listOf(
                        SealedTransaction.Detail(
                            "typename",
                            SealedTransaction.Detail.Fragments(
                                SealedTransactionDetailChargeAttribute(
                                    "SealedTransactionDetailChargeAttribute",
                                    SealedTransactionDetailChargeAttribute.VirtualCardAmount(
                                        "VirtualCardAmount",
                                        SealedTransactionDetailChargeAttribute.VirtualCardAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("virtualCardAmount")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.Markup(
                                        "Markup",
                                        SealedTransactionDetailChargeAttribute.Markup.Fragments(
                                            SealedMarkupAttribute(
                                                "SealedMarkupAttribute",
                                                mockSeal("1"),
                                                mockSeal("2"),
                                                mockSeal("3")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.MarkupAmount(
                                        "MarkupAmount",
                                        SealedTransactionDetailChargeAttribute.MarkupAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("markupAmount")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.FundingSourceAmount(
                                        "FundingSourceAmount",
                                        SealedTransactionDetailChargeAttribute.FundingSourceAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("fundingSourceAmount")
                                            )
                                        )
                                    ),
                                    "fundingSourceId",
                                    mockSeal("description")
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private val queryResponse by before {
        Response.builder<GetTransactionQuery.Data>(GetTransactionQuery("id", "keyId"))
            .data(GetTransactionQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<GetTransactionQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "owner"
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

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetTransactionQuery>()) } doReturn queryHolder.queryOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
            on { getPassword(anyString()) } doReturn ByteArray(42)
            on { getPublicKeyData(anyString()) } doReturn ByteArray(42)
            on { getPrivateKeyData(anyString()) } doReturn ByteArray(42)
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
            .setLogger(mock())
            .setPublicKeyService(mockPublicKeyService)
            .build()
    }

    @Before
    fun init() {
        queryHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockPublicKeyService, mockAppSyncClient)
    }

    @Test
    fun `getTransaction() should return results when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getTransaction("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        checkTransaction(result!!)

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
        verify(mockKeyManager, times(16)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(16)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    private fun checkTransaction(transaction: Transaction) {
        with(transaction) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            cardId shouldNotBe null
            type shouldBe TransactionTypeEntity.COMPLETE
            description.isBlank() shouldBe false
            sequenceId shouldBe "sequenceId"
            transactedAt shouldNotBe null
            billedAmount.currency.isBlank() shouldBe false
            transactedAmount.currency.isBlank() shouldBe false
            declineReason shouldBe null
            createdAt shouldBe java.util.Date(1L)
            updatedAt shouldBe java.util.Date(1L)
            details.isEmpty() shouldBe false
            details[0].fundingSourceId shouldBe "fundingSourceId"
            details[0].description.isBlank() shouldBe false
        }
    }

    @Test
    fun `getTransaction() should return null result when query result data is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val responseWithNullResult by before {
            Response.builder<GetTransactionQuery.Data>(GetTransactionQuery("id", "keyId"))
                .data(GetTransactionQuery.Data(null))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getTransaction("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(responseWithNullResult)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should return null result when query response is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<GetTransactionQuery.Data>(GetTransactionQuery("id", "keyId"))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getTransaction("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should return null when query response has no transaction found error`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val errorQueryResponse by before {
            Response.builder<GetTransactionQuery.Data>(GetTransactionQuery("id", "keyId"))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getTransaction("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(errorQueryResponse)

        deferredResult.await() shouldBe null

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should throw current key pair retrieval returns null`() = runBlocking<Unit> {

        mockPublicKeyService.stub {
            onBlocking { getCurrentKey() } doReturn null
        }

        shouldThrow<SudoVirtualCardsClient.TransactionException.PublicKeyException> {
            client.getTransaction("id")
        }

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should throw when unsealing fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<GetTransactionQuery>()) } doThrow
                Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
        }

        shouldThrow<SudoVirtualCardsClient.TransactionException.UnsealingException> {
            client.getTransaction("id")
        }

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should throw when http error occurs`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.TransactionException.FailedException> {
                client.getTransaction("id")
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

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should throw when unknown error occurs`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetTransactionQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        shouldThrow<SudoVirtualCardsClient.TransactionException.UnknownException> {
            client.getTransaction("id")
        }

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getTransaction() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<GetTransactionQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.getTransaction("id")
        }

        verify(mockAppSyncClient).query(any<GetTransactionQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }
}
