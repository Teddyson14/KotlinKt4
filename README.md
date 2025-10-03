# Ktor Chat App

## Описание
Простой Ktor сервер с:
- JWT аутентификацией
- Ролями (admin, user)
- WebSocket чат
- Swagger документация
- H2
- Docker

## Запуск через Docker
docker-compose build
docker-compose up

## JWT аутентификация
- Генерация токена через JwtConfig.generateToken(username, role)
- Регистрация `/register`
- Защищенные маршруты `/admin` и `/user`

## Swagger
Доступно через `/swagger`

## WebSocket
Подключение к чату: `ws://localhost:8081/chat`
Уведомления `s://localhost:8081/notify`