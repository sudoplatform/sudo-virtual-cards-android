/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sudoplatform.sudoidentityverification.DefaultSudoIdentityVerificationClient
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudoprofiles.DefaultSudoProfilesClient
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudouser.DefaultSudoUserClient
import com.sudoplatform.sudouser.TESTAuthenticationProvider
import com.sudoplatform.sudovirtualcards.types.Card
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionCardInput
import com.sudoplatform.sudovirtualcards.util.LocaleUtil
import io.kotlintest.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Test the operation of the [SudoVirtualCardsClient].
 *
 * @since 2020-05-22
 */
abstract class BaseIntegrationTest {

    protected val context: Context = ApplicationProvider.getApplicationContext<Context>()

    protected val userClient by lazy {
        DefaultSudoUserClient(context, "vc-client-test")
    }

    protected val sudoClient by lazy {
        val containerURI = Uri.fromFile(context.cacheDir)
        DefaultSudoProfilesClient(context, userClient, containerURI)
    }

    private val identityVerificationClient by lazy {
        DefaultSudoIdentityVerificationClient(context, userClient)
    }

    protected val keyManager by lazy {
        KeyManagerFactory(context).createAndroidKeyManager()
    }

    protected suspend fun register() {
        userClient.isRegistered() shouldBe false

        val privateKey = readTextFile("register_key.private")
        val keyId = readTextFile("register_key.id")

        val authProvider = TESTAuthenticationProvider(
            name = "vc-client-test",
            privateKey = privateKey,
            publicKey = null,
            keyManager = keyManager,
            keyId = keyId
        )

        userClient.registerWithAuthenticationProvider(authProvider, "vc-client-test")
    }

    private fun readTextFile(fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use {
            it.readText().trim()
        }
    }

    protected suspend fun deregister() {
        userClient.deregister()
    }

    protected suspend fun signIn() {
        userClient.signInWithKey()
    }

    protected suspend fun signInAndRegister() {
        userClient.isRegistered() shouldBe false
        register()
        userClient.isRegistered() shouldBe true
        signIn()
        userClient.isSignedIn() shouldBe true
    }

    protected fun clientConfigFilesPresent(): Boolean {
        val configFiles = context.assets.list("")?.filter { fileName ->
            fileName == "sudoplatformconfig.json" ||
                fileName == "register_key.private" ||
                fileName == "register_key.id"
        } ?: emptyList()
        Timber.d("config files present ${configFiles.size}")
        return configFiles.size == 3
    }

    protected suspend fun verifyTestUserIdentity() {

        val countryCodeAlpha3 = LocaleUtil.toCountryCodeAlpha3(context, TestData.VerifiedUser.country)
            ?: throw IllegalArgumentException("Unable to convert country code to ISO 3166 Alpha-3")

        identityVerificationClient.verifyIdentity(
            firstName = TestData.VerifiedUser.firstName,
            lastName = TestData.VerifiedUser.lastName,
            address = TestData.VerifiedUser.addressLine1,
            city = TestData.VerifiedUser.city,
            state = TestData.VerifiedUser.state,
            postalCode = TestData.VerifiedUser.postalCode,
            country = countryCodeAlpha3,
            dateOfBirth = TestData.VerifiedUser.dateOfBirth
        )
    }

    protected suspend fun createSudo(sudoInput: Sudo): Sudo {
        return sudoClient.createSudo(sudoInput)
    }

    protected suspend fun provisionCard(input: ProvisionCardInput, client: SudoVirtualCardsClient): Card {
        val provisionalCard1 = client.provisionCard(input)
        var state = provisionalCard1.state

        return withTimeout<Card>(20_000L) {
            var card: Card? = null
            while (state == ProvisionalCard.State.PROVISIONING) {
                val provisionalCard2 = client.getProvisionalCard(provisionalCard1.id)
                if (provisionalCard2?.state == ProvisionalCard.State.COMPLETED) {
                    card = provisionalCard2.card
                } else {
                    delay(2_000L)
                }
                state = provisionalCard2?.state ?: ProvisionalCard.State.PROVISIONING
            }
            card ?: throw AssertionError("Provisioned card should not be null")
        }
    }
}
