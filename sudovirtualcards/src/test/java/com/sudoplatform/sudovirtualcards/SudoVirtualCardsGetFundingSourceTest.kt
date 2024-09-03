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
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceFlags as FundingSourceFlagsGraphQL
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as FundingSourceStateGraphQL

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
                "checkoutBankAccount",
            )
        }
    }

    private val creditCardResponse by before {
        JSONObject(
            """
                {
                    'getFundingSource': {
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
                    'getFundingSource': {
                        '__typename': 'BankAccountFundingSource',
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

    private val unfundedBankAccountResponse by before {
        JSONObject(
            """
                {
                    'getFundingSource': {
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
                        },
                        'unfundedAmount': {
                           'currency': 'USD',
                            'amount': 123
                        }
                    }
                }
            """.trimIndent(),
        )
    }

    private val queryResponse by before {
        mapOf(
            "stripe" to creditCardResponse,
            "checkoutCard" to creditCardResponse,
            "checkoutBankAccount" to bankAccountResponse,
            "unfundedBankAccount" to unfundedBankAccountResponse,
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
                query<String>(
                    argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
                    any(), any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                val responseToUse = queryResponse[provider] ?: throw missingProvider(provider)
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
            on { decryptWithPrivateKey(anyString(), any(), any()) } doReturn ByteArray(42)
            on { decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>()) } doReturn "42".toByteArray()
        }
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockApiCategory)
    }

    @Test
    fun `getFundingSource() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSource("id")
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
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getFundingSource() should interpret bank account funding source when no error present`() = runBlocking<Unit> {
        if (provider !== "checkoutBankAccount") {
            return@runBlocking
        }
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(unfundedBankAccountResponse.toString(), null),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSource("id")
        }
        deferredResult.start()
        delay(100L)
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
                    unfundedAmount shouldBe CurrencyAmount("USD", 123)
                }
            }
            else -> {
                fail("Unexpected FundingSource type")
            }
        }

        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getFundingSource() should return null result when query result data is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
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
            client.getFundingSource("id")
        }
        deferredResult.start()
        delay(100L)
        val result = deferredResult.await()
        result shouldBe null

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
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
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.getFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getFundingSource() should throw when unknown error occurs()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.getFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getFundingSource() should not suppress CancellationException()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetFundingSourceQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.getFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetFundingSourceQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
