package com.web3auth.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.web3auth.core.api.ApiHelper
import com.web3auth.core.api.Web3AuthApi
import com.web3auth.core.api.models.LogoutApiRequest
import com.web3auth.core.keystore.KeyStoreManagerUtils
import com.web3auth.core.types.*
import com.web3auth.core.types.Base64
import java8.util.concurrent.CompletableFuture
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.*

class Web3Auth(web3AuthOptions: Web3AuthOptions) {
    enum class Network {
        @SerializedName("mainnet")
        MAINNET,
        @SerializedName("testnet")
        TESTNET,
        @SerializedName("cyan")
        CYAN
    }

    private val gson = Gson()

    private val sdkUrl = Uri.parse(web3AuthOptions.sdkUrl)
    private val initParams: Map<String, Any>
    private val context : Context

    private var loginCompletableFuture: CompletableFuture<Web3AuthResponse> = CompletableFuture()
    private var logoutCompletableFuture: CompletableFuture<Void> = CompletableFuture()

    private var web3AuthResponse = Web3AuthResponse()
    private var shareMetadata = ShareMetadata()
    private val web3AuthApi = ApiHelper.getInstance().create(Web3AuthApi::class.java)
    private var sessionId: String? = null

    init {
        //initiate keyStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initiateKeyStoreManager()
        }

        //authorize sessionId
        sessionId = KeyStoreManagerUtils.decryptData(KeyStoreManagerUtils.SESSION_ID)
        if(sessionId != null && sessionId?.isNotEmpty() == true) {
            authorizeSession("04".plus(KeyStoreManagerUtils.getPubKey(sessionId.toString())))
        }

        // Build init params
        val initParams = mutableMapOf(
            "clientId" to web3AuthOptions.clientId,
            "network" to web3AuthOptions.network.name.lowercase(Locale.ROOT)
        )
        if (web3AuthOptions.redirectUrl != null) initParams["redirectUrl"] = web3AuthOptions.redirectUrl.toString()
        if (web3AuthOptions.whiteLabel != null) initParams["whiteLabel"] = gson.toJson(web3AuthOptions.whiteLabel)
        if (web3AuthOptions.loginConfig != null) initParams["loginConfig"] = gson.toJson(web3AuthOptions.loginConfig)

        this.initParams = initParams
        this.context = web3AuthOptions.context
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun initiateKeyStoreManager() {
        KeyStoreManagerUtils.getKeyGenerator()
    }

    private fun request(path: String, params: LoginParams? = null, extraParams: Map<String, Any>? = null) {
        val paramMap = mapOf(
            "init" to initParams,
            "params" to params
        )
        extraParams?.let{ paramMap.plus("params" to extraParams) }
        val validParams = paramMap.filterValues { it != null }

        val hash = gson.toJson(validParams).toByteArray(Charsets.UTF_8).toBase64URLString()

        val url = Uri.Builder().scheme(sdkUrl.scheme)
            .encodedAuthority(sdkUrl.encodedAuthority)
            .encodedPath(sdkUrl.encodedPath)
            .appendPath(path)
            .fragment(hash)
            .build()

        val defaultBrowser = context.getDefaultBrowser()
        val customTabsBrowsers = context.getCustomTabsBrowsers()

        if (customTabsBrowsers.contains(defaultBrowser)) {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.setPackage(defaultBrowser)
            customTabs.launchUrl(context, url)
        } else if (customTabsBrowsers.isNotEmpty()) {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.setPackage(customTabsBrowsers[0])
            customTabs.launchUrl(context, url)
        } else {
            // Open in browser externally
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
        }
    }

    fun setResultUrl(uri: Uri?) {
        val hash = uri?.fragment
        if (hash == null) {
            loginCompletableFuture.completeExceptionally(UserCancelledException())
            return
        }
        val error = uri.getQueryParameter("error")
        if (error != null) {
            loginCompletableFuture.completeExceptionally(UnKnownException(error))
        }

        web3AuthResponse = gson.fromJson(
            decodeBase64URLString(hash).toString(Charsets.UTF_8),
            Web3AuthResponse::class.java
        )
        if (web3AuthResponse.error?.isNotBlank() == true ) {
            loginCompletableFuture.completeExceptionally(UnKnownException(web3AuthResponse.error ?: "Something went wrong"))
        }

        if (web3AuthResponse.privKey.isNullOrBlank()) {
            logoutCompletableFuture.complete(null)
        }

        web3AuthResponse.sessionId?.let { KeyStoreManagerUtils.encryptData(KeyStoreManagerUtils.SESSION_ID, it) }
        loginCompletableFuture.complete(web3AuthResponse)
    }

    fun login(loginParams: LoginParams) : CompletableFuture<Web3AuthResponse> {
        request("login", loginParams)

        loginCompletableFuture = CompletableFuture()
        return loginCompletableFuture
    }

    fun logout(params: Map<String, Any>? = null) : CompletableFuture<Void> {
        sessionTimeOutAPI()
        request("logout", extraParams = params)

        logoutCompletableFuture = CompletableFuture()
        return logoutCompletableFuture
    }

    private fun authorizeSession(pubKey: String) {
        GlobalScope.launch {
            val result = web3AuthApi.authorizeSession(pubKey)
            if(result.isSuccessful && result.body() != null) {
                val messageObj = JSONObject(result.body()?.message).toString()
                shareMetadata = gson.fromJson(
                    messageObj,
                    ShareMetadata::class.java
                )
                KeyStoreManagerUtils.encryptData(KeyStoreManagerUtils.EPHEM_PUBLIC_Key, shareMetadata.ephemPublicKey.toString())
                KeyStoreManagerUtils.encryptData(KeyStoreManagerUtils.IV_KEY, shareMetadata.iv.toString())
                val aes256cbc = AES256CBC(
                    sessionId?.let { KeyStoreManagerUtils.getPrivateKey(it) },
                    shareMetadata.ephemPublicKey,
                    shareMetadata.iv.toString()
                )
                // Implementation specific oddity - hex string actually gets passed as a base64 string
                /*val hexUTF8AsBase64: String = shareMetadata.ciphertext.toString()
                val hexUTF8 = Base64.decode(hexUTF8AsBase64)
                    ?.let { String(it, StandardCharsets.UTF_8) }
                val encryptedShareBytes = AES256CBC.toByteArray(
                    BigInteger(hexUTF8, 16)
                )
                val share = BigInteger(1, aes256cbc.decrypt(Base64.encodeBytes(encryptedShareBytes)))*/
                if (web3AuthResponse != null) {
                    //loginCompletableFuture.complete(web3AuthResponse)
                }
            }
        }
    }

    private fun sessionTimeOutAPI() {
        val aes256cbc = AES256CBC(
            sessionId?.let { KeyStoreManagerUtils.getPrivateKey(it) },
            KeyStoreManagerUtils.decryptData(KeyStoreManagerUtils.EPHEM_PUBLIC_Key),
            KeyStoreManagerUtils.decryptData(KeyStoreManagerUtils.IV_KEY)
        )

        val derivedECKeyPair: ECKeyPair = ECKeyPair.create(BigInteger(sessionId, 16))
        val gson = Gson()
        val setDataString = gson.toJson(web3AuthResponse)
        val hashedData = Hash.sha3(setDataString.toByteArray(StandardCharsets.UTF_8))
        val signature: ECDSASignature = derivedECKeyPair.sign(hashedData)
        val sig: String = KeyStoreManagerUtils.padLeft(signature.r.toString(16), '0', 64)+
                KeyStoreManagerUtils.padLeft(signature.s.toString(16), '0', 64) +
                KeyStoreManagerUtils.padLeft("", '0', 2)
        val sigBytes = AES256CBC.toByteArray(BigInteger(sig, 16))
        val finalSig = Base64.encodeBytesToBytes(sigBytes)?.let { String(it, StandardCharsets.UTF_8) }

        GlobalScope.launch {
            web3AuthApi.logout(LogoutApiRequest(key = KeyStoreManagerUtils.getPubKey(sessionId.toString()) ,
                data = aes256cbc.encrypt(web3AuthResponse.toString().toByteArray(StandardCharsets.UTF_8)),
                signature = finalSig,
                timeout = 0))
        }
    }

    fun logout(
        redirectUrl: Uri? = null,
        appState: String? = null
    ) {
        val params = mutableMapOf<String, Any>()
        if (redirectUrl != null) params["redirectUrl"] = redirectUrl.toString()
        if (appState != null) params["appState"] = appState
        logout(params)
    }
}
