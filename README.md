# Gateway Service

API Gateway para la red de microservicios. Actúa como punto de entrada único, validando los JWT emitidos por `user-service` antes de enrutar las peticiones a los servicios internos.

## Arquitectura

```
Client → Gateway (8090) → user-service (8080)
                        → budget-service (8083)
                        → payment-service (8081)  [futuro]
                        → agenda-service (8082)   [futuro]
```

## Requisitos

- Java 21
- Maven 3.9+
- La misma `JWT_SECRET_KEY` configurada en `user-service`

## Configuración

Copia `.env.example` a `.env` y ajusta los valores:

```bash
cp .env.example .env
```

**Importante:** `JWT_SECRET_KEY` debe ser idéntica a la de `user-service` para que la validación de tokens funcione.

## Ejecución local

```bash
./mvnw spring-boot:run
```

El gateway escuchará en el puerto `8090`.

## Docker

```bash
docker compose up --build
```

Dentro de Docker el gateway llega a `user-service` por su nombre en la red `microservices-net` (`http://user-service:8080`). No uses el `USER_SERVICE_URL` del `.env` para eso (apunta a `localhost`, que en un contenedor es el propio gateway): para cambiar la URL en Docker define `DOCKER_USER_SERVICE_URL`.

## Rutas configuradas

| Servicio        | Ruta              | Estado     |
|-----------------|-------------------|------------|
| user-service    | `/auth/**`, `/api/user/**`, `/api/roles/**` | Activo |
| budget-service  | `/api/projects/**`, `/api/budgets/**` | Activo (`BUDGET_SERVICE_URL`, en Docker `http://budget-service:8083`) |
| payment-service | `/api/payments/**` | Pendiente |
| agenda-service  | `/api/agenda/**`   | Pendiente |

## Seguridad

- Todas las rutas requieren un JWT válido excepto las definidas como `open-endpoints` (por defecto `/auth/**`).
- El gateway valida la firma y expiración del token y rechaza los refresh tokens como credencial de acceso.
- Se propagan los headers `X-User-Email` y `X-User-Id` a los servicios downstream. Si el cliente los envía, se descartan: solo pueden venir de un JWT validado.
- Se propaga `X-Client-Ip` con la IP real del cliente (usada por el rate limiting de `user-service`). Si el gateway está detrás de un reverse proxy, esa IP será la del proxy.
- CORS solo admite los orígenes de `CORS_ALLOWED_ORIGINS` (separados por comas).

## Resiliencia

Si `user-service` cae o se atasca, el gateway responde rápido con un error controlado en lugar de acumular peticiones colgadas.

| Mecanismo | Comportamiento |
|---|---|
| Timeout de conexión | 2 s (`GATEWAY_CONNECT_TIMEOUT_MS`) |
| Timeout de respuesta | 10 s (`GATEWAY_RESPONSE_TIMEOUT`, p. ej. `15s`) |
| Reintentos | Solo peticiones `GET` y solo si falla la conexión (hasta 2 reintentos con espera creciente). **Nunca** `POST`/`PUT`/`DELETE`, ni errores 5xx, ni timeouts |
| Circuit breaker | Evalúa las últimas 10 llamadas; con ≥ 5 llamadas y ≥ 50 % de fallos se abre 10 s y responde sin llamar a `user-service`, luego prueba con 3 llamadas |
| Límite por llamada | 12 s (debe seguir por encima del timeout de respuesta) |

Cuando el servicio no está disponible, el circuit breaker o un timeout devuelven `503`:

```json
{ "message": "user-service is temporarily unavailable. Please try again in a few moments.", "data": null }
```

Un `500` que responde el propio `user-service` se reenvía tal cual (no cuenta como indisponibilidad). Los valores del circuit breaker están en `application.resilience.circuit-breaker.*` de `application.yml`.

## Añadir un nuevo servicio

1. Añadir la ruta en `application.yml` bajo `spring.cloud.gateway.routes`, con los filtros `CircuitBreaker` y `Retry` de la ruta de `user-service` (cambiando `name` y `fallbackUri`)
2. Añadir el endpoint `/fallback/<servicio>` en `FallbackController`
3. Configurar la variable de entorno con la URL del servicio (y su `DOCKER_*` en `docker-compose.yml`)
4. Si el servicio tiene endpoints públicos, añadirlos a `application.security.open-endpoints`
