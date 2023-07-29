package com.infineon.walletconnect.v2.sample

enum class Chains(
    val chainName: String,
    val chainNamespace: String,
    val chainReference: String,
    val methods: List<String>,
    val events: List<String>,
    val order: Int,
    val chainId: String = "$chainNamespace:$chainReference"
) {

    ETHEREUM_MAIN(
        chainName = "Ethereum",
        chainNamespace = Info.Eth.chain,
        chainReference = "1",
        methods = Info.Eth.defaultMethods,
        events = Info.Eth.defaultEvents,
        order = 1
    ),

    ETHEREUM_GOERLI(
        chainName = "Ethereum Goerli",
        chainNamespace = Info.Eth.chain,
        chainReference = "5",
        methods = Info.Eth.defaultMethods,
        events = Info.Eth.defaultEvents,
        order = 2
    );

    sealed class Info {
        abstract val chain: String
        abstract val defaultEvents: List<String>
        abstract val defaultMethods: List<String>

        object Eth: Info() {
            override val chain = "eip155"
            override val defaultEvents: List<String> = listOf("chainChanged", "accountsChanged")
            override val defaultMethods: List<String> = listOf(
                "eth_sendTransaction",
                "eth_signTransaction",
                "eth_sign",
                "eth_signTypedData",
                "eth_signTypedData_v4",
                "personal_sign"
            )
        }
    }
}