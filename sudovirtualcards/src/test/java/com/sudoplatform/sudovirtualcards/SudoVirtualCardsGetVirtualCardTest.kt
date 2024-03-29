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
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAddressAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedExpiryAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection
import java.util.Date
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.getVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsGetVirtualCardTest : BaseTests() {

    private val billingAddress by before {
        SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    mockSeal("addressLine1"),
                    mockSeal("addressLine2"),
                    mockSeal("city"),
                    mockSeal("state"),
                    mockSeal("postalCode"),
                    mockSeal("country"),
                ),
            ),
        )
    }

    private val expiry by before {
        SealedCard.Expiry(
            "Expiry",
            SealedCard.Expiry.Fragments(
                SealedExpiryAttribute(
                    "SealedExpiryAttribute",
                    mockSeal("01"),
                    mockSeal("2021"),
                ),
            ),
        )
    }

    private val queryResult by before {
        GetCardQuery.GetCard(
            "typename",
            GetCardQuery.GetCard.Fragments(
                SealedCardWithLastTransaction(
                    "SealedCardWithLastTransaction",
                    null,
                    SealedCardWithLastTransaction.Fragments(
                        SealedCard(
                            "SealedCard",
                            "id",
                            "owner",
                            1,
                            1.0,
                            1.0,
                            "algorithm",
                            "keyId",
                            "keyRingId",
                            emptyList(),
                            "fundingSourceId",
                            "currency",
                            CardState.ISSUED,
                            1.0,
                            null,
                            "last4",
                            mockSeal("cardHolder"),
                            mockSeal("alias"),
                            mockSeal("pan"),
                            mockSeal("csc"),
                            billingAddress,
                            expiry,
                            null,
                        ),
                    ),
                ),
            ),
        )
    }

    private val queryResponse by before {
        Response.builder<GetCardQuery.Data>(GetCardQuery("id", "keyId"))
            .data(GetCardQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<GetCardQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetCardQuery>()) } doReturn queryHolder.queryOperation
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
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockKeyManager,
            mockPublicKeyService,
            mockAppSyncClient,
        )
    }

    @Test
    fun `getVirtualCard() should return results when no error present`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCard("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result!!) {
            id shouldBe "id"
            owners shouldNotBe null
            owner shouldBe "owner"
            version shouldBe 1
            fundingSourceId shouldBe "fundingSourceId"
            state shouldBe CardStateEntity.ISSUED
            cardHolder shouldNotBe null
            alias shouldNotBe null
            last4 shouldBe "last4"
            cardNumber shouldNotBe null
            securityCode shouldNotBe null
            billingAddress shouldNotBe null
            expiry shouldNotBe null
            currency shouldBe "currency"
            activeTo shouldNotBe null
            cancelledAt shouldBe null
            createdAt shouldBe Date(1L)
            updatedAt shouldBe Date(1L)
        }

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `getVirtualCard() should return null result when query result data is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val responseWithNullResult by before {
            Response.builder<GetCardQuery.Data>(GetCardQuery("id", "keyId"))
                .data(GetCardQuery.Data(null))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCard("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(responseWithNullResult)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should return null result when query response is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<GetCardQuery.Data>(GetCardQuery("id", "keyId"))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCard("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should throw when query response has errors`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val errorQueryResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError"),
            )
            Response.builder<GetCardQuery.Data>(GetCardQuery("id", "keyId"))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.getVirtualCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(errorQueryResponse)

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should throw when current key pair retrieval returns null`() = runBlocking<Unit> {
        mockPublicKeyService.stub {
            onBlocking { getCurrentKey() } doReturn null
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.getVirtualCard("id")
        }

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should throw when unsealing fails`() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { query(any<GetCardQuery>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.getVirtualCard("id")
        }

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should throw when http error occurs`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.getVirtualCard("id")
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

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should throw when unknown error occurs()`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetCardQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.getVirtualCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `getVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { query(any<GetCardQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.getVirtualCard("id")
        }

        verify(mockAppSyncClient).query(any<GetCardQuery>())
        verify(mockPublicKeyService).getCurrentKey()
    }
}
