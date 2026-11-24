package com.th0rgal.sidesystem

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Transparent Activity launched by sidesystem-powerd when NixOS PAM (sudo / polkit / hyprlock)
 * or sidesystem-fido2 (WebAuthn Passkeys / ssh-keygen -t ecdsa-sk) requests hardware fingerprint
 * authentication on the Motorola Razr 60 Ultra.
 *
 * Uses Android's StrongBox/TEE-backed BiometricPrompt + ECDSA P-256 CryptoObject so every
 * PAM challenge and every FIDO2/CTAP2 credential is cryptographically backed by hardware keys
 * that never leave Android KeyStore.
 *
 * Protocols:
 *   1. PAM Touch ID:
 *        extras: nonce=<48-hex>, reason=<display text>
 *        resp:   "OK <nonce> <derSigB64>"
 *   2. FIDO2 MakeCredential:
 *        extras: nonce="FIDO2_MAKE", reason="<credIdHex>|<rpId>"
 *        resp:   "OK_FIDO2 <credIdHex> <pubX64Hex> <pubY64Hex>"
 *   3. FIDO2 GetAssertion:
 *        extras: nonce="FIDO2_GET", reason="<credIdHex>|<rpId>|<payloadB64>"
 *        resp:   "OK_FIDO2 <credIdHex> <derSigB64>"
 */
class BiometricAuthActivity : Activity() {

    companion object {
        private const val PAM_KEY_ALIAS = "sidesystem_nixos_touchid_ecdsa"
        private const val FIDO2_KEY_PREFIX = "sidesystem_fido2_"
        private const val BRIDGE_SOCK = "/data/local/nixos/rootfs/var/lib/sidesystem/bridge.sock"
        private const val RESP_FILE = "/data/local/nixos/rootfs/var/lib/sidesystem/bio-resp"
        private const val PUB_FILE = "/data/local/nixos/rootfs/var/lib/sidesystem/touchid.pub"
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
            when (nonce) {
                "FIDO2_MAKE" -> handleFido2Make(reason)
                "FIDO2_GET"  -> handleFido2Get(reason)
                else         -> handlePamTouchId(nonce, reason)
            }
        } catch (e: Exception) {
            writeResponse("ERR init_${e.javaClass.simpleName}_${e.message}")
            finish()
        }
    }

    private fun handlePamTouchId(nonce: String, reason: String) {
        val sig = initHardwareSignature(PAM_KEY_ALIAS, createIfMissing = true, publishPamPub = true)
        launchPrompt(
            title = "SideSystem Touch ID",
            subtitle = reason,
            description = "Touch the Razr fingerprint sensor to authorize NixOS",
            negative = "Use Password",
            sig = sig
        ) { unlockedSig ->
            unlockedSig.update(nonce.toByteArray(Charsets.UTF_8))
            val sigB64 = Base64.encodeToString(unlockedSig.sign(), Base64.NO_WRAP)
            writeResponse("OK $nonce $sigB64")
        }
    }

    private fun handleFido2Make(payload: String) {
        val parts = payload.split("|")
        if (parts.size < 2) {
            writeResponse("ERR bad_fido2_make")
            finish()
            return
        }
        val credIdHex = parts[0]
        val rpId = parts[1]
        val alias = FIDO2_KEY_PREFIX + credIdHex

        val sig = initHardwareSignature(alias, createIfMissing = true, publishPamPub = false)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val ecPub = ks.getCertificate(alias).publicKey as ECPublicKey
        val xHex = toFixed32Hex(ecPub.w.affineX)
        val yHex = toFixed32Hex(ecPub.w.affineY)

        launchPrompt(
            title = "Create Passkey",
            subtitle = rpId,
            description = "Touch the Razr fingerprint sensor to create a hardware FIDO2 key",
            negative = "Cancel",
            sig = sig
        ) { unlockedSig ->
            // Exercise the CryptoObject to prove hardware biometric unlock
            unlockedSig.update(credIdHex.toByteArray(Charsets.UTF_8))
            unlockedSig.sign()
            writeResponse("OK_FIDO2 $credIdHex $xHex $yHex")
        }
    }

    private fun handleFido2Get(payload: String) {
        val parts = payload.split("|")
        if (parts.size < 3) {
            writeResponse("ERR bad_fido2_get")
            finish()
            return
        }
        val credIdHex = parts[0]
        val rpId = parts[1]
        val dataBytes = Base64.decode(parts[2], Base64.DEFAULT)
        val alias = FIDO2_KEY_PREFIX + credIdHex

        val sig = initHardwareSignature(alias, createIfMissing = false, publishPamPub = false)
        launchPrompt(
            title = "Sign in with Passkey",
            subtitle = rpId,
            description = "Touch the Razr fingerprint sensor to sign with your hardware FIDO2 key",
            negative = "Cancel",
            sig = sig
        ) { unlockedSig ->
            unlockedSig.update(dataBytes)
            val sigB64 = Base64.encodeToString(unlockedSig.sign(), Base64.NO_WRAP)
            writeResponse("OK_FIDO2 $credIdHex $sigB64")
        }
    }

    private fun launchPrompt(
        title: String,
        subtitle: String,
        description: String,
        negative: String,
        sig: Signature,
        onSigned: (Signature) -> Unit
    ) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(negative, mainExecutor) { _, _ ->
                writeResponse("CANCEL")
                finish()
            }
            .build()

        cancelSignal = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    onSigned(result.cryptoObject?.signature ?: sig)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!responded) {
            writeResponse("CANCEL")
        }
        cancelSignal?.cancel()
    }

    private fun toFixed32Hex(v: BigInteger): String {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out.joinToString("") { "%02x".format(it) }
    }

    private fun initHardwareSignature(
        alias: String,
        createIfMissing: Boolean,
        publishPamPub: Boolean
    ): Signature {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            if (!createIfMissing) {
                throw IllegalStateException("Key alias not found: $alias")
            }
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        if (publishPamPub) {
            val pubBytes = ks.getCertificate(alias).publicKey.encoded
            val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
            publishPubKey(pubB64)
        }

        val privKey = ks.getKey(alias, null) as java.security.PrivateKey
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
        // Fast path: event-driven Unix socket directly to sidesystem-bridged (<10ms, no su fork)
        try {
            LocalSocket().use { sock ->
                sock.connect(LocalSocketAddress(BRIDGE_SOCK, LocalSocketAddress.Namespace.FILESYSTEM))
                val out = sock.outputStream
                out.write("BIO_RESP $safe\n".toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
        } catch (_: Exception) {}
        // Fallback: legacy bio-resp file
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
