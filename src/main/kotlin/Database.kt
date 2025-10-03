package com.example

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils

object Users : Table() {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex()
    val password = varchar("password", 64)
    val role = varchar("role", 10)
    override val primaryKey = PrimaryKey(id)
}

fun initDatabase() {
    Database.connect(
        url = "jdbc:h2:./data/mydb;DB_CLOSE_DELAY=-1", // база хранится в файле ./data/mydb
        driver = "org.h2.Driver",
        user = "sa",
        password = ""
    )

    transaction {
        SchemaUtils.create(Users)
    }
}
