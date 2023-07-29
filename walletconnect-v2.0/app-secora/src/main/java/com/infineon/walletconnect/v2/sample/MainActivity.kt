package com.infineon.walletconnect.v2.sample

import android.app.PendingIntent
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.budiyev.android.codescanner.*
import com.github.infineon.NfcUtils
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.infineon.walletconnect.v2.sample.databinding.ActivityMainBinding
import com.walletconnect.android.Core
import com.walletconnect.android.CoreClient
import com.walletconnect.android.cacao.signature.SignatureType
import com.walletconnect.android.internal.common.cacao.Cacao
import com.walletconnect.android.internal.common.cacao.CacaoType
import com.walletconnect.android.internal.common.cacao.CacaoVerifier
import com.walletconnect.android.internal.common.cacao.signature.Signature
import com.walletconnect.android.internal.common.cacao.signature.toCacaoSignature
import com.walletconnect.android.internal.common.model.ProjectId
import com.walletconnect.android.relay.ConnectionType
import com.walletconnect.web3.wallet.client.Wallet
import com.walletconnect.web3.wallet.client.Web3Wallet
import okhttp3.internal.and
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.*
import org.web3j.crypto.transaction.type.TransactionType
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpList
import java.math.BigInteger
import java.nio.charset.Charset


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    /* The order of this enum has to be consistent with the
       nfc_actions string-array from strings.xml */
    private enum class Actions {
        READ_OR_CREATE_KEYPAIR, GEN_KEYPAIR_FROM_SEED,
        SIGN_MESSAGE, SET_PIN, CHANGE_PIN, UNLOCK_PIN
    }

    private data class SignatureDataObject(val r: ByteArray, val s: ByteArray, val v: ByteArray,
                                           val sigCounter: ByteArray, val globalSigCounter: ByteArray)
    private data class DialogDataObject(val alertDialog: AlertDialog, val view: View)

    /* Get Project ID at https://cloud.walletconnect.com/ */
    private val projectId = "f2cabf62d43121c04c15dfae7fa389df"
    private val relayUrl = "relay.walletconnect.com"
    val ISS_DID_PREFIX = "did:pkh:"
    fun Map.Entry<Chains, String>.toIssuer(): String = "$ISS_DID_PREFIX${key.chainId}:$value"

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var codeScanner: CodeScanner

    private var alertDialogPin = "0"
    private var alertDialog: AlertDialog? = null
    private var action: Actions = Actions.READ_OR_CREATE_KEYPAIR
    private var nfcCallback: ((IsoTagWrapper)->Unit) =
        { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /* Set up NFC */

        binding.nfcSpinner.onItemSelectedListener = this
        ArrayAdapter.createFromResource(
            this,
            R.array.nfc_actions,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.nfcSpinner.adapter = adapter
        }
        action = Actions.READ_OR_CREATE_KEYPAIR
        binding.nfcKeyhandle.visibility = View.VISIBLE

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC functionality not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, this.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        /* Set up QR scanner */

        codeScanner = CodeScanner(this, binding.scannerView)
        codeScanner.camera = CodeScanner.CAMERA_BACK
        codeScanner.formats = CodeScanner.ALL_FORMATS
        codeScanner.autoFocusMode = AutoFocusMode.SAFE
        codeScanner.scanMode = ScanMode.SINGLE
        codeScanner.isAutoFocusEnabled = true
        codeScanner.isFlashEnabled = false

        codeScanner.decodeCallback = DecodeCallback {
            runOnUiThread {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
                binding.uriInput.editText?.setText(it.text)
            }
        }
        codeScanner.errorCallback = ErrorCallback {
            runOnUiThread {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
                Toast.makeText(this, "Camera initialization error: ${it.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
        binding.scannerButton.setOnClickListener {
            if (binding.scannerFrame.visibility == View.GONE) {
                codeScanner.startPreview()
                binding.scannerFrame.visibility = View.VISIBLE
            } else {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
            }
        }

        /* Set up wallet connect */

        val serverUrl = "wss://$relayUrl?projectId=$projectId"

        CoreClient.initialize(
            relayServerUrl = serverUrl, connectionType = ConnectionType.AUTOMATIC, application = application,
            metaData = Core.Model.AppMetaData(
                name = "Secora Blockchain",
                description = "Secora Blockchain",
                url = "https://www.infineon.com/cms/en/product/security-smart-card-solutions/secora-security-solutions/secora-blockchain-security-solutions/",
                icons = listOf(""),
                redirect = ""
            )
        ) { _ ->
            runOnUiThread {
                Toast.makeText(this@MainActivity,
                    "WalletConnect module (CoreClient) init has failed!", Toast.LENGTH_SHORT).show()
            }
        }

        val initParams = Wallet.Params.Init(core = CoreClient)
        Web3Wallet.initialize(initParams) { error ->
            runOnUiThread {
                Toast.makeText(this@MainActivity,
                    "WalletConnect module (Web3Wallet) init has failed!", Toast.LENGTH_SHORT).show()
            }
        }

        val walletDelegate = object : Web3Wallet.WalletDelegate {
            override fun onSessionProposal(sessionProposal: Wallet.Model.SessionProposal) {
                /* Triggered when wallet receives the session proposal sent by a Dapp */
                processSessionProposal(sessionProposal)
            }

            override fun onSessionRequest(sessionRequest: Wallet.Model.SessionRequest) {
                /* Triggered when a Dapp sends SessionRequest to sign a transaction or a message */
                processSessionRequest(sessionRequest)
            }

            override fun onAuthRequest(authRequest: Wallet.Model.AuthRequest) {
                /* Triggered when Dapp / Requester makes an authorisation request */
                /* https://docs.walletconnect.com/2.0/specs/clients/auth */
                processAuthRequest(authRequest)
            }

            override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {
                /* Triggered when the session is deleted by the peer */
                processSessionDelete(sessionDelete)
            }

            override fun onSessionSettleResponse(settleSessionResponse: Wallet.Model.SettledSessionResponse) {
                /* Triggered when wallet receives the session settlement response from Dapp */
                processSessionSettleResponse(settleSessionResponse)
            }

            override fun onSessionUpdateResponse(sessionUpdateResponse: Wallet.Model.SessionUpdateResponse) {
                /* Triggered when wallet receives the session update response from Dapp */
                processSessionUpdateResponse(sessionUpdateResponse)
            }

            override fun onConnectionStateChange(state: Wallet.Model.ConnectionState) {
                /* Triggered whenever the connection state is changed */
                processConnectionStateChange(state)
            }

            override fun onError(error: Wallet.Model.Error) {
                /* Triggered whenever there is an issue inside the SDK */
                processError(error)
            }
        }
        Web3Wallet.setWalletDelegate(walletDelegate)

        setupConnectButton()
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            val rootView: ConstraintLayout = findViewById(R.id.rootView)
            rootView.clearFocus()

            val tag: Tag = intent!!.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
            val isoDep = IsoDep.get(tag) /* ISO 14443-4 Type A & B */

            nfcCallback?.let { it(IsoTagWrapper(isoDep)) }

            isoDep.close()
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
            }
        } finally {
            nfcCallback = { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
        }
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
        /* An item was selected. You can retrieve the selected item using
           parent.getItemAtPosition(pos) */
        action = Actions.values()[pos]

        val nfcKeyHandle: TextInputLayout = binding.nfcKeyhandle
        val nfcPinUse: TextInputLayout = binding.nfcPinUse
        val nfcPinSet: TextInputLayout = binding.nfcPinSet
        val nfcPuk: TextInputLayout = binding.nfcPuk
        val nfcPinCur: TextInputLayout = binding.nfcPinCur
        val nfcPinNew: TextInputLayout = binding.nfcPinNew
        val nfcSeed: TextInputLayout = binding.nfcSeed
        val nfcMessage: TextInputLayout = binding.nfcMessage

        nfcKeyHandle.visibility = View.GONE
        nfcKeyHandle.editText?.inputType = InputType.TYPE_CLASS_NUMBER
        nfcKeyHandle.editText?.setBackgroundColor(Color.TRANSPARENT)
        nfcPinUse.visibility = View.GONE
        nfcPinSet.visibility = View.GONE
        nfcPuk.visibility = View.GONE
        nfcPuk.editText?.inputType = InputType.TYPE_CLASS_TEXT
        nfcPuk.editText?.setBackgroundColor(Color.TRANSPARENT)
        nfcPinCur.visibility = View.GONE
        nfcPinNew.visibility = View.GONE
        nfcSeed.visibility = View.GONE
        nfcMessage.visibility = View.GONE

        if (nfcKeyHandle.editText?.text.toString() == "")
            nfcKeyHandle.editText?.setText("1")
        if (nfcPinUse.editText?.text.toString() == "")
            nfcPinUse.editText?.setText("0")
        if (nfcPinSet.editText?.text.toString() == "")
            nfcPinSet.editText?.setText("00000000")
        if (nfcPuk.editText?.text.toString() == "")
            nfcPuk.editText?.setText("0000000000000000")
        if (nfcPinCur.editText?.text.toString() == "")
            nfcPinCur.editText?.setText("00000000")
        if (nfcPinNew.editText?.text.toString() == "")
            nfcPinNew.editText?.setText("00000000")
        if (nfcSeed.editText?.text.toString() == "")
            nfcSeed.editText?.setText("00112233445566778899AABBCCDDEEFF")
        if (nfcMessage.editText?.text.toString() == "")
            nfcMessage.editText?.setText("00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF")

        when (action) {
            Actions.READ_OR_CREATE_KEYPAIR -> {
                nfcKeyHandle.visibility = View.VISIBLE
            }
            Actions.GEN_KEYPAIR_FROM_SEED -> {
                nfcKeyHandle.visibility = View.VISIBLE
                nfcKeyHandle.editText?.inputType = View.AUTOFILL_TYPE_NONE
                nfcKeyHandle.editText?.setText("0")
                nfcKeyHandle.editText?.setBackgroundColor(Color.LTGRAY)
                nfcSeed.visibility = View.VISIBLE
                nfcPinUse.visibility = View.VISIBLE
            }
            Actions.SIGN_MESSAGE -> {
                nfcKeyHandle.visibility = View.VISIBLE
                nfcPinUse.visibility = View.VISIBLE
                nfcMessage.visibility =View.VISIBLE
            }
            Actions.SET_PIN -> {
                nfcPinSet.visibility = View.VISIBLE
                nfcPuk.visibility = View.VISIBLE
                nfcPuk.editText?.inputType = InputType.TYPE_NULL
                nfcPuk.editText?.setTextIsSelectable(true)
                nfcPuk.editText?.setBackgroundColor(Color.LTGRAY)
            }
            Actions.CHANGE_PIN -> {
                nfcPinCur.visibility = View.VISIBLE
                nfcPinNew.visibility = View.VISIBLE
                nfcPuk.visibility = View.VISIBLE
                nfcPuk.editText?.inputType = InputType.TYPE_NULL
                nfcPuk.editText?.setTextIsSelectable(true)
                nfcPuk.editText?.setBackgroundColor(Color.LTGRAY)
            }
            Actions.UNLOCK_PIN -> {
                nfcPuk.visibility = View.VISIBLE
            }
            else -> {

            }
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>) { }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            if (!nfcAdapter!!.isEnabled()) {
                openNfcSettings();
            }
            nfcAdapter!!.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        codeScanner.releaseResources()
        binding.scannerFrame.visibility = View.GONE
        super.onPause()
    }

    private fun setupConnectButton() {
        runOnUiThread {
            binding.connectButton.text = "Connect"
            binding.connectButton.isEnabled = true
            binding.connectButton.setOnClickListener {
                val uri = binding.uriInput.editText?.text?.toString()
                val address = binding.addressInput.editText?.text?.toString()

                if (uri.isNullOrBlank() || uri.commonPrefixWith("wc:") != "wc:") {
                    createAndShowDefaultDialog("Reminder",
                        "Please scan WalletConnect QR code.",
                        "Dismiss", null,
                        null, null)
                    return@setOnClickListener
                }

                if (address.isNullOrBlank() || address.commonPrefixWith("0x") != "0x") {
                    createAndShowDefaultDialog("Reminder",
                        "Please read your card's public key.",
                        "Dismiss", null,
                        null, null)
                    return@setOnClickListener
                }

                connect(binding.uriInput.editText?.text?.toString() ?: return@setOnClickListener)
            }
        }
    }

    private fun connect(uri: String) {

        /* Disconnect all pairings */
        disconnect()

        val pairingParamsWallet = Wallet.Params.Pair(uri)
        Web3Wallet.pair(pairingParamsWallet,
            { _ ->
            },
            { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.throwable.message, Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun disconnect() {
        setupConnectButton()

        for (pair in CoreClient.Pairing.getPairings()) {
            CoreClient.Pairing.disconnect(Core.Params.Disconnect(pair.topic)) { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.throwable.message, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return removePrefix("0x")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun ByteArray.toHex() : String{
        val HEX_CHARS = "0123456789abcdef".toCharArray()
        val result = StringBuffer()

        forEach {
            val octet = it.toInt()
            val firstIndex = (octet and 0xF0).ushr(4)
            val secondIndex = octet and 0x0F
            result.append(HEX_CHARS[firstIndex])
            result.append(HEX_CHARS[secondIndex])
        }

        return result.toString()
    }

    private fun openNfcSettings() {
        runOnUiThread {
            Toast.makeText(this, "Please enable NFC", Toast.LENGTH_SHORT).show()
        }
        val intent = Intent(Settings.ACTION_NFC_SETTINGS)
        startActivity(intent)
    }

    private fun nfcDefaultCallback(isoTagWrapper: IsoTagWrapper) {

        try {
            val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
            val pinUse = binding.nfcPinUse.editText?.text.toString()
            val pinSet = binding.nfcPinSet.editText?.text.toString()
            val pinCur = binding.nfcPinCur.editText?.text.toString()
            val pinNew = binding.nfcPinNew.editText?.text.toString()
            val puk = binding.nfcPuk.editText?.text.toString()
            val seed = binding.nfcSeed.editText?.text.toString()
            val message = binding.nfcMessage.editText?.text.toString()
            var ret: Boolean

            when (action) {
                Actions.READ_OR_CREATE_KEYPAIR -> {
                    val pubkey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                        isoTagWrapper, Integer.parseInt(keyHandle)
                    )

                    val address = Keys.toChecksumAddress(Keys.getAddress(pubkey.publicKeyInHexWithoutPrefix))
                    binding.addressInput.editText?.setText(address)

                    createAndShowDefaultDialog("Response",
                        "Address:\n$address\n\n" +
                                "Signature counter:\n${Integer.decode("0x" + pubkey.sigCounter.toHex())}\n\n" +
                                "Global signature counter:\n${Integer.decode("0x" + pubkey.globalSigCounter.toHex())}",
                        "Dismiss", null,
                        null, null)
                }
                Actions.GEN_KEYPAIR_FROM_SEED -> {

                    /* Generate keypair from seed */

                    if (pinUse == "0")
                        ret = NfcUtils.generateKeyFromSeed(isoTagWrapper, seed.decodeHex(), null)
                    else
                        ret = NfcUtils.generateKeyFromSeed(isoTagWrapper, seed.decodeHex(), pinUse.decodeHex())

                    if (!ret)
                        throw Exception("Invalid PIN")

                    /* Read back and display the key info */

                    val pubkey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                        isoTagWrapper, 0
                    )

                    val address = Keys.toChecksumAddress(Keys.getAddress(pubkey.publicKeyInHexWithoutPrefix))
                    binding.addressInput.editText?.setText(address)

                    createAndShowDefaultDialog("Response",
                        "Address:\n$address\n\n" +
                                "Signature counter:\n${Integer.decode("0x" + pubkey.sigCounter.toHex())}\n\n" +
                                "Global signature counter:\n${Integer.decode("0x" + pubkey.globalSigCounter.toHex())}",
                        "Dismiss", null,
                        null, null)
                }
                Actions.SIGN_MESSAGE -> {

                    val pin = if (pinUse != "0") {
                        pinUse.decodeHex()
                    } else {
                        null
                    }
                    val sig = signing(isoTagWrapper, Integer.parseInt(keyHandle), pin, message.decodeHex())
                    val rsv = sig.r + sig.s + sig.v

                    createAndShowDefaultDialog("Response",
                        "Signature (ASN.1):\n0x${rsv.toHex()}\n\n" +
                                "r:\n0x${sig.r.toHex()}\n\n" +
                                "s:\n0x${sig.s.toHex()}\n\n" +
                                "v:\n0x${sig.v.toHex()}\n\n" +
                                "Signature counter:\n${Integer.decode("0x" + sig.sigCounter.toHex())}\n\n" +
                                "Global signature counter:\n${Integer.decode("0x" + sig.globalSigCounter.toHex())}",
                        "Dismiss", null,
                        null, null)
                }
                Actions.SET_PIN -> {
                    val puk = NfcUtils.initializePinAndReturnPuk(isoTagWrapper, pinSet.decodeHex())
                    binding.nfcPuk.editText?.setText(puk.toHex())

                    createAndShowDefaultDialog("Response",
                        "Remember the PUK:\n${puk.toHex()}\n\n" +
                                "and the PIN:\n${pinSet}",
                        "Dismiss", null,
                        null, null)
                }
                Actions.CHANGE_PIN -> {
                    val puk = NfcUtils.changePin(isoTagWrapper, pinCur.decodeHex(), pinNew.decodeHex())
                    binding.nfcPuk.editText?.setText(puk.toHex())

                    createAndShowDefaultDialog("Response",
                        "Remember the PUK:\n${puk.toHex()}\n\n" +
                        "and the PIN:\n${pinNew}",
                        "Dismiss", null,
                        null, null)
                }
                Actions.UNLOCK_PIN -> {
                    if (!NfcUtils.unlockPin(isoTagWrapper, puk.decodeHex()))
                        throw Exception("Invalid PUK")

                    createAndShowDefaultDialog("Response",
                        "PIN Unlocked, remember to set a new PIN",
                        "Dismiss", null,
                        null, null)
                }
                else -> {

                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extractR(signature: ByteArray): ByteArray {
        val startR = if ((signature[1] and 0x80) != 0) 3 else 2
        val lengthR = signature[startR + 1]
        var skipZeros = 0
        var addZeros = ByteArray(0)

        if (lengthR > 32)
            skipZeros = lengthR - 32
        if (lengthR < 32)
            addZeros = ByteArray(32 - lengthR)

        return addZeros + signature.copyOfRange(startR + 2 + skipZeros, startR + 2 + lengthR)
    }

    private fun verifyAndExtractS(signature: ByteArray): ByteArray {
        val startR = if ((signature[1] and 0x80) != 0) 3 else 2
        val lengthR = signature[startR + 1]
        val startS = startR +2 + lengthR;
        val lengthS = signature [startS + 1];
        var skipZeros = 0
        var addZeros = ByteArray(0)

        if (lengthS > 32)
            skipZeros = lengthS - 32
        if (lengthS < 32)
            addZeros = ByteArray(32 - lengthS)

        val s: ByteArray = addZeros + signature.copyOfRange(startS + 2 + skipZeros, startS + 2 + lengthS)

        if (s[0] >= 0x80)
            throw Exception("Signature is vulnerable to malleability attack")

        return s
    }

    private fun signing(isoTagWrapper: IsoTagWrapper, keyHandle: Int,
                        pin: ByteArray?, data: ByteArray): SignatureDataObject {

        val signature = NfcUtils.generateSignature(isoTagWrapper, keyHandle, data, pin)
        val asn1Signature = signature.signature.dropLast(2).toByteArray()

        /* ASN.1 DER encoded signature
           Example:
               3045022100D962A9F5185971A1229300E8FC7E699027F90843FBAD5DE060
               CA4B289CF88D580220222BAB7E5BCC581373135A5E8C9B1933398B994814
               CE809FA1053F5E17BC1733
           Breakdown:
               30: DER TAG Signature
               45: Total length of signature
               02: DER TAG component
               21: Length of R
               00D962A9F5185971A1229300E8FC7E699027F90843FBAD5DE060CA4B289CF88D58
               02: DER TAG component
               20: Length of S
               222BAB7E5BCC581373135A5E8C9B1933398B994814CE809FA1053F5E17BC1733
         */
        val r = extractR(asn1Signature)
        val s = verifyAndExtractS(asn1Signature)

        /* Determines the component v and indirectly verifies the signature */

        val rawPublicKey = NfcUtils.readPublicKeyOrCreateIfNotExists(
            isoTagWrapper, keyHandle
        )

        val address = Keys.getAddress(rawPublicKey.publicKeyInHexWithoutPrefix)

        val v = byteArrayOf(0)

        for (i in 0..4) {
            val publicKey = Sign.recoverFromSignature(
                i,
                ECDSASignature(BigInteger(1, r), BigInteger(1, s)),
                data
            )

            if (Keys.getAddress(publicKey) == address) {
                v[0] = i.toByte()
                break
            }
        }

        if (v[0] > 3) {
            throw Exception("Signature internal verification has failed")
        }

        v[0] = v[0].plus(27).toByte()

        return SignatureDataObject(r, s, v, signature.sigCounter, signature.globalSigCounter)
    }

    private fun processSessionProposal(sessionProposal: Wallet.Model.SessionProposal) {
        runOnUiThread {
            try {
                val ethereumNamespace = sessionProposal.requiredNamespaces[Chains.ETHEREUM_MAIN.chainNamespace]
                    ?: throw Exception("Only namespaces ${Chains.ETHEREUM_MAIN.chainNamespace} is supported")

                val address = binding.addressInput.editText?.text.toString().lowercase()
                val accounts: ArrayList<String> = ArrayList()

                for (chain in ethereumNamespace.chains) {
                    if (chain == Chains.ETHEREUM_MAIN.chainId) {
                        accounts.add("${Chains.ETHEREUM_MAIN.chainId}:${address}")
                    } else if (chain == Chains.ETHEREUM_GOERLI.chainId) {
                        accounts.add("${Chains.ETHEREUM_GOERLI.chainId}:${address}")
                    } else {
                        throw Exception("Chain ${chain} is not supported")
                    }
                }

                val mapOfNamespace: Map<String, Wallet.Model.Namespace.Session> = mapOf(
                    Chains.ETHEREUM_MAIN.chainNamespace to Wallet.Model.Namespace.Session(
                        accounts.toList(), Chains.ETHEREUM_MAIN.methods, Chains.ETHEREUM_MAIN.events, null)
                )

                createAndShowDefaultDialog("SessionProposal",
                    sessionProposal.description,
                    "Approve",
                    { _, _ ->
                        val approveProposal = Wallet.Params.SessionApprove(sessionProposal.proposerPublicKey, mapOfNamespace)
                        Web3Wallet.approveSession(approveProposal) { error ->
                            runOnUiThread {
                                Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                            }
                        }
                        binding.connectButton.text = "Pending..."
                        binding.connectButton.isEnabled = false
                    },
                    "Reject",
                    { _, _ ->
                        val reject = Wallet.Params.SessionReject(sessionProposal.proposerPublicKey, "Rejected by user")
                        Web3Wallet.rejectSession(reject) { error ->
                            runOnUiThread {
                                Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                            }
                        }
                        disconnect()
                    })
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                disconnect()
            }
        }
    }

    private fun processSessionRequest(sessionRequest: Wallet.Model.SessionRequest) {
        runOnUiThread {
            try {
                val address = binding.addressInput.editText?.text.toString().lowercase()
                var byteArrayToSign: ByteArray
                var dialogMessage: String
                var rawTransaction: RawTransaction? = null
                var web3j: Web3j? = null
                var chainId: Long = 0

                when (sessionRequest.chainId) {
                    Chains.ETHEREUM_MAIN.chainId -> {
                        chainId = Chains.ETHEREUM_MAIN.chainReference.toLong()
                    }
                    Chains.ETHEREUM_GOERLI.chainId -> {
                        chainId = Chains.ETHEREUM_GOERLI.chainReference.toLong()
                    }
                    else -> {
                        throw Exception("Only chainId ${Chains.ETHEREUM_MAIN.chainId} and ${Chains.ETHEREUM_GOERLI.chainId} are supported")
                    }
                }

                when (sessionRequest.request.method) {
                    "eth_sendTransaction",
                    "eth_signTransaction" -> {

                        val jsonArray = JSONArray(sessionRequest.request.params)
                        val jsonObject = JSONObject(jsonArray.getString(0))

                        /* Public node provider list: https://ethereumnodes.com/ */
                        when (chainId) {
                            Chains.ETHEREUM_MAIN.chainReference.toLong() -> {
                                web3j = Web3j.build(HttpService("https://eth-mainnet.public.blastapi.io"))
                            }
                            Chains.ETHEREUM_GOERLI.chainReference.toLong() -> {
                                web3j = Web3j.build(HttpService("https://eth-goerli.public.blastapi.io"))
                            }
                            else -> {
                                throw Exception("Only chainId ${Chains.ETHEREUM_MAIN.chainId} and ${Chains.ETHEREUM_GOERLI.chainId} are supported")
                            }
                        }

                        val address = binding.addressInput.editText?.text?.toString() ?: "Address not set"
                        val nonce = web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).sendAsync().get().transactionCount
                        val gasPrice = if (!jsonObject.has("gasPrice")) {
                            web3j.ethGasPrice().sendAsync().get().gasPrice
                        } else {
                            BigInteger(jsonObject.getString("gasPrice").removePrefix("0x"), 16)
                        }
                        val data = if (jsonObject.has("data")) {
                            jsonObject.getString("data")
                        } else {
                            null
                        }
                        val value = if (!jsonObject.has("value")) {
                            throw Exception("Field value is null")
                        } else {
                            BigInteger(jsonObject.getString("value").removePrefix("0x"), 16)
                        }
                        val to = if (!jsonObject.has("to")) {
                            throw Exception("Field to is null")
                        } else {
                            jsonObject.getString("to")
                        }
                        val gasLimit = if (!jsonObject.has("gasLimit")) {
                            val transactionEstimation: Transaction = Transaction.createFunctionCallTransaction(
                                address,
                                nonce,
                                gasPrice,
                                BigInteger("0",10),
                                to,
                                value,
                                data
                            )
                            val ethEstimateGas = web3j.ethEstimateGas(transactionEstimation).sendAsync().get()
                            ethEstimateGas.amountUsed
                        } else {
                            BigInteger(jsonObject.getString("gasLimit").removePrefix("0x"), 16)
                        }

                        /* Create TransactionType.LEGACY */
                        rawTransaction = RawTransaction.createTransaction(nonce, gasPrice,
                            gasLimit, jsonObject.getString("to"), value,
                            jsonObject.getString("data"))
                        val encodedTransaction = TransactionEncoder.encode(rawTransaction, chainId)

                        byteArrayToSign = Hash.sha3(encodedTransaction)
                        dialogMessage = Gson().toJson(rawTransaction, RawTransaction::class.java)

                    }
                    "eth_sign" -> {
                        /* eth_sign (standard) */

                        val jsonArray = JSONArray(sessionRequest.request.params)
                        val account = jsonArray.getString(0)
                        val message = jsonArray.getString(1)
                        val messageByteArray = message.decodeHex()
                        val prefix = ("\u0019Ethereum Signed Message:\n" + messageByteArray.size).toByteArray(Charsets.UTF_8)

                        if (account != address) {
                            throw Exception("Requested account is not valid")
                        }

                        dialogMessage = messageByteArray.toString(Charset.defaultCharset())
                        byteArrayToSign = Hash.sha3(prefix + messageByteArray)
                    }
                    "eth_signTypedData_v4",
                    "eth_signTypedData" -> {

                        val jsonArray = JSONArray(sessionRequest.request.params)
                        val account = jsonArray.getString(0)
                        val message = jsonArray.getString(1)
                        val structuredDataEncoder = StructuredDataEncoder(message)
                        val messageByteArray = structuredDataEncoder.structuredData

                        if (account != address) {
                            throw Exception("Requested account is not valid")
                        }

                        dialogMessage = message
                        byteArrayToSign = Hash.sha3(messageByteArray)
                    }
                    "personal_sign" -> {

                        val jsonArray = JSONArray(sessionRequest.request.params)
                        val message = jsonArray.getString(0)
                        val account = jsonArray.getString(1)
                        val messageByteArray = message.decodeHex()
                        val prefix = ("\u0019Ethereum Signed Message:\n" + messageByteArray.size).toByteArray(Charsets.UTF_8)

                        if (account != address) {
                            throw Exception("Requested account is not valid")
                        }

                        dialogMessage = messageByteArray.toString(Charset.defaultCharset())
                        byteArrayToSign = Hash.sha3(prefix + messageByteArray)
                    }
                    else -> {
                        throw Exception("Unknown method")
                    }
                }

                val alertDialogObject = createAndShowCustomDialog(sessionRequest.request.method, dialogMessage) {
                    Web3Wallet.respondSessionRequest(
                        Wallet.Params.SessionRequestResponse(
                            sessionRequest.topic,
                            Wallet.Model.JsonRpcResponse.JsonRpcError(
                                sessionRequest.request.id,
                                4001, /* https://docs.walletconnect.com/2.0/specs/clients/sign/error-codes */
                                "User Rejected Request"
                            )
                        )
                    ) { error ->
                        runOnUiThread {
                            Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                nfcCallback = { isoTagWrapper ->
                    try {
                        /* Sign with Secora Blockchain */

                        val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
                        alertDialogPin =
                            alertDialogObject.view.findViewById<TextInputLayout>(R.id.dialogPinInput).editText?.text.toString()
                        val pin = if (alertDialogPin != "0") {
                            alertDialogPin.decodeHex()
                        } else {
                            null
                        }
                        val sig = signing(
                            isoTagWrapper,
                            Integer.parseInt(keyHandle),
                            pin,
                            byteArrayToSign
                        )
                        var signature = Signature(sig.v, sig.r, sig.s).toCacaoSignature()

                        /* Special handling for eth_sendTransaction and eth_signTransaction */

                        when (sessionRequest.request.method) {
                            "eth_sendTransaction",
                            "eth_signTransaction" -> {
                                if (rawTransaction == null
                                    || web3j == null) {
                                    throw Exception("Web3j initialization failed")
                                }
                                if (rawTransaction.type != TransactionType.LEGACY) {
                                    throw Exception("Transaction type not supported")
                                }

                                val signatureData = Sign.SignatureData(sig.v, sig.r, sig.s)
                                val eip155SignatureData =
                                    TransactionEncoder.createEip155SignatureData(signatureData, chainId)
                                val values = TransactionEncoder.asRlpValues(
                                    rawTransaction,
                                    eip155SignatureData
                                )
                                val rlpList = RlpList(values)
                                val encoded = RlpEncoder.encode(rlpList)
                                val hexString = "0x" + encoded.toHex()

                                signature = if (sessionRequest.request.method == "eth_sendTransaction") {
                                    val ethSendRawTransaction =
                                        web3j.ethSendRawTransaction(hexString).sendAsync().get()
                                    val error = ethSendRawTransaction.error
                                    if (error != null) {
                                        throw Exception(error.message)
                                    } else {
                                        ethSendRawTransaction.transactionHash
                                    }
                                } else {
                                    hexString
                                }
                            }
                        }

                        /* Send response */

                        Web3Wallet.respondSessionRequest(
                            Wallet.Params.SessionRequestResponse(
                                sessionRequest.topic,
                                Wallet.Model.JsonRpcResponse.JsonRpcResult(
                                    sessionRequest.request.id,
                                    signature
                                )
                            )
                        ) { error ->
                            runOnUiThread {
                                Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                        }
                        Web3Wallet.respondSessionRequest(
                            Wallet.Params.SessionRequestResponse(
                                sessionRequest.topic,
                                Wallet.Model.JsonRpcResponse.JsonRpcError(
                                    sessionRequest.request.id,
                                    4001, /* https://docs.walletconnect.com/2.0/specs/clients/sign/error-codes */
                                    e.message.toString()
                                )
                            )
                        ){ error ->
                            runOnUiThread {
                                Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        alertDialogObject.alertDialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                Web3Wallet.respondSessionRequest(
                    Wallet.Params.SessionRequestResponse(
                        sessionRequest.topic,
                        Wallet.Model.JsonRpcResponse.JsonRpcError(
                            sessionRequest.request.id,
                            4001, /* https://docs.walletconnect.com/2.0/specs/clients/sign/error-codes */
                            e.message.toString()
                        )
                    )
                ){ error ->
                    runOnUiThread {
                        Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun processAuthRequest(authRequest: Wallet.Model.AuthRequest) {
        runOnUiThread {
            try {
                val address = binding.addressInput.editText?.text.toString().lowercase()
                val mapOfAccounts: Map<Chains, String> = mapOf(
                    Chains.ETHEREUM_MAIN to address,
                )
                val issuer = mapOfAccounts.map { it.toIssuer() }.first()

                val message = Web3Wallet.formatMessage(
                    Wallet.Params.FormatMessage(
                        authRequest.payloadParams,
                        issuer
                    )
                ) ?: throw Exception("Error formatting message")

                val alertDialogObject = createAndShowCustomDialog("AuthRequest", message) {
                    Web3Wallet.respondAuthRequest(
                        Wallet.Params.AuthRequestResponse.Error(
                            authRequest.id,
                            12001, /* https://docs.walletconnect.com/2.0/specs/clients/auth/codes */
                            "User Rejected Request"
                        )
                    ) { error ->
                        runOnUiThread {
                            Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                nfcCallback = { isoTagWrapper ->
                    try {
                        /* Sign with Secora Blockchain */

                        val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
                        alertDialogPin =
                            alertDialogObject.view.findViewById<TextInputLayout>(R.id.dialogPinInput).editText?.text.toString()
                        val pin = if (alertDialogPin != "0") {
                            alertDialogPin.decodeHex()
                        } else {
                            null
                        }
                        val byteArrayData = message.toByteArray()
                        val prefix = ("\u0019Ethereum Signed Message:\n" + byteArrayData.size).toByteArray(Charsets.UTF_8)
                        val byteArrayToSign = Hash.sha3(prefix + byteArrayData)
                        val sig = signing(
                            isoTagWrapper,
                            Integer.parseInt(keyHandle),
                            pin,
                            byteArrayToSign
                        )
                        val signature = Signature(sig.v, sig.r, sig.s).toCacaoSignature()

                        /* Signature redundancy check */
                        val cacaoSignature = Cacao.Signature(
                            SignatureType.EIP191.header,
                            signature,
                            message
                        )
                        val payload = Cacao.Payload(issuer, authRequest.payloadParams.domain,
                            authRequest.payloadParams.aud, authRequest.payloadParams.version,
                            authRequest.payloadParams.nonce, authRequest.payloadParams.iat,
                            authRequest.payloadParams.nbf, authRequest.payloadParams.exp,
                            authRequest.payloadParams.statement, authRequest.payloadParams.requestId,
                            authRequest.payloadParams.resources)
                        val cacao = Cacao(CacaoType.EIP4361.toHeader(), payload, cacaoSignature)
                        val cacaoVerifier = CacaoVerifier(ProjectId(""))
                        if (!cacaoVerifier.verify(cacao))
                            throw Exception("Signature redundancy check has failed")

                        /* Send response */
                        val modelCacaoSignature = Wallet.Model.Cacao.Signature(
                            SignatureType.EIP191.header,
                            signature,
                            message
                        )
                        Web3Wallet.respondAuthRequest(
                            Wallet.Params.AuthRequestResponse.Result(
                                authRequest.id,
                                modelCacaoSignature,
                                issuer
                            )
                        ) { error ->
                            runOnUiThread {
                                Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG)
                                    .show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                        }
                        disconnect()
                    } finally {
                        alertDialogObject.alertDialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                disconnect()
            }
        }
    }

    private fun processSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {
        runOnUiThread {
            try {
                when(sessionDelete) {
                    is Wallet.Model.SessionDelete.Success -> {
                    }
                    is Wallet.Model.SessionDelete.Error -> {
                        throw sessionDelete.error
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            } finally {
                disconnect()
            }
        }
    }

    private fun processSessionSettleResponse(settleSessionResponse: Wallet.Model.SettledSessionResponse) {
        runOnUiThread {
            try {
                when(settleSessionResponse) {
                    is Wallet.Model.SettledSessionResponse.Result -> {
                        binding.connectButton.text = "Kill Session"
                        binding.connectButton.isEnabled = true
                        binding.connectButton.setOnClickListener {
                            val disconnect = Wallet.Params.SessionDisconnect(settleSessionResponse.session.topic)
                            Web3Wallet.disconnectSession(disconnect,
                                {
                                    disconnect()
                                },
                                { error ->
                                    runOnUiThread {
                                        Toast.makeText(this, error.throwable.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                    is Wallet.Model.SettledSessionResponse.Error -> {
                        throw Exception(settleSessionResponse.errorMessage)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                disconnect()
            }
        }
    }

    private fun processSessionUpdateResponse(sessionUpdateResponse: Wallet.Model.SessionUpdateResponse) {
        runOnUiThread {
            try {
                when(sessionUpdateResponse) {
                    is Wallet.Model.SessionUpdateResponse.Result -> {

                    }
                    is Wallet.Model.SessionUpdateResponse.Error -> {
                        throw Exception(sessionUpdateResponse.errorMessage)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                disconnect()
            }
        }
    }

    private fun processConnectionStateChange(state: Wallet.Model.ConnectionState) {
        /*runOnUiThread {
            Toast.makeText(this@MainActivity,
                "onConnectionStateChange: $state", Toast.LENGTH_SHORT).show()
        }*/
    }

    private fun processError(error: Wallet.Model.Error) {
        runOnUiThread {
            Toast.makeText(this@MainActivity,
                error.throwable.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createAndShowDefaultDialog(
        title: String, message: String,
        positiveBtnText: String?, positiveCallbank: DialogInterface.OnClickListener?,
        negativeBtnText: String?, negativeCallbank: DialogInterface.OnClickListener?
    ) {

        if (alertDialog != null && alertDialog!!.isShowing) {
            alertDialog!!.dismiss()
        }

        val alertDialogBuilder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)

        if (!positiveBtnText.isNullOrBlank()) {
            alertDialogBuilder.setPositiveButton(positiveBtnText, positiveCallbank)
        }

        if (!negativeBtnText.isNullOrBlank()) {
            alertDialogBuilder.setNegativeButton(negativeBtnText, negativeCallbank)
        }

        alertDialog = alertDialogBuilder.show()
        alertDialog!!.setCancelable(false)
        alertDialog!!.setCanceledOnTouchOutside(false)
    }

    private fun createAndShowCustomDialog(title: String, message: String,
                                          negativeCallback: (() -> Unit) = {}): DialogDataObject {

        if (alertDialog != null && alertDialog!!.isShowing) {
            alertDialog!!.dismiss()
        }

        val inflater = this.layoutInflater;
        val alertDialogView = inflater.inflate(R.layout.alert_dialog, null)

        alertDialogView.findViewById<TextView>(R.id.dialogMessage).text = message
        alertDialogView.findViewById<TextInputLayout>(R.id.dialogPinInput).editText?.setText(alertDialogPin)

        alertDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setPositiveButton("Tap Your Card To Sign") { _, _ ->
            }
            .setNegativeButton("Cancel") { _, _ ->
                negativeCallback()
            }
            .setOnDismissListener {
                nfcCallback = { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
            }
            .create()

        alertDialog!!.setCancelable(false)
        alertDialog!!.setCanceledOnTouchOutside(false)
        alertDialog!!.setView(alertDialogView)
        alertDialog!!.show()
        alertDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        return DialogDataObject(alertDialog!!, alertDialogView)
    }
}
