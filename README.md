# Gateway Service

API Gateway para la red de microservicios. Actúa como punto de entrada único, validando los JWT emitidos por `user-service` antes de enrutar las peticiones a los servicios internos.

## Arquitectura

```
Client → Gateway (8090) → user-service (8080)
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

## Rutas configuradas

| Servicio        | Ruta              | Estado     |
|-----------------|-------------------|------------|
| user-service    | `/auth/**`, `/api/user/**`, `/api/roles/**` | Activo |
| payment-service | `/api/payments/**` | Pendiente |
| agenda-service  | `/api/agenda/**`   | Pendiente |

## Seguridad

- Todas las rutas requieren un JWT válido excepto las definidas como `open-endpoints` (por defecto `/auth/**`).
- El gateway valida la firma y expiración del token y rechaza los refresh tokens como credencial de acceso.
- Se propagan los headers `X-User-Email` y `X-User-Id` a los servicios downstream. Si el cliente los envía, se descartan: solo pueden venir de un JWT validado.
- Se propaga `X-Client-Ip` con la IP real del cliente (usada por el rate limiting de `user-service`). Si el gateway está detrás de un reverse proxy, esa IP será la del proxy.
- CORS solo admite los orígenes de `CORS_ALLOWED_ORIGINS` (separados por comas).

## Añadir un nuevo servicio

1. Añadir la ruta en `application.yml` bajo `spring.cloud.gateway.routes`
2. Configurar la variable de entorno con la URL del servicio
3. Si el servicio tiene endpoints públicos, añadirlos a `application.security.open-endpoints`
