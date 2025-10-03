package com.example

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import io.ktor.server.plugins.swagger.*

fun Application.configureRouting() {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml") {}

        get("/") {
            call.respondText("Сервер Ktor работает! Добро пожаловать!")
        }

        post("/register") {
            val request = call.receive<RegisterRequest>()

            val exists = transaction {
                Users.select { Users.username eq request.username }.count() > 0
            }

            if (exists) {
                call.respondText("Пользователь с таким именем уже существует")
                return@post
            }

            transaction {
                val hashedPassword = BCrypt.hashpw(request.password, BCrypt.gensalt())
                Users.insert {
                    it[username] = request.username
                    it[password] = hashedPassword
                    it[role] = if (request.username == "admin") {
                        "admin"
                    } else {
                        request.role ?: "user"
                    }
                }
            }

            val token = JwtConfig.generateToken(request.username, request.role ?: "user")

            call.respond(AuthResponse(token))
        }

        authenticate("auth-jwt") {
            get("/user") {
                val principal = call.principal<JWTPrincipal>()
                val username = principal!!.getClaim("username", String::class)
                val role = principal.getClaim("role", String::class)
                call.respondText("Hello, $username! Ваша роль: $role")
            }

            get("/admin") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal!!.getClaim("role", String::class)
                if (role != "admin") {
                    call.respondText("Access denied")
                } else {
                    call.respondText("Welcome, admin!")
                }
            }
        }
    }
}
