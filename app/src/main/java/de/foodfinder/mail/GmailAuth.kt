package de.foodfinder.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import de.foodfinder.data.Http
import de.foodfinder.data.Prefs
import de.foodfinder.data.get
import de.foodfinder.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Google OAuth 2.0 loopback flow (Desktop client, PKCE) without Play Services. */
class GmailAuth(private val ctx: Context, private val prefs: Prefs) {
    val signedIn: Boolean get() = prefs.gmailConfigured
    val account: String? get() = prefs.gmailAccount

    private val rnd = SecureRandom()
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private fun randomString(n: Int) = buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun signIn(ctx: Context): String = withContext(Dispatchers.IO) {
        val clientId = prefs.gmailClientId
        val clientSecret = prefs.gmailClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) throw IllegalStateException("Bitte zuerst Client-ID und Client-Secret eintragen (Google-Zugang einrichten).")
        val verifier = randomString(64)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val state = randomString(24)
        val server = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        catch (e: Exception) { throw IllegalStateException("Lokaler Anmelde-Server konnte nicht gestartet werden.") }
        val redirect = "http://127.0.0.1:${server.localPort}"
        try {
            val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${enc(clientId)}&redirect_uri=${enc(redirect)}&response_type=code" +
                "&scope=${enc("https://www.googleapis.com/auth/gmail.readonly openid email")}&state=$state" +
                "&code_challenge=$challenge&code_challenge_method=S256&access_type=offline&prompt=consent"
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            val params = awaitRedirect(server)
            val err = params["error"]
            if (err != null) throw IllegalStateException(if (err == "access_denied") "Anmeldung abgebrochen." else "Google-Fehler: $err")
            if (params["state"] != state) throw IllegalStateException("Anmeldung ungültig (state stimmt nicht).")
            val code = params["code"] ?: throw IllegalStateException("Google hat keinen Code geliefert.")

            val j = tokenRequest(FormBody.Builder()
                .add("code", code).add("client_id", clientId).add("client_secret", clientSecret)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").add("code_verifier", verifier).build())
            val refresh = j["refresh_token"].str ?: throw IllegalStateException("Google hat kein Refresh-Token geliefert. Bitte erneut anmelden.")
            store(j)
            prefs.gmailRefresh = refresh
            val email = emailFromIdToken(j["id_token"].str) ?: runCatching {
                Http.parse(Http.get("https://openidconnect.googleapis.com/v1/userinfo", mapOf("Authorization" to "Bearer ${j["access_token"].str}")))["email"].str
            }.getOrNull() ?: "Google-Konto"
            prefs.gmailAccount = email
            email
        } finally {
            runCatching { server.close() }
        }
    }

    private fun awaitRedirect(server: ServerSocket): Map<String, String> {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (true) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) throw IllegalStateException("Zeitüberschreitung bei der Anmeldung (5 Minuten).")
            server.soTimeout = left
            val sock = try { server.accept() } catch (e: SocketTimeoutException) {
                throw IllegalStateException("Zeitüberschreitung bei der Anmeldung (5 Minuten).")
            }
            var result: Map<String, String>? = null
            sock.use { s ->
                s.soTimeout = 10_000
                val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull() ?: return@use
                val path = line.split(" ").getOrNull(1) ?: return@use
                val query = path.substringAfter('?', "")
                val params = query.split("&").filter { it.contains("=") }.associate {
                    URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                }
                val ok = params.containsKey("code") || params.containsKey("error")
                val html = if (ok) "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
                    "<body style=\"font-family:sans-serif;padding:32px\"><h2>Fertig – du kannst zu FoodFinder zurückkehren.</h2></body></html>" else ""
                val bytes = html.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes); out.flush()
                if (ok) result = params
            }
            result?.let { return it }
        }
    }

    private fun tokenRequest(body: FormBody): JsonElement =
        Http.client.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use { r ->
            val txt = r.body?.string() ?: ""
            val j = runCatching { Http.parse(txt) }.getOrNull()
            if (!r.isSuccessful) {
                val e = j["error"].str
                throw IllegalStateException(when (e) {
                    "invalid_grant" -> "Anmeldung abgelaufen oder widerrufen. Bitte neu anmelden."
                    "invalid_client" -> "Client-ID oder Client-Secret ungültig."
                    else -> "Google-Anmeldung fehlgeschlagen (${e ?: "HTTP ${r.code}"})."
                })
            }
            j ?: throw IllegalStateException("Unerwartete Antwort von Google.")
        }

    private fun store(j: JsonElement) {
        prefs.gmailAccess = j["access_token"].str
        val secs = j["expires_in"].str?.toLongOrNull() ?: 3600
        prefs.gmailAccessExp = System.currentTimeMillis() + secs * 1000
    }

    private fun emailFromIdToken(t: String?): String? = runCatching {
        val payload = String(Base64.decode(t!!.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        Http.parse(payload)["email"].str
    }.getOrNull()

    /** Valid access token (refreshed if needed) or null when signed out / refresh failed. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val refresh = prefs.gmailRefresh ?: return@withContext null
        val cur = prefs.gmailAccess
        if (cur != null && prefs.gmailAccessExp - 60_000 > System.currentTimeMillis()) return@withContext cur
        try {
            val j = tokenRequest(FormBody.Builder().add("client_id", prefs.gmailClientId).add("client_secret", prefs.gmailClientSecret)
                .add("refresh_token", refresh).add("grant_type", "refresh_token").build())
            store(j)
            prefs.gmailAccess
        } catch (e: IllegalStateException) {
            if (e.message?.contains("abgelaufen") == true) signOutLocal()
            null
        } catch (e: Exception) { null }
    }

    private fun signOutLocal() {
        prefs.gmailRefresh = null; prefs.gmailAccess = null; prefs.gmailAccessExp = 0; prefs.gmailAccount = null
    }

    fun signOut() {
        val token = prefs.gmailRefresh
        signOutLocal()
        if (token != null) Thread {
            runCatching {
                Http.client.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke")
                    .post(FormBody.Builder().add("token", token).build()).build()).execute().close()
            }
        }.start()
    }
}
