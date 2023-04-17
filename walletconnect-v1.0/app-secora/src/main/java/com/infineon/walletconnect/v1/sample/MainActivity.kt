package com.infineon.walletconnect.v1.sample

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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.budiyev.android.codescanner.*
import com.github.infineon.NfcUtils
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.infineon.walletconnect.v1.sample.databinding.ActivityMainBinding
import com.trustwallet.walletconnect.WCClient
import com.trustwallet.walletconnect.exceptions.InvalidSessionException
import com.trustwallet.walletconnect.extensions.toHex
import com.trustwallet.walletconnect.models.WCAccount
import com.trustwallet.walletconnect.models.WCPeerMeta
import com.trustwallet.walletconnect.models.WCSignTransaction
import com.trustwallet.walletconnect.models.binance.WCBinanceCancelOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTradeOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTransferOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTxConfirmParam
import com.trustwallet.walletconnect.models.ethereum.WCEthereumSignMessage
import com.trustwallet.walletconnect.models.ethereum.WCEthereumTransaction
import com.trustwallet.walletconnect.models.session.WCSession
import okhttp3.OkHttpClient
import okhttp3.internal.and
import org.web3j.crypto.*
import org.web3j.crypto.TransactionEncoder.asRlpValues
import org.web3j.crypto.transaction.type.TransactionType
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
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

    private val peerMeta = WCPeerMeta(name = "Example", url = "https://example.com")
    private val wcClient by lazy {
        WCClient(GsonBuilder(), OkHttpClient())
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var codeScanner: CodeScanner
    private lateinit var wcSession: WCSession

    private var alertDialogPin = "0"
    private var alertDialog: AlertDialog? = null
    private var remotePeerMeta: WCPeerMeta? = null
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
                Toast.makeText(this, "Scan result: ${it.text}", Toast.LENGTH_LONG).show()
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

        wcClient.onDisconnect = { _, _ -> onDisconnect() }
        wcClient.onFailure = { t -> onFailure(t) }
        wcClient.onSessionRequest = { _, peer -> onSessionRequest(peer) }
        wcClient.onGetAccounts = { id -> onGetAccounts(id) }

        wcClient.onEthSign = { id, message -> onEthSign(id, message) }
        wcClient.onEthSignTransaction = { id, transaction -> onEthTransaction(id, transaction) }
        wcClient.onEthSendTransaction = { id, transaction -> onEthTransaction(id, transaction, send = true) }

        wcClient.onBnbTrade = { id, order -> onBnbTrade(id, order) }
        wcClient.onBnbCancel = { id, order -> onBnbCancel(id, order) }
        wcClient.onBnbTransfer = { id, order -> onBnbTransfer(id, order) }
        wcClient.onBnbTxConfirm = { _, param -> onBnbTxConfirm(param) }
        wcClient.onSignTransaction = { id, transaction -> onSignTransaction(id, transaction) }

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
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
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
        disconnect()
        wcSession = WCSession.from(uri) ?: throw InvalidSessionException()
        wcClient.connect(wcSession, peerMeta)
    }

    private fun disconnect() {
        if (wcClient.session != null) {
            wcClient.killSession()
        } else {
            wcClient.disconnect()
        }
    }

    private fun approveSession() {
        val address = binding.addressInput.editText?.text?.toString() ?: "Address not set"
        val chainId = binding.chainInput.editText?.text?.toString()?.toIntOrNull() ?: 1
        wcClient.approveSession(listOf(address), chainId)
        binding.connectButton.text = "Kill Session"
        binding.connectButton.setOnClickListener {
            disconnect()
        }
    }

    private fun rejectSession() {
        wcClient.rejectSession()
        wcClient.disconnect()
    }

    private fun onDisconnect() {
        setupConnectButton()
    }

    private fun onFailure(throwable: Throwable) {
        throwable.printStackTrace()
    }

    private fun onSessionRequest(peer: WCPeerMeta) {
        runOnUiThread {
            remotePeerMeta = peer
            wcClient.remotePeerId ?: run {
                println("remotePeerId can't be null")
                return@runOnUiThread
            }
            val meta = remotePeerMeta ?: return@runOnUiThread

            createAndShowDefaultDialog(meta.name,
                "${meta.description}\n${meta.url}",
                "Approve",
                { _, _ ->
                    approveSession()
                },
                "Reject",
                { _, _ ->
                    rejectSession()
                })
        }
    }

    private fun onEthSign(id: Long, message: WCEthereumSignMessage) {
        runOnUiThread {
            val byteArrayData: ByteArray
            val dialogMessage: String

            when (message.type) {
                WCEthereumSignMessage.WCSignType.MESSAGE -> {
                    byteArrayData = message.data.decodeHex()
                    if (byteArrayData.size == 32) {
                        /* eth_sign (legacy) */
                        dialogMessage = message.data
                    } else {
                        /* eth_sign (standard) */
                        dialogMessage = message.data.decodeHex().toString(Charset.defaultCharset())
                    }
                }
                WCEthereumSignMessage.WCSignType.PERSONAL_MESSAGE -> {
                    byteArrayData = message.data.decodeHex()
                    dialogMessage = message.data.decodeHex().toString(Charset.defaultCharset())
                }
                WCEthereumSignMessage.WCSignType.TYPED_MESSAGE -> {
                    val structuredDataEncoder = StructuredDataEncoder(message.data)
                    byteArrayData = structuredDataEncoder.structuredData
                    //byteArrayData = EthereumAbi.encodeTyped(message.data)
                    dialogMessage = message.data
                }
                else -> {
                    throw Exception("Unsupported WCSignType")
                }
            }

            val alertDialogObject = createAndShowCustomDialog(id, message.type.name, dialogMessage)

            nfcCallback = { isoTagWrapper ->
                try {
                    val prefix = ("\u0019Ethereum Signed Message:\n" + byteArrayData.size).toByteArray(Charsets.UTF_8)
                    val byteArrayToSign: ByteArray

                    /* https://docs.walletconnect.com/1.0/json-rpc-api-methods/ethereum */
                    when (message.type) {
                        WCEthereumSignMessage.WCSignType.MESSAGE -> {
                            if (byteArrayData.size == 32) {
                                /* eth_sign (legacy) */
                                byteArrayToSign = byteArrayData
                            } else {
                                /* eth_sign (standard) */

                                byteArrayToSign = Hash.sha3(prefix + byteArrayData)
                            }
                        }
                        WCEthereumSignMessage.WCSignType.PERSONAL_MESSAGE -> {
                            byteArrayToSign = Hash.sha3(prefix + byteArrayData)
                        }
                        WCEthereumSignMessage.WCSignType.TYPED_MESSAGE -> {
                            byteArrayToSign = Hash.sha3(byteArrayData)
                        }

                        else -> {
                            throw Exception("Unsupported WCSignType")
                        }
                    }

                    val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
                    alertDialogPin = alertDialogObject.view.findViewById<TextInputLayout>(R.id.dialogPinInput).editText?.text.toString()
                    val pin = if (alertDialogPin != "0") {
                        alertDialogPin.decodeHex()
                    } else {
                        null
                    }
                    val sig = signing(isoTagWrapper, Integer.parseInt(keyHandle), pin, byteArrayToSign)
                    val rsv = sig.r + sig.s + sig.v

                    wcClient.approveRequest(id, "0x" + rsv.toHex())
                } catch (e: Exception) {
                    wcClient.rejectRequest(id)
                    throw e
                } finally {
                    alertDialogObject.alertDialog.dismiss()
                }
            }
        }
    }

    private fun onEthTransaction(id: Long, payload: WCEthereumTransaction, send: Boolean = false) {
        runOnUiThread {
            val chainId = (binding.chainInput.editText?.text?.toString() ?: "1").toLong()
            val web3j: Web3j

            when (chainId) {
                1.toLong() -> {
                    /* Public node provider examples:
                       - https://www.infura.io/
                       - https://blastapi.io/public-api/ethereum
                       - and many more ... */
                    web3j = Web3j.build(HttpService("https://eth-mainnet.public.blastapi.io"))
                }
                else -> {
                    wcClient.rejectRequest(id, "ChainId ${chainId} is not supported")
                    return@runOnUiThread
                }
            }

            val address = binding.addressInput.editText?.text?.toString() ?: "Address not set"
            val nonce = web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).sendAsync().get().transactionCount
            val gasPrice = if (payload.gasPrice.isNullOrBlank()) {
                web3j.ethGasPrice().sendAsync().get().gasPrice
            } else {
                BigInteger(payload.gasPrice!!.removePrefix("0x"), 16)
            }
            val gasLimit = if (payload.gas.isNullOrBlank()) {
                web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).sendAsync().get().block.gasLimit
            } else {
                BigInteger(payload.gas!!.removePrefix("0x"), 16)
            }
            var value = if (payload.value.isNullOrBlank()) {
                throw Exception("Field value is null")
            } else {
                payload.value!!.removePrefix("0x")
            }
            val rawTransaction = RawTransaction.createTransaction(nonce, gasPrice,
                gasLimit, payload.to, BigInteger(value, 16),
                payload.data)
            val encodedTransaction = TransactionEncoder.encode(rawTransaction, chainId)
            val byteArrayToSign = Hash.sha3(encodedTransaction)
            val payloadPreview = Gson().toJson(rawTransaction, RawTransaction::class.java)

            val alertDialogObject = createAndShowCustomDialog(id, "Transaction", payloadPreview.toString())

            nfcCallback = { isoTagWrapper ->
                try {
                    val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
                    alertDialogPin = alertDialogObject.view.findViewById<TextInputLayout>(R.id.dialogPinInput).editText?.text.toString()
                    val pin = if (alertDialogPin != "0") {
                        alertDialogPin.decodeHex()
                    } else {
                        null
                    }
                    val sig = signing(isoTagWrapper, Integer.parseInt(keyHandle), pin, byteArrayToSign)

                    if (!send) {
                        val rsv = sig.r + sig.s + sig.v
                        wcClient.approveRequest(id, "0x" + rsv.toHex())
                    } else {
                        if (rawTransaction.type != TransactionType.LEGACY) {
                            wcClient.rejectRequest(id, "Transaction type is not supported")
                        } else {
                            val signatureData = Sign.SignatureData(sig.v, sig.r, sig.s)
                            val eip155SignatureData =
                                TransactionEncoder.createEip155SignatureData(signatureData, chainId)
                            val values = asRlpValues(rawTransaction, eip155SignatureData)
                            val rlpList = RlpList(values)
                            val encoded = RlpEncoder.encode(rlpList)
                            val hexString = "0x" + encoded.toHex()
                            val ethSendRawTransaction =
                                web3j.ethSendRawTransaction(hexString).sendAsync().get()
                            val error = ethSendRawTransaction.error

                            if (error != null) {
                                wcClient.rejectRequest(id, error.message)
                            } else {
                                wcClient.approveRequest(id, ethSendRawTransaction.transactionHash)
                            }
                        }
                    }
                } catch (e: Exception) {
                    wcClient.rejectRequest(id, e.message.toString())
                    throw e
                } finally {
                    alertDialogObject.alertDialog.dismiss()
                }
            }
        }
    }

    private fun onBnbTrade(id: Long, order: WCBinanceTradeOrder) {
        createAndShowDefaultDialog("Warning",
            "bnb_sign is not implemented.",
            "Dismiss", null,
            null, null)
    }

    private fun onBnbCancel(id: Long, order: WCBinanceCancelOrder) {
        createAndShowDefaultDialog("Warning",
            "bnb_sign is not implemented.",
            "Dismiss", null,
            null, null)
    }

    private fun onBnbTransfer(id: Long, order: WCBinanceTransferOrder) {
        createAndShowDefaultDialog("Warning",
            "bnb_sign is not implemented.",
            "Dismiss", null,
            null, null)
    }

    private fun onBnbTxConfirm(param: WCBinanceTxConfirmParam) {
        createAndShowDefaultDialog("Warning",
            "bnb_tx_confirmation is not implemented.",
            "Dismiss", null,
            null, null)
    }

    private fun onGetAccounts(id: Long) {
        val account = WCAccount(
            binding.chainInput.editText?.text?.toString()?.toIntOrNull() ?: 1,
            binding.addressInput.editText?.text?.toString() ?: "Address not set",
        )
        wcClient.approveRequest(id, account)
    }

    private fun onSignTransaction(id: Long, payload: WCSignTransaction) {
        createAndShowDefaultDialog("Warning",
            "trust_signTransaction is not implemented.",
            "Dismiss", null,
            null, null)
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return removePrefix("0x")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun openNfcSettings() {
        Toast.makeText(this, "Please enable NFC", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
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

    private fun createAndShowCustomDialog(id: Long, title: String, message: String): DialogDataObject {

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
                wcClient.rejectRequest(id, "User canceled")
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
