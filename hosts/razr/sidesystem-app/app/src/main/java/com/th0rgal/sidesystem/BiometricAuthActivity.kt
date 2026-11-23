package com.th0rgal.sidesystem

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Transparent Activity launched by sidesystem-powerd when NixOS PAM (sudo / polkit)
 * requests hardware fingerprint authentication on the Motorola Razr 60 Ultra.
 *
 * Uses Android's StrongBox/TEE-backed BiometricPrompt + ECDSA P-256 CryptoObject
 * so every NixOS PAM challenge nonce is cryptographically signed by the hardware sensor.
 */
class BiometricAuthActivity : Activity() {

    companion object {
        private const val KEY_ALIAS = "sidesystem_nixos_touchid_ecdsa"
        private const val RESP_FILE = "/data/local/nixos/mnt/var/lib/sidesystem/bio-resp"
        private const val PUB_FILE = "/data/local/nixos/mnt/var/lib/sidesystem/touchid.pub"
    }

    private var cancelSignal: CancellationSignal? = null
    private var responded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nonce = intent?.getStringExtra("nonce") ?: ""
        val reason = intent?.getStringExtra("reason") ?: "NixOS authentication"

        if (nonce.isBlank()) {
            writeResponse("ERR missing_nonce")
            finish()
            return
        }

        try {
            val sig = initHardwareSignature()
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("SideSystem Touch ID")
                .setSubtitle(reason)
                .setDescription("Touch the Razr fingerprint sensor to authorize NixOS")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Use Password", mainExecutor) { _, _ ->
                    writeResponse("CANCEL")
                    finish()
                }
                .build()

            cancelSignal = CancellationSignal()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val s = result.cryptoObject?.signature ?: sig
                        s.update(nonce.toByteArray(Charsets.UTF_8))
                        val sigBytes = s.sign()
                        val sigB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)
                        writeResponse("OK $nonce $sigB64")
                    } catch (e: Exception) {
                        writeResponse("ERR sign_${e.javaClass.simpleName}")
                    }
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    writeResponse("ERR $errorCode")
                    finish()
                }

                override fun onAuthenticationFailed() {
                    // System keeps prompt open for retry
                }
            }

            prompt.authenticate(
                BiometricPrompt.CryptoObject(sig),
                cancelSignal!!,
                mainExecutor,
                callback
            )
        } catch (e: Exception) {
            writeResponse("ERR init_${e.javaClass.simpleName}_${e.message}")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!responded) {
            writeResponse("CANCEL")
        }
        cancelSignal?.cancel()
    }

    private fun initHardwareSignature(): Signature {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        // Export public key (DER base64) via root helper if not yet published
        val pubBytes = ks.getCertificate(KEY_ALIAS).publicKey.encoded
        val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        publishPubKey(pubB64)

        val privKey = ks.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privKey)
        }
    }

    private fun publishPubKey(pubB64: String) {
        try {
            val f = File(PUB_FILE)
            if (f.canWrite()) {
                f.writeText("$pubB64\n")
                return
            }
        } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "printf '%s\\n' '$pubB64' > '$PUB_FILE' && chmod 644 '$PUB_FILE'")
            ).waitFor()
        } catch (_: Exception) {}
    }

    private fun writeResponse(line: String) {
        if (responded) return
        responded = true
        val safe = line.replace("'", "")
        try {
            val f = File(RESP_FILE)
            if (f.canWrite()) {
                f.writeText("$safe\n")
                return
            }
        } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "printf '%s\\n' '$safe' > '$RESP_FILE' && chmod 666 '$RESP_FILE'")
            ).waitFor()
        } catch (_: Exception) {}
    }
}
