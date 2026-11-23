package me.serce.solidity.chain

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Secrets of the Chains tool window (RPC URLs with API keys, the Etherscan key, remembered keystore passwords) live in
 * the IDE's password safe, which is the system keychain on macOS, never in project or settings files.
 */
object Secrets {
  fun attributes(key: String) = CredentialAttributes(generateServiceName("Solidity", key))

  fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))?.takeIf { it.isNotEmpty() }

  /** Null or empty removes the secret. */
  fun set(key: String, value: String?) {
    PasswordSafe.instance.set(attributes(key), value?.takeIf { it.isNotEmpty() }?.let { Credentials("", it) })
  }

  fun networkUrlKey(name: String) = "network URL: $name"
  fun keystorePasswordKey(path: String) = "keystore password: $path"
  const val ETHERSCAN_KEY = "Etherscan API key"
}
