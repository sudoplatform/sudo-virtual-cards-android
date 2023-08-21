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
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.SandboxGetPlaidDataQuery
import com.sudoplatform.sudovirtualcards.graphql.type.SandboxGetPlaidDataRequest
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.PlaidAccountMetadata
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.sandboxGetPlaidData]
 * using mocks and spies.
 */
class SudoVirtualCardsSandboxGetPlaidTest : BaseTests() {

    private val queryResult by before {
        SandboxGetPlaidDataQuery.SandboxGetPlaidData(
            "SandboxGetPlaidData",
            listOf(
                SandboxGetPlaidDataQuery.AccountMetadatum(
                    "AccountMetadata",
                    "checkingAccountId",
                    "checking"
                ),
                SandboxGetPlaidDataQuery.AccountMetadatum(
                    "AccountMetadata",
                    "savingsAccountId",
                    "savings"
                ),
                SandboxGetPlaidDataQuery.AccountMetadatum(
                    "AccountMetadata",
                    "otherAccountId",
                    "other"
                ),
                SandboxGetPlaidDataQuery.AccountMetadatum(
                    "AccountMetadata",
                    "unspecifiedAccountId",
                    null
                )
            ),
            "publicToken"
        )
    }

    private val queryResponse by before {
        Response.builder<SandboxGetPlaidDataQuery.Data>(
            SandboxGetPlaidDataQuery(
                SandboxGetPlaidDataRequest
                    .builder()
                    .institutionId("institutionId")
                    .username("plaidUsername")
                    .build()
            )
        )
            .data(SandboxGetPlaidDataQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<SandboxGetPlaidDataQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<SandboxGetPlaidDataQuery>()) } doReturn queryHolder.queryOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val mockPublicKeyService by before {
        mock<PublicKeyService>()
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
            mockAppSyncClient,
            mockKeyManager,
            mockPublicKeyService
        )
    }

    @Test
    fun `sandboxGetPlaidData() should return results when no error present`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.sandboxGetPlaidData("institutionId", "plaidUsername")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            accountMetadata shouldBe listOf(
                PlaidAccountMetadata("checkingAccountId", BankAccountFundingSource.BankAccountType.CHECKING),
                PlaidAccountMetadata("savingsAccountId", BankAccountFundingSource.BankAccountType.SAVING),
                PlaidAccountMetadata("otherAccountId", BankAccountFundingSource.BankAccountType.UNKNOWN),
                PlaidAccountMetadata("unspecifiedAccountId", BankAccountFundingSource.BankAccountType.UNKNOWN)
            )
            publicToken shouldBe "publicToken"
        }
        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }

    @Test
    fun `sandboxGetPlaidData() should throw when query response is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<SandboxGetPlaidDataQuery.Data>(
                SandboxGetPlaidDataQuery(
                    SandboxGetPlaidDataRequest
                        .builder()
                        .institutionId("institutionId")
                        .username("plaidUsername")
                        .build()
                )
            )
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }

    @Test
    fun `sandboxGetPlaidData() should throw when query response has errors`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val errorQueryResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<SandboxGetPlaidDataQuery.Data>(
                SandboxGetPlaidDataQuery(
                    SandboxGetPlaidDataRequest
                        .builder()
                        .institutionId("institutionId")
                        .username("plaidUsername")
                        .build()
                )
            )
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(errorQueryResponse)

        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }

    @Test
    fun `sandboxGetPlaidData() should throw when http error occurs`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
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

        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }

    @Test
    fun `sandboxGetPlaidData() should throw when unknown error occurs()`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<SandboxGetPlaidDataQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }

    @Test
    fun `sandboxGetPlaidData() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { query(any<SandboxGetPlaidDataQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.sandboxGetPlaidData("institutionId", "plaidUsername")
        }

        verify(mockAppSyncClient).query(any<SandboxGetPlaidDataQuery>())
    }
}
