package me.serce.solidity.chain.rpc

import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * HTTP clients for RPCs, Sourcify and Etherscan that use the IDE's proxy (Settings | HTTP Proxy) and trust the
 * certificates it accepts (Settings | Server Certificates), so they work behind a corporate proxy.
 */
object ChainHttp {
  fun client(connectTimeout: Duration, redirect: HttpClient.Redirect = HttpClient.Redirect.NEVER): HttpClient {
    val ide = JdkProxyProvider.getInstance()
    return HttpClient.newBuilder()
      .connectTimeout(connectTimeout)
      .sslContext(CertificateManager.getInstance().sslContext)
      .followRedirects(redirect)
      .proxy(LocalDirect(ide.proxySelector))
      .authenticator(ide.authenticator)
      .build()
  }

  /** A local node (anvil) is never reached through a proxy, whatever the proxy settings say. */
  private class LocalDirect(private val ide: ProxySelector) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = if (isLocal(uri.host)) listOf(Proxy.NO_PROXY) else ide.select(uri)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = ide.connectFailed(uri, sa, ioe)

    private fun isLocal(host: String?): Boolean {
      val h = host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
      return h == "localhost" || h.endsWith(".localhost") || h.startsWith("127.") || h == "::1" || h == "0.0.0.0"
    }
  }
}
