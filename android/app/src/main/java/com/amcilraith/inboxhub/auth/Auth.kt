package com.amcilraith.inboxhub.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.amcilraith.inboxhub.data.get
import com.amcilraith.inboxhub.data.json
import com.amcilraith.inboxhub.data.long
import com.amcilraith.inboxhub.data.obj
import com.amcilraith.inboxhub.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

class SignInRequired(msg: String) : Exception(msg)

/**
 * Sign-in to Superhuman's MCP server: OAuth discovery, dynamic client registration and PKCE,
 * like the desktop app. The result comes back to com.amcilraith.inboxhub:/oauth.
 * Tokens are kept in this app's private storage only.
 */
class Auth(context: Context, private val http: OkHttpClient) {
    companion object {
        const val RESOURCE = "https://mcp.mail.superhuman.com/mcp"
        const val REDIRECT = "com.amcilraith.inboxhub:/oauth"
        const val SCOPES = "openid email profile offline_access"
    }

    private val prefs = context.getSharedPreferences("superhuman-auth", Context.MODE_PRIVATE)
    private val lock = Mutex()

    val signedIn: Boolean get() = prefs.getString("access_token", null) != null

    private suspend fun getJson(url: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$url returned ${r.code}")
            json.parseToJsonElement(r.body!!.string())
        }
    }

    private data class Meta(val issuer: String, val authorize: String, val token: String, val register: String?)

    private suspend fun meta(): Meta {
        prefs.getString("meta", null)?.let { raw ->
            val m = json.parseToJsonElement(raw)
            return Meta(m["issuer"].str()!!, m["authorization_endpoint"].str()!!, m["token_endpoint"].str()!!, m["registration_endpoint"].str())
        }
        val uri = Uri.parse(RESOURCE)
        val origin = "${uri.scheme}://${uri.authority}"
        val prm = runCatching { getJson("$origin/.well-known/oauth-protected-resource${uri.path}") }
            .recoverCatching { getJson("$origin/.well-known/oauth-protected-resource") }.getOrNull()
        val issuer = (prm["authorization_servers"][0].str() ?: origin).trimEnd('/')
        val m = getJson("$issuer/.well-known/oauth-authorization-server")
        prefs.edit().putString("meta", m.toString()).apply()
        return Meta(m["issuer"].str() ?: issuer, m["authorization_endpoint"].str()!!, m["token_endpoint"].str()!!, m["registration_endpoint"].str())
    }

    private suspend fun clientId(m: Meta): String {
        prefs.getString("client_id", null)?.let { return it }
        val reg = m.register ?: throw Exception("Superhuman does not allow app registration")
        val body = obj(
            "client_name" to "Inbox Hub (Android)",
            "redirect_uris" to listOf(REDIRECT),
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none",
            "scope" to SCOPES,
        ).toString()
        val id = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(reg).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val text = r.body!!.string()
                if (!r.isSuccessful) throw Exception("Registering with Superhuman failed (${r.code}): $text")
                json.parseToJsonElement(text)["client_id"].str() ?: throw Exception("No client_id from Superhuman")
            }
        }
        prefs.edit().putString("client_id", id).apply()
        return id
    }

    private fun random(n: Int): String {
        val bytes = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** URL to open in a browser tab to start signing in. */
    suspend fun authorizeUrl(): Uri {
        val m = meta()
        val client = clientId(m)
        val verifier = random(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = random(24)
        prefs.edit().putString("pending_state", state).putString("pending_verifier", verifier).apply()
        return Uri.parse(m.authorize).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", client)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("resource", RESOURCE)
            .build()
    }

    /** Finish sign-in from the redirect URI. */
    suspend fun complete(redirect: Uri) {
        redirect.getQueryParameter("error")?.let { throw Exception(redirect.getQueryParameter("error_description") ?: it) }
        val code = redirect.getQueryParameter("code") ?: throw Exception("Sign-in returned no code")
        val state = redirect.getQueryParameter("state")
        if (state == null || state != prefs.getString("pending_state", null)) throw Exception("Sign-in expired — try again")
        val m = meta()
        redirect.getQueryParameter("iss")?.let { if (it.trimEnd('/') != m.issuer.trimEnd('/')) throw Exception("Unexpected sign-in server") }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", clientId(m))
            .add("code_verifier", prefs.getString("pending_verifier", "")!!)
            .add("resource", RESOURCE)
            .build()
        saveToken(tokenRequest(m, form, null))
        prefs.edit().remove("pending_state").remove("pending_verifier").apply()
    }

    private suspend fun tokenRequest(m: Meta, form: FormBody, oldRefresh: String?): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(m.token).post(form).build()).execute().use { r ->
            val v = json.parseToJsonElement(r.body!!.string())
            if (!r.isSuccessful) throw SignInRequired(v["error_description"].str() ?: v["error"].str() ?: "Sign-in failed (${r.code})")
            if (v["refresh_token"].str() == null && oldRefresh != null) {
                json.parseToJsonElement(v.toString().dropLast(1) + ",\"refresh_token\":\"$oldRefresh\"}")
            } else v
        }
    }

    private fun saveToken(v: JsonElement) {
        prefs.edit()
            .putString("access_token", v["access_token"].str())
            .putString("refresh_token", v["refresh_token"].str())
            .putLong("expires_at", System.currentTimeMillis() / 1000 + (v["expires_in"].long() ?: 3600))
            .apply()
    }

    /** A valid access token, refreshed when close to expiry (or when [force] is set). */
    suspend fun accessToken(force: Boolean = false): String = lock.withLock {
        val token = prefs.getString("access_token", null) ?: throw SignInRequired("Not signed in to Superhuman")
        val expires = prefs.getLong("expires_at", 0)
        if (!force && expires - 60 > System.currentTimeMillis() / 1000) return token
        val refresh = prefs.getString("refresh_token", null) ?: run { signOut(); throw SignInRequired("Superhuman session expired — sign in again") }
        val m = meta()
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId(m))
            .add("resource", RESOURCE)
            .build()
        try {
            val v = tokenRequest(m, form, refresh)
            saveToken(v)
            v["access_token"].str()!!
        } catch (e: SignInRequired) {
            signOut()
            throw SignInRequired("Superhuman session expired — sign in again")
        }
    }

    fun signOut() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }
}
