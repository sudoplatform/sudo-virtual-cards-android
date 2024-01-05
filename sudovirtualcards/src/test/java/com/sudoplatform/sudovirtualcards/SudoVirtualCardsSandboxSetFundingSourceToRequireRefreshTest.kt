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
import com.sudoplatform.sudovirtualcards.graphql.SandboxSetFundingSourceToRequireRefreshMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType
import com.sudoplatform.sudovirtualcards.graphql.type.SandboxSetFundingSourceToRequireRefreshRequest
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
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
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL

/**
 * Test the correct operation of [SudoVirtualCardsClient.sandboxSetFundingSourceToRequireRefresh]
 * using mocks and spies.
 */
class SudoVirtualCardsSandboxSetFundingSourceToRequireRefreshTest : BaseTests() {
    private val input = SandboxSetFundingSourceToRequireRefreshRequest
        .builder()
        .fundingSourceId("fundingSourceId")
        .build()

    private val bankAccountResult by before {
        SandboxSetFundingSourceToRequireRefreshMutation.SandboxSetFundingSourceToRequireRefresh(
            "BankAccountFundingSource",
            null,
            SandboxSetFundingSourceToRequireRefreshMutation.AsBankAccountFundingSource(
                "BankAccountFundingSource",
                SandboxSetFundingSourceToRequireRefreshMutation.AsBankAccountFundingSource.Fragments(
                    BankAccountFundingSourceGraphQL(
                        "BankAccountFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceStateGraphQL.INACTIVE,
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
                    ),
                ),
            ),
        )
    }

    private val bankAccountResponse by before {
        Response.builder<SandboxSetFundingSourceToRequireRefreshMutation.Data>(SandboxSetFundingSourceToRequireRefreshMutation(input))
            .data(SandboxSetFundingSourceToRequireRefreshMutation.Data(bankAccountResult))
            .build()
    }

    private val holder = CallbackHolder<SandboxSetFundingSourceToRequireRefreshMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>()) } doReturn holder.mutationOperation
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
    fun `sandboxSetFundingSourceToRequireRefresh() should return results when no error present`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(bankAccountResponse)

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
                    state shouldBe FundingSourceState.INACTIVE
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

        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should throw when mutation response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<SandboxSetFundingSourceToRequireRefreshMutation.Data>(SandboxSetFundingSourceToRequireRefreshMutation(input))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should throw when response has a funding source not found error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError"),
            )
            Response.builder<SandboxSetFundingSourceToRequireRefreshMutation.Data>(SandboxSetFundingSourceToRequireRefreshMutation(input))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should throw when response has an account locked error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError"),
            )
            Response.builder<SandboxSetFundingSourceToRequireRefreshMutation.Data>(SandboxSetFundingSourceToRequireRefreshMutation(input))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should throw when http error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
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

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should throw when unknown error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }

    @Test
    fun `sandboxSetFundingSourceToRequireRefresh() should not suppress CancellationException`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>()) } doThrow CancellationException(
                "Mock Cancellation Exception",
            )
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.sandboxSetFundingSourceToRequireRefresh("fundingSourceId")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<SandboxSetFundingSourceToRequireRefreshMutation>())
    }
}
