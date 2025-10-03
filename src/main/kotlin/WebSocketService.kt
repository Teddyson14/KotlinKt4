package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class WsMessage(
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val text: String,
    val ts: Long = Instant.now().toEpochMilli()
)

object WsManager {
    private val sessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    private val mutex = Mutex()

    suspend fun add(user: String, session: DefaultWebSocketServerSession) {
        mutex.withLock {
            val set = sessions.computeIfAbsent(user) { mutableSetOf() }
            set.add(session)
        }
    }

    suspend fun remove(user: String, session: DefaultWebSocketServerSession) {
        mutex.withLock {
            sessions[user]?.let { set ->
                set.remove(session)
                if (set.isEmpty()) sessions.remove(user)
            }
        }
    }

    fun getUserSessions(user: String) = sessions[user]?.toList() ?: emptyList()

    fun getAllUsers() = sessions.keys.toList()
}

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(15)
        timeout = java.time.Duration.ofSeconds(30)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/chat") {
            val tokenFromQuery = call.request.queryParameters["token"]
            val protocolHeader = call.request.headers["Sec-WebSocket-Protocol"]
            val rawToken = when {
                !tokenFromQuery.isNullOrBlank() -> tokenFromQuery
                !protocolHeader.isNullOrBlank() && protocolHeader.startsWith("Bearer ") -> protocolHeader.removePrefix("Bearer ").trim()
                !protocolHeader.isNullOrBlank() -> protocolHeader.trim()
                else -> null
            }
            val username = try {
                if (rawToken != null) {
                    val decoded = JwtConfig.verifier.verify(rawToken)
                    decoded.getClaim("username").asString() ?: "anonymous"
                } else {
                    "anonymous-${this.hashCode()}"
                }
            } catch (t: Throwable) {
                "anonymous-${this.hashCode()}"
            }

            WsManager.add(username, this)

            broadcastSystem("${username} присоединился", exclude = listOf(this))

            try {
                outgoing.send(Frame.Text(json.encodeToString(WsMessage(
                    type = "system",
                    text = "Добро пожаловать в чат, $username",
                    from = "server"
                ))))

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val incomingMsg = try {
                            json.decodeFromString<WsMessage>(text)
                        } catch (e: Exception) {
                            WsMessage(type = "chat", from = username, text = text)
                        }

                        when (incomingMsg.type) {
                            "chat" -> {
                                broadcastChat(incomingMsg.copy(from = username))
                            }
                            "private" -> {
                                incomingMsg.to?.let { target ->
                                    sendToUser(target, incomingMsg.copy(from = username))
                                    outgoing.send(Frame.Text(json.encodeToString(incomingMsg.copy(from = username, to = target))))
                                } ?: run {
                                    outgoing.send(Frame.Text(json.encodeToString(WsMessage(type="system", text="Поле 'to' обязательно для private сообщений."))))
                                }
                            }
                            "notification" -> {
                                outgoing.send(Frame.Text(json.encodeToString(WsMessage(type="system", text="Notification type is server-only"))))
                            }
                            else -> {
                                broadcastChat(incomingMsg.copy(from = username))
                            }
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
            } catch (t: Throwable) {
                val msg = "Произошла ошибка: ${t.message}"
                outgoing.send(Frame.Text(json.encodeToString(WsMessage(type = "system", text = msg))))
            } finally {
                WsManager.remove(username, this)
                broadcastSystem("${username} покинул чат")
            }
        }

        post("/notify") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.getClaim("role", String::class)
            val username = principal?.getClaim("username", String::class)

            if (role != "admin") {
                call.respond(HttpStatusCode.Forbidden, "Access denied: only admin can send notifications")
                return@post
            }

            val notifyRequest = try {
                call.receive<WsMessage>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid body: expected WsMessage JSON")
                return@post
            }

            if (notifyRequest.to != null) {
                val sent = sendToUser(
                    notifyRequest.to,
                    notifyRequest.copy(type = "notification", from = "server")
                )
                if (sent) {
                    call.respondText("Notification sent to ${notifyRequest.to} by admin $username")
                } else {
                    call.respond(HttpStatusCode.NotFound, "User ${notifyRequest.to} not connected")
                }
            } else {
                broadcastChat(
                    notifyRequest.copy(type = "notification", from = "server")
                )
                call.respondText("Broadcast notification sent by admin $username")
            }
        }
    }
}

suspend fun broadcastChat(message: WsMessage) {
    val jsonText = json.encodeToString(message)
    val allUsers = WsManager.getAllUsers()
    for (u in allUsers) {
        WsManager.getUserSessions(u).forEach { session ->
            try {
                session.outgoing.send(Frame.Text(jsonText))
            } catch (t: Throwable) { /* ignore individual send failures */ }
        }
    }
}

suspend fun broadcastSystem(text: String, exclude: List<DefaultWebSocketServerSession> = emptyList()) {
    val msg = WsMessage(type = "system", from = "server", text = text)
    val jsonText = json.encodeToString(msg)
    val allUsers = WsManager.getAllUsers()
    for (u in allUsers) {
        WsManager.getUserSessions(u).forEach { session ->
            if (exclude.contains(session)) return@forEach
            try {
                session.outgoing.send(Frame.Text(jsonText))
            } catch (t: Throwable) { /* ignore */ }
        }
    }
}

/**
 * Send message/notification to specific user.
 * Returns true if at least one session received the message.
 */
suspend fun sendToUser(user: String, message: WsMessage): Boolean {
    val sessions = WsManager.getUserSessions(user)
    if (sessions.isEmpty()) return false
    val jsonText = json.encodeToString(message)
    var sent = false
    for (session in sessions) {
        try {
            session.outgoing.send(Frame.Text(jsonText))
            sent = true
        } catch (t: Throwable) { /* ignore */ }
    }
    return sent
}
