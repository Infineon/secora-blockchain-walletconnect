package com.infineon.walletconnect.v1.sample

import android.nfc.tech.IsoDep
import com.github.infineon.NfcTranceiver


class IsoTagWrapper : NfcTranceiver {

    private var isoDep: IsoDep
    private var timeout: Int = 30000 // 30 seconds

    constructor(isoDep: IsoDep) {
        isoDep.timeout = timeout
        this.isoDep = isoDep
    }

    /**
     * Sends a command APDU to the NFC card and returns the received response APDU.
     *
     * @param p0 command APDU to send
     * @return bytes received as response
     */
    override fun transceive(p0: ByteArray?): ByteArray {
        if (!isoDep.isConnected) {
            isoDep.connect()
        }
        return isoDep.transceive(p0)
    }
}