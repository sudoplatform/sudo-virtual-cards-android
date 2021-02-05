/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.type.DeltaAction
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString

/**
 * Test the correct operation of [SudoVirtualCardsClient.getProvisionalCard] using mocks and spies.
 *
 * @since 2020-06-23
 */
class SudoVirtualCardsGetProvisionalCardTest : BaseTests() {

    private val queryResult by before {
        GetProvisionalCardQuery.GetProvisionalCard(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            "clientRefId",
            ProvisioningState.PROVISIONING,
            null,
            DeltaAction.DELETE
        )
    }

    private val queryResponse by before {
        Response.builder<GetProvisionalCardQuery.Data>(GetProvisionalCardQuery("id"))
            .data(GetProvisionalCardQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<GetProvisionalCardQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetProvisionalCardQuery>()) } doReturn queryHolder.queryOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
            on { getPassword(anyString()) } doReturn ByteArray(42)
            on { getPublicKeyData(anyString()) } doReturn ByteArray(42)
            on { getPrivateKeyData(anyString()) } doReturn ByteArray(42)
        }
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    private fun resetCallbacks() {
        queryHolder.callback = null
    }

    @Before
    fun init() {
        resetCallbacks()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockSudoClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `getProvisionalCard() should return results when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getProvisionalCard("id")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result!!) {
            id shouldBe "id"
            clientRefId shouldBe "clientRefId"
            owner shouldBe "owner"
            version shouldBe 1
            state shouldBe ProvisionalCard.State.PROVISIONING
            card shouldBe null
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
        }

        verify(mockAppSyncClient).query(any<GetProvisionalCardQuery>())
    }

    @Test
    fun `getProvisionalCard() should throw when query response is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<GetProvisionalCardQuery.Data>(GetProvisionalCardQuery("id"))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.CardException.FailedException> {
                client.getProvisionalCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        verify(mockAppSyncClient).query(any<GetProvisionalCardQuery>())
    }

    @Test
    fun `getProvisionalCard() should throw when query response has errors`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val errorQueryResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<GetProvisionalCardQuery.Data>(GetProvisionalCardQuery("id"))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.CardException.IdentityVerificationException> {
                client.getProvisionalCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(errorQueryResponse)

        verify(mockAppSyncClient).query(any<GetProvisionalCardQuery>())
    }

    @Test
    fun `getProvisionalCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<GetProvisionalCardQuery>()) } doThrow CancellationException("mock")
        }

        shouldThrow<CancellationException> {
            client.getProvisionalCard("id")
        }
        verify(mockAppSyncClient).query(any<GetProvisionalCardQuery>())
    }

    @Test
    fun `getProvisionalCard() should throw when key registration fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<GetProvisionalCardQuery>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException("mock")
        }

        shouldThrow<SudoVirtualCardsClient.CardException.UnsealingException> {
            client.getProvisionalCard("id")
        }
        verify(mockAppSyncClient).query(any<GetProvisionalCardQuery>())
    }
}
