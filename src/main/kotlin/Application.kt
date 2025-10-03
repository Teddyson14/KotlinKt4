package com.example

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8081

    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    initDatabase()

    install(ContentNegotiation) {
        json()
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JwtConfig.verifier)
            validate { credential ->
                if (credential.payload.getClaim("username").asString().isNotEmpty())
                    JWTPrincipal(credential.payload)
                else null
            }
        }
    }

    // Роутинг
    configureRouting()
    // WebSocket
    configureWebSockets()
}
