// SimpleServer.kt — ListmakerAndroid
// Mirrors your Flask routes & JSON file formats.
// Place HTML/JS/CSS under: app/src/main/assets/www/
//
// Pages we serve (static from assets):
//   /login         -> /login.html
//   /signup        -> /signup.html
//   /dashboard     -> /dashboard.html
//   /events        -> /event.html
// Assets:
//   /static/...    -> /assets/www/static/...
//
// APIs:
//   GET  /api/session                     -> {username, role}
//   POST /api/login  {username,password}  -> sets Auth cookie
//   POST /api/signup {username,password,role}
//
//   /api/users       GET (auth), POST/PUT/DELETE (admin)
//   /api/questions   GET (auth), POST (admin)
//   /api/temp_events GET/POST/DELETE (auth; mutate = admin)
//   /api/responses   GET/POST (auth)
//   /api/all_responses GET (admin)
//   POST /api/submit_response (auth)
//
// Files (internal storage):
//   users.json        (login accounts)     -> {"alice":{"password":"<hash>","role":"admin"}, ...}
//   data.json         (known people list)  -> [{"name":"...","phone":"...","address":"..."}]
//   questions.json    (string array)       -> ["Q1","Q2"]
//   temp_events.json  (events by title)    -> { "Title": {...EventModel} }
//   responses.json    (event->user map)    -> { "Title": {"Alice":{"responses":{...}}} }

package com.example.ListMakerAndroid

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Log


// alongside AccountRec, Person, etc.
data class UserUpdateReq(
    val name: String = "",
    val phone: String? = null,
    val address: String? = null,
    val events: Any? = null // ignored, kept for Flask parity
)

class SimpleServer(
    private val context: Context,
    port: Int = 53399,
) : NanoHTTPD(port) {

    private val gson = Gson()

    // ---------- File names (match Flask) ----------
    private val USERS_FILE = "users.json"          // accounts map
    private val DATA_FILE = "data.json"            // known users list for events
    private val QUESTIONS_FILE = "questions.json"
    private val RESPONSES_FILE = "responses.json"
    private val TEMP_EVENTS_FILE = "temp_events.json"

    // ---------- Models ----------
    data class AccountRec(
        var password: String,
        var role: String
    ) // password = hash; role = "admin"|"user"

    data class Person(var name: String, var phone: String? = null, var address: String? = null)

    data class DateSlot(var date: String = "", var times: MutableList<String> = mutableListOf())

    data class Participant(
        var name: String,
        var phone: String? = null,
        var address: String? = null,
        var responses: MutableMap<String, String> = mutableMapOf()
    )

    data class EventModel(
        var title: String = "",
        var dates: MutableList<DateSlot> = mutableListOf(),
        var participants: MutableList<Participant> = mutableListOf(),
        var questions: MutableList<String> = mutableListOf(),
        var temp_questions: MutableList<String> = mutableListOf()
    )

    // ---------- In-memory stores (backed by files) ----------
    private var accounts: MutableMap<String, AccountRec> =
        readJsonFile<MutableMap<String, AccountRec>>(USERS_FILE) ?: mutableMapOf()

    private var people: MutableList<Person> =
        readJsonFile<MutableList<Person>>(DATA_FILE) ?: mutableListOf()

    private var questionsStore: MutableList<String> =
        readJsonFile<MutableList<String>>(QUESTIONS_FILE) ?: mutableListOf()

    private var eventsStore: MutableMap<String, EventModel> =
        readJsonFile<MutableMap<String, EventModel>>(TEMP_EVENTS_FILE) ?: mutableMapOf()

    // responses: { event: { username: { responses: { q: a } } } }
    private var responsesStore: MutableMap<String, MutableMap<String, MutableMap<String, String>>> =
        readJsonFile<MutableMap<String, MutableMap<String, MutableMap<String, String>>>>(
            RESPONSES_FILE
        ) ?: mutableMapOf()

    // session tokens
    private val tokens = mutableMapOf<String, String>() // token -> username

    // ---------- Helpers: JSON file IO ----------
    private inline fun <reified T> readJsonFile(file: String): T? = try {
        val path = "${context.filesDir}/$file"
        android.util.Log.d("SimpleServer", "readJsonFile: opening $path")
        val txt = context.openFileInput(file).bufferedReader().use { it.readText() }
        android.util.Log.d("SimpleServer", "readJsonFile: $file content = $txt")
        gson.fromJson<T>(txt, object : TypeToken<T>() {}.type)
    } catch (e: Exception) {
        android.util.Log.e("SimpleServer", "readJsonFile: failed for $file", e)
        null
    }

    private fun writeJsonFile(file: String, any: Any) {
        val path = "${context.filesDir}/$file"
        val txt = gson.toJson(any)
        android.util.Log.d("SimpleServer", "writeJsonFile: saving $file at $path, content = $txt")
        try {
            context.openFileOutput(file, Context.MODE_PRIVATE)
                .use { it.write(txt.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            android.util.Log.e("SimpleServer", "writeJsonFile: failed for $file", e)
        }
    }

    // ---------- Helpers: responses ----------
    private fun json(any: Any): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(any)
        )

    private fun ok(): Response = json(mapOf("ok" to true))

    private fun err(msg: String, code: Response.Status = Response.Status.BAD_REQUEST): Response =
        newFixedLengthResponse(
            code,
            "application/json; charset=utf-8",
            """{"ok":false,"error":${gson.toJson(msg)}}"""
        )

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found")

    private fun redirect(to: String): Response =
        newFixedLengthResponse(
            Response.Status.REDIRECT,
            "text/plain; charset=utf-8",
            "Redirect"
        ).apply {
            addHeader("Location", to)
        }

    // ---------- Helpers: body & cookies ----------
    private fun readPostJson(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        return try {
            session.parseBody(files)
            // NanoHTTPD uses "postData" for raw body. Sometimes it writes to a temp file under "content".
            when {
                files["postData"] != null -> files["postData"]!!
                files["content"] != null -> File(files["content"]!!).readText(Charsets.UTF_8)
                else -> session.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private inline fun <reified T> parse(json: String): T =
        gson.fromJson(json, object : TypeToken<T>() {}.type)

    private fun cookieMap(session: IHTTPSession): Map<String, String> =
        (session.headers["cookie"] ?: "")
            .split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate {
                val i = it.indexOf('=')
                it.substring(0, i) to it.substring(i + 1)
            }

    private fun currentUser(session: IHTTPSession): Pair<String, AccountRec>? {
        val token = cookieMap(session)["Auth"] ?: return null
        val username = tokens[token] ?: return null
        val acc = accounts[username] ?: return null
        return username to acc
    }

    private fun unauthorized(): Response = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED, "application/json; charset=utf-8",
        """{"ok":false,"error":"unauthorized"}"""
    )

    // ---------- Password hashing (PBKDF2) ----------
    // Stored format: pbkdf2$<iterations>$<salt_b64>$<hash_b64>
    private fun hashPassword(password: String, iterations: Int = 120_000): String {
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key =
            skf.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
        val b64 = java.util.Base64.getEncoder()
        return "pbkdf2$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(key)}"
    }

    private fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iters = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = skf.generateSecret(
            PBEKeySpec(
                password.toCharArray(),
                salt,
                iters,
                expected.size * 8
            )
        ).encoded
        return key.contentEquals(expected)
    }

    // ---------- Assets ----------
    private fun guessMime(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        else -> "text/plain; charset=utf-8"
    }

    private fun serveAsset(path: String): Response? {
        val assetPath = if (path.startsWith("/")) "www$path" else "www/$path"
        return try {
            val ai = context.assets.open(assetPath)
            val bytes = ai.readBytes()
            ai.close()
            val mime = guessMime(path)
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                mime,
                bytes.inputStream(),
                bytes.size.toLong()
            )
            // prevent caching of HTML documents
            if (mime.startsWith("text/html")) {
                resp.addHeader("Cache-Control", "no-store")
            }
            resp
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Routing ----------
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val cookieHeader = session.headers["cookie"] ?: ""

        Log.d("SimpleServer", "➡️ serve(method=$method uri=$uri cookie=$cookieHeader)")

        return try {
            // Static assets under /static/...
            if (method == Method.GET && uri.startsWith("/static/")) {
                Log.d("SimpleServer", "🟢 Serving static asset $uri")
                return serveAsset(uri) ?: notFound().also {
                    Log.w("SimpleServer", "❌ Asset not found $uri")
                }
            }

            // Pages (static)
            if (method == Method.GET && (
                        uri == "/login" ||
                                uri == "/signup" ||
                                uri == "/dashboard" ||
                                uri == "/events" ||
                                uri == "/userevents" ||
                                uri == "/userevents_admin"
                        )) {

                val me = currentUser(session)

                // 🔑 Special handling for /login
                if (uri == "/login") {
                    if (me != null) {
                        val role = me.second.role
                        val redirectTarget = if (role == "admin") "/dashboard" else "/userevents"
                        Log.d("SimpleServer", "🔄 Already logged in, redirecting $role -> $redirectTarget")
                        return redirect(redirectTarget)
                    }
                    // Not logged in → just show login.html
                    return serveAsset("/login.html") ?: notFound()
                }

                // 🔑 Protect /dashboard: must be logged in *and* admin
                if (uri == "/dashboard") {
                    if (me == null) {
                        Log.w("SimpleServer", "❌ Unauthorized access to /dashboard (no session)")
                        return redirect("/login")
                    }
                    if (me.second.role != "admin") {
                        Log.w("SimpleServer", "❌ Non-admin tried to access /dashboard, redirecting to /userevents")
                        return redirect("/userevents")
                    }
                }

                val file = when (uri) {
                    "/signup" -> "/signup.html"
                    "/dashboard" -> "/dashboard.html"
                    "/events" -> "/event.html"
                    "/userevents" -> "/userevents.html"
                    "/userevents_admin" -> "/userevents_admin.html"
                    else -> "/dashboard.html"
                }

                Log.d("SimpleServer", "📄 Serving page for $uri -> $file (user=${me?.first ?: "none"})")
                return serveAsset(file) ?: notFound().also {
                    Log.w("SimpleServer", "❌ Page asset missing $file")
                }
            }


            // Root -> redirect based on session
            if (method == Method.GET && uri == "/") {
                val user = currentUser(session)
                val redirectTarget = when (user?.second?.role) {
                    "user" -> "/userevents"
                    "admin" -> "/dashboard"
                    else -> "/login"
                }
                Log.d(
                    "SimpleServer",
                    "➡️ Root redirect for user=${user?.first ?: "none"} role=${user?.second?.role ?: "none"} -> $redirectTarget"
                )
                return redirect(redirectTarget)
            }
            // Logout
            if (method == Method.GET && uri == "/logout") {
                Log.d("SimpleServer", "👋 Logout requested, cookie=$cookieHeader")
                val token = cookieMap(session)["Auth"]
                val username = token?.let { tokens[it] }

                if (token != null) tokens.remove(token)
                if (username != null) {
                    val it = tokens.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        if (e.value == username) it.remove()
                    }
                }

                return redirect("/login").apply {
                    addHeader(
                        "Set-Cookie",
                        "Auth=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax"
                    )
                    addHeader("Cache-Control", "no-store")
                }
            }

            // JSON APIs
            if (uri.startsWith("/api/")) {
                Log.d("SimpleServer", "🔎 API call $method $uri")
                val resp = serveApi(session, uri)
                Log.d(
                    "SimpleServer",
                    "✅ API response $method $uri -> ${resp.status.requestStatus} ${resp.status.description}"
                )
                return resp
            }

            // Fallback to static (if you link /style.css directly)
            if (method == Method.GET) {
                Log.d("SimpleServer", "⚪ Fallback serveAsset for $uri")
                return serveAsset(uri) ?: notFound().also {
                    Log.w("SimpleServer", "❌ Fallback not found $uri")
                }
            }

            // Anything else
            Log.w("SimpleServer", "❌ No matching handler for $method $uri")
            notFound()
        } catch (t: Throwable) {
            Log.e("SimpleServer", "💥 Unhandled error for $method $uri", t)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
                """{"ok":false,"error":"internal_error"}"""
            ).apply { addHeader("Cache-Control", "no-store") }
        }
    }

    private fun serveApi(session: IHTTPSession, uri: String): Response {
        return when (session.method) {
            Method.GET -> when (uri) {
                "/api/session" -> {
                    val me = currentUser(session)
                    if (me == null) {
                        Log.w("SimpleServer", "❌ /api/session -> unauthorized (no valid session cookie)")
                        return unauthorized()
                    }

                    val (username, acc) = me
                    Log.d(
                        "SimpleServer",
                        "✅ /api/session -> user=$username role=${acc.role}"
                    )
                    json(mapOf("username" to username, "role" to acc.role))
                }

                "/api/users" -> {
                    try {
                        val me = currentUser(session) ?: return unauthorized()
                        android.util.Log.d(
                            "SimpleServer",
                            "GET /api/users -> ${people.size} entries"
                        )
                        return json(people)
                    } catch (t: Throwable) {
                        android.util.Log.e("SimpleServer", "GET /api/users failed", t)
                        return err("internal_error", Response.Status.INTERNAL_ERROR)
                    }
                }

                "/api/questions" -> {
                    if (currentUser(session) == null) return unauthorized()
                    json(questionsStore)
                }

                "/api/questionsets" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    val set = mapOf(
                        "id" to "default",
                        "name" to "Default Set",
                        "questions" to questionsStore
                    )
                    json(listOf(set))
                }

                "/api/temp_events" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    val txt = try {
                        context.openFileInput(TEMP_EVENTS_FILE).bufferedReader()
                            .use { it.readText() }
                    } catch (_: Exception) {
                        "[]"
                    }
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=utf-8",
                        txt
                    ).apply { addHeader("Cache-Control", "no-store") }
                }

                "/api/my_events" -> {
                    val me = currentUser(session)
                    if (me == null) {
                        Log.w("SimpleServer", "❌ /api/my_events unauthorized, no session")
                        return unauthorized()
                    }

                    val (usernameOrig, uobj) = me
                    val username = usernameOrig.lowercase()
                    if (uobj.role != "user") {
                        Log.w("SimpleServer", "❌ /api/my_events forbidden for role=${uobj.role}")
                        return err("Not a user", Response.Status.FORBIDDEN)
                    }

                    // load all events
                    val txt = try {
                        context.openFileInput(TEMP_EVENTS_FILE).bufferedReader().use { it.readText() }
                    } catch (_: Exception) { "[]" }

                    val events: List<MutableMap<String, Any?>> =
                        try { gson.fromJson(txt, List::class.java) as? List<MutableMap<String, Any?>> ?: mutableListOf() }
                        catch (t: Throwable) {
                            Log.e("SimpleServer", "❌ Failed to parse events JSON", t)
                            mutableListOf()
                        }

                    // filter only events where this user is a participant (normalize)
                    val filtered = events.filter { ev ->
                        val parts = ev["participants"] as? List<Map<String, Any?>> ?: emptyList()
                        parts.any { (it["name"] as? String)?.lowercase() == username }
                    }

                    // attach responses consistently (normalize keys)
                    filtered.forEach { ev ->
                        val eventId = ev["id"] as? String ?: return@forEach
                        val responsesForEvent = responsesStore[eventId] ?: emptyMap()

                        // 🔎 Attach only this user’s responses (normalize lookup)
                        val userResp = responsesForEvent.entries.find { it.key.lowercase() == username }?.value ?: emptyMap()
                        ev["responses"] = mapOf(usernameOrig to userResp)

                        val participants = ev["participants"] as? MutableList<MutableMap<String, Any?>> ?: mutableListOf()
                        participants.forEach { p ->
                            val pname = (p["name"] as? String)?.trim().orEmpty()
                            if (pname.lowercase() == username) {
                                val presp = responsesForEvent.entries.find { it.key.lowercase() == username }?.value ?: emptyMap()
                                if (presp.isNotEmpty()) {
                                    p["responses"] = presp
                                    Log.d("SimpleServer", "🔎 Attached responses for user=$pname event=$eventId -> $presp")
                                }
                            }
                        }
                    }

                    Log.d("SimpleServer", "✅ /api/my_events user=$usernameOrig -> ${filtered.size} events")
                    json(filtered)
                }

                "/api/admin_events" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err("Not an admin", Response.Status.FORBIDDEN)

                    // Which user are we impersonating?
                    val targetOrig = session.parms["user"] ?: return err("Missing user", Response.Status.BAD_REQUEST)
                    val target = targetOrig.lowercase()
                    Log.d("SimpleServer", "📥 /api/admin_events requested for target=$targetOrig")

                    // Load events from temp_events.json
                    val eventsJsonText = try {
                        context.openFileInput(TEMP_EVENTS_FILE).bufferedReader().use { it.readText() }
                    } catch (_: Exception) {
                        Log.w("SimpleServer", "⚠️ Could not read $TEMP_EVENTS_FILE, returning empty list")
                        "[]"
                    }

                    // Load responses from responses.json
                    val responses: Map<String, Map<String, Map<String, String>>> =
                        readJsonFile(RESPONSES_FILE) ?: emptyMap()
                    Log.d("SimpleServer", "📖 Loaded responses.json with ${responses.size} events")

                    // Parse events
                    val events: List<MutableMap<String, Any?>> =
                        try {
                            gson.fromJson(eventsJsonText, List::class.java) as? List<MutableMap<String, Any?>> ?: mutableListOf()
                        } catch (t: Throwable) {
                            Log.e("SimpleServer", "❌ Failed to parse events JSON", t)
                            mutableListOf()
                        }
                    Log.d("SimpleServer", "📖 Loaded temp_events.json with ${events.size} events")

                    // Filter to events where `target` is a participant (normalize)
                    val filtered = events.filter { ev ->
                        val parts = ev["participants"] as? List<Map<String, Any?>> ?: emptyList()
                        parts.any { (it["name"] as? String)?.lowercase() == target }
                    }
                    Log.d("SimpleServer", "🔎 Found ${filtered.size} events for user=$targetOrig")

                    // Attach responses from responses.json consistently (normalize)
                    filtered.forEach { ev ->
                        val eventId = ev["id"] as? String ?: return@forEach
                        val responsesForEvent = responses[eventId] ?: emptyMap()

                        val userResp = responsesForEvent.entries.find { it.key.lowercase() == target }?.value ?: emptyMap()

                        val participants = ev["participants"] as? MutableList<MutableMap<String, Any?>> ?: mutableListOf()
                        participants.forEach { p ->
                            val pname = (p["name"] as? String)?.trim().orEmpty()
                            if (pname.lowercase() == target) {
                                if (userResp.isNotEmpty()) {
                                    p["responses"] = userResp
                                    Log.d("SimpleServer", "✅ Attached responses for user=$pname in event=$eventId -> $userResp")
                                }
                            }
                        }

                        // Whole-event responses
                        ev["responses"] = mapOf(targetOrig to userResp)
                    }

                    Log.d("SimpleServer", "✅ /api/admin_events complete for target=$targetOrig -> returning ${filtered.size} events")
                    json(filtered)
                }

                "/api/responses" -> {
                    if (currentUser(session) == null) return unauthorized()
                    json(responsesStore)
                }

                "/api/all_responses" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )

                    val raw = try {
                        context.openFileInput(TEMP_EVENTS_FILE).bufferedReader()
                            .use { it.readText() }
                    } catch (_: Exception) {
                        "[]"
                    }

                    @Suppress("UNCHECKED_CAST")
                    val events: List<Map<String, Any?>> =
                        try {
                            gson.fromJson(raw, List::class.java) as? List<Map<String, Any?>>
                                ?: emptyList()
                        } catch (_: Throwable) {
                            emptyList()
                        }

                    val out = mutableMapOf<String, MutableMap<String, Any>>()
                    events.forEach { ev ->
                        val title = ev["name"] as? String ?: return@forEach
                        val parts = ev["participants"] as? List<*> ?: emptyList<Any>()
                        val perUser = mutableMapOf<String, Any>()
                        parts.forEach { pAny ->
                            val pm = pAny as? Map<*, *> ?: return@forEach
                            val name = (pm["name"] as? String)?.trim().orEmpty()

                            @Suppress("UNCHECKED_CAST")
                            val resp = (pm["responses"] ?: pm["data"]) as? Map<String, String>
                                ?: emptyMap()
                            if (name.isNotBlank() && resp.isNotEmpty()) {
                                perUser[name] = mapOf("responses" to resp)
                            }
                        }
                        if (perUser.isNotEmpty()) out[title] = perUser
                    }
                    return json(out)
                }

                else -> if (uri.startsWith("/api/temp_events/")) {
                    val me = currentUser(session) ?: return unauthorized()
                    val id = uri.removePrefix("/api/temp_events/").trim()
                    val file = File(context.filesDir, TEMP_EVENTS_FILE)
                    val list: MutableList<Map<String, Any?>> =
                        if (file.exists()) gson.fromJson(
                            file.readText(),
                            List::class.java
                        ) as MutableList<Map<String, Any?>>
                        else mutableListOf()
                        val ev = list.find { it["id"] == id }
                        ?: run {
                            android.util.Log.w("SimpleServer", "GET /api/temp_events/$id -> not found")
                            return notFound()
                        }
                    return json(ev)
                } else notFound()
            }

            Method.POST -> when (uri) {
                "/api/login" -> {
                    data class LoginReq(val username: String = "", val password: String = "")
                    val req = parse<LoginReq>(readPostJson(session))
                    val acc = accounts[req.username] ?: return err("Invalid username", Response.Status.UNAUTHORIZED)

                    if (!verifyPassword(req.password, acc.password)) {
                        Log.w("SimpleServer", "❌ Login failed for ${req.username}: invalid password")
                        return err("Invalid password", Response.Status.UNAUTHORIZED)
                    }

                    val token = UUID.randomUUID().toString()
                    tokens[token] = req.username

                    val redirectTo = if (acc.role == "user") "/userevents" else "/dashboard"

                    Log.d("SimpleServer", "🔑 Login success for ${req.username} role=${acc.role} -> redirect=$redirectTo")

                    return json(mapOf("ok" to true, "redirect" to redirectTo)).apply {
                        addHeader("Set-Cookie", "Auth=$token; Path=/; HttpOnly; SameSite=Lax")
                    }
                }


                "/api/signup" -> {
                    data class SignupReq(
                        val username: String = "",
                        val password: String = "",
                        val role: String = "user"
                    )

                    val req = parse<SignupReq>(readPostJson(session))
                    if (req.username.isBlank() || req.password.isBlank()) return err("username/password required")
                    if (accounts.containsKey(req.username)) return err(
                        "Username already exists",
                        Response.Status.CONFLICT
                    )
                    accounts[req.username] = AccountRec(
                        hashPassword(req.password),
                        if (req.role == "admin") "admin" else "user"
                    )
                    writeJsonFile(USERS_FILE, accounts)
                    ok()
                }

                "/api/users" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )
                    val p = parse<Person>(readPostJson(session))
                    if (p.name.isBlank()) return err("Missing name")
                    if (people.any { it.name == p.name }) return err(
                        "Name exists",
                        Response.Status.CONFLICT
                    )
                    people.add(p)
                    writeJsonFile(DATA_FILE, people)
                    json(people)
                }

                "/api/questions" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )
                    val body = parse<Map<String, String>>(readPostJson(session))
                    val text = body["text"]?.trim().orEmpty()
                    if (text.isBlank()) return err("Missing text")
                    questionsStore.add(text)
                    writeJsonFile(QUESTIONS_FILE, questionsStore)
                    json(mapOf("ok" to true, "questions" to questionsStore))
                }

                "/api/temp_events" -> {
                    Log.d("SimpleServer", "📥 POST /api/temp_events called")

                    val me = currentUser(session)
                    if (me == null) {
                        Log.w("SimpleServer", "❌ Unauthorized: no valid session cookie for /api/temp_events")
                        return unauthorized()
                    }

                    val (username, acc) = me
                    Log.d("SimpleServer", "👤 User=$username role=${acc.role} attempting to create event")

                    if (acc.role != "admin") {
                        Log.w("SimpleServer", "❌ Forbidden: user=$username is not admin")
                        return err("Unauthorized", Response.Status.FORBIDDEN)
                    }

                    val body = readPostJson(session)
                    Log.d("SimpleServer", "📄 POST /api/temp_events body=$body")

                    val root = try {
                        gson.fromJson(body, Map::class.java) as Map<String, Any?>
                    } catch (t: Throwable) {
                        Log.e("SimpleServer", "❌ Failed to parse body JSON", t)
                        return err("invalid json", Response.Status.BAD_REQUEST)
                    }

                    val file = File(context.filesDir, TEMP_EVENTS_FILE)
                    val list: MutableList<MutableMap<String, Any?>> =
                        if (file.exists()) {
                            try {
                                gson.fromJson(file.readText(), List::class.java) as MutableList<MutableMap<String, Any?>>
                            } catch (t: Throwable) {
                                Log.e("SimpleServer", "❌ Failed to parse existing $TEMP_EVENTS_FILE, starting fresh", t)
                                mutableListOf()
                            }
                        } else mutableListOf()

                    val newEv = mutableMapOf<String, Any?>(
                        "id" to UUID.randomUUID().toString(),
                        "name" to (root["name"] ?: ""),
                        "date" to (root["date"] ?: ""),
                        "time" to (root["time"] ?: ""),
                        "location" to (root["location"] ?: ""),
                        "host" to (root["host"] ?: ""),
                        "notes" to (root["notes"] ?: ""),
                        "participants" to (root["participants"] ?: emptyList<Any>()),
                        "questions" to (root["questions"] ?: emptyList<Any>()),
                        "temp_questions" to (root["temp_questions"] ?: emptyList<Any>())
                    )

                    list.add(newEv)
                    file.writeText(gson.toJson(list))

                    Log.d("SimpleServer", "✅ New event created: $newEv")
                    return json(newEv)
                }

                "/api/submit_response" -> {
                    if (currentUser(session) == null) return unauthorized()
                    data class RespReq(
                        val event: String = "",
                        val name: String = "",
                        val responses: Map<String, String> = emptyMap()
                    )

                    val req = parse<RespReq>(readPostJson(session))
                    if (req.event.isBlank() || req.name.isBlank() || req.responses.isEmpty()) return err(
                        "Invalid data"
                    )

                    val byEvent = responsesStore.getOrPut(req.event) { mutableMapOf() }
                    byEvent[req.name] =
                        mutableMapOf<String, String>().apply { putAll(req.responses) }
                    responsesStore[req.event] = byEvent
                    writeJsonFile(RESPONSES_FILE, responsesStore)
                    ok()
                }

                "/api/admin_submit_response" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin")
                        return err("Not an admin", Response.Status.FORBIDDEN)

                    data class AdminRespReq(
                        val event: String = "",
                        val as_user: String = "",
                        val responses: Map<String, String> = emptyMap()
                    )
                    val req = parse<AdminRespReq>(readPostJson(session))
                    if (req.event.isBlank() || req.as_user.isBlank() || req.responses.isEmpty())
                        return err("Invalid data")

                    // update responsesStore just like /api/submit_response
                    val byEvent = responsesStore.getOrPut(req.event) { mutableMapOf() }
                    byEvent[req.as_user] = mutableMapOf<String, String>().apply { putAll(req.responses) }
                    responsesStore[req.event] = byEvent

                    writeJsonFile(RESPONSES_FILE, responsesStore)
                    json(mapOf("ok" to true, "impersonated" to req.as_user))
                }
                else -> notFound()
            }


            Method.PUT -> when {
                uri == "/api/users" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )

                    val body = readPostJson(session)
                    if (body.isBlank()) return err("empty body")
                    val u = try {
                        gson.fromJson(body, UserUpdateReq::class.java)
                    } catch (_: Exception) {
                        null
                    }
                        ?: return err("invalid json")
                    if (u.name.isBlank()) return err("name required")

                    val idx = people.indexOfFirst { it.name == u.name }
                    if (idx < 0) return err("Not found", Response.Status.NOT_FOUND)
                    val p = people[idx]
                    if (u.phone != null) p.phone = u.phone
                    if (u.address != null) p.address = u.address
                    people[idx] = p
                    writeJsonFile(DATA_FILE, people)
                    return json(people)
                }

                uri.startsWith("/api/temp_events/") -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )

                    val id = uri.removePrefix("/api/temp_events/").trim()
                    val body = readPostJson(session)
                    val root = gson.fromJson(body, Map::class.java) as Map<String, Any?>

                    val file = File(context.filesDir, TEMP_EVENTS_FILE)
                    val list: MutableList<MutableMap<String, Any?>> =
                        if (file.exists()) gson.fromJson(
                            file.readText(),
                            List::class.java
                        ) as MutableList<MutableMap<String, Any?>>
                        else mutableListOf()

                    val idx = list.indexOfFirst { it["id"] == id }
                    if (idx == -1) return notFound()

                    val ev = list[idx]
                    ev["name"] = root["name"] ?: ""
                    ev["date"] = root["date"] ?: ""
                    ev["time"] = root["time"] ?: ""
                    ev["location"] = root["location"] ?: ""
                    ev["host"] = root["host"] ?: ""
                    ev["notes"] = root["notes"] ?: ""
                    ev["participants"] = root["participants"] ?: emptyList<Any>()
                    ev["questions"] = root["questions"] ?: emptyList<Any>()
                    ev["temp_questions"] = root["temp_questions"] ?: emptyList<Any>()

                    list[idx] = ev
                    file.writeText(gson.toJson(list))
                    return json(ev)
                }

                else -> notFound()
            }

            Method.DELETE -> when {
                uri == "/api/questions" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )
                    val q = session.parameters["text"]?.firstOrNull()?.trim().orEmpty()
                    if (q.isBlank()) return err("question required")
                    questionsStore.remove(q)
                    writeJsonFile(QUESTIONS_FILE, questionsStore)
                    return json(questionsStore)
                }

                uri == "/api/users" -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )
                    val name = session.parameters["name"]?.firstOrNull()?.trim().orEmpty()
                    if (name.isBlank()) return err("name required")
                    people.removeAll { it.name == name }
                    writeJsonFile(DATA_FILE, people)
                    json(people)
                }

                uri.startsWith("/api/temp_events/") -> {
                    val me = currentUser(session) ?: return unauthorized()
                    if (me.second.role != "admin") return err(
                        "Unauthorized",
                        Response.Status.FORBIDDEN
                    )

                    val id = uri.removePrefix("/api/temp_events/").trim()
                    val file = File(context.filesDir, TEMP_EVENTS_FILE)
                    val list: MutableList<MutableMap<String, Any?>> =
                        if (file.exists()) gson.fromJson(
                            file.readText(),
                            List::class.java
                        ) as MutableList<MutableMap<String, Any?>>
                        else mutableListOf()

                    val idx = list.indexOfFirst { it["id"] == id }
                    if (idx == -1) return notFound()

                    val removed = list.removeAt(idx)
                    file.writeText(gson.toJson(list))
                    return json(removed)
                }

                else -> notFound()
            }

            else -> notFound()
        }
    }

    // Convenience wrappers
    fun startServer() = start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    fun stopServer() = stop()
}
