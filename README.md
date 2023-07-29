[![Github actions](https://github.com/infineon/secora-blockchain-walletconnect/actions/workflows/main.yml/badge.svg)](https://github.com/infineon/secora-blockchain-walletconnect/actions)

# Introduction

In this project, you'll find two Android applications that utilize the [WalletConnect](https://walletconnect.com/) protocol to connect [Infineon's SECORA™ Blockchain](https://www.infineon.com/cms/en/product/security-smart-card-solutions/secora-security-solutions/secora-blockchain-security-solutions/) or [Blockchain Security 2Go starter kit](https://www.infineon.com/cms/en/product/evaluation-boards/blockchainstartkit/) with Dapps (Web3 Apps).

What is WalletConnect? WalletConnect is a widely supported open protocol that enables secured communication between wallets and Dapps.

# Project Overview

<table>
  <tr>
    <th>WalletConnect Version</th>
    <th>Description</th>
  </tr>
  <tr>
    <td>v1.0<br><b>*This version has already been sunsetted</b></td>
    <td>The implementation is based on examples from <a href="https://docs.walletconnect.com/1.0/quick-start/wallets/kotlin">WalletConnect v1.0</a>. The project is built on top of <a href="https://github.com/trustwallet/wallet-connect-kotlin/tree/75b2590a7002a0c7ef1c0ee9633aac58eed0c643">wallet-connect-kotlin@75b2590</a> and utilizes the <a href="https://github.com/infineon/secora-blockchain-apdu-java-library">secora-blockchain-apdu-java-library</a> to communicate with Infineon's SECORA™ Blockchain.</td>
  </tr>
  <tr>
    <td>v2.0</td>
    <td>The implementation is based on examples from <a href="https://docs.walletconnect.com/2.0/kotlin/guides/examples-and-resources">WalletConnect v2.0</a>. The project is built on top of <a href="https://github.com/WalletConnect/WalletConnectKotlinV2/tree/BOM_1.4.0/web3/wallet">WalletConnectKotlinV2@BOM_1.4.0</a> and utilizes the <a href="https://github.com/infineon/secora-blockchain-apdu-java-library">secora-blockchain-apdu-java-library</a> to communicate with Infineon's SECORA™ Blockchain.</td>
  </tr>
</table>

# Demo

- [OpenSea NFT Marketplace](https://opensea.io):
  ![](https://github.com/infineon/secora-blockchain-walletconnect/blob/master/media/wc1.0_opensea.gif)
- [Example Dapp for WalletConnect v1.0](https://example.walletconnect.org):
  ![](https://github.com/infineon/secora-blockchain-walletconnect/blob/master/media/wc1.0_example.gif)
- [Example Dapp for WalletConnect v2.0](https://react-app.walletconnect.com):
  ![](https://github.com/infineon/secora-blockchain-walletconnect/blob/master/media/wc2.0_example.gif)

# License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
