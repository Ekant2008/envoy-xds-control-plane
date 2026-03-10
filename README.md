# xDS Control Plane

Dynamic xDS Control Plane for Envoy proxy — add controllers and services without restarting Envoy.

## Features

- Dynamic controller registration with zero downtime
- Dynamic routing with exact domain, pattern, and wildcard support
- Priority-based route matching
- HTTP management API for runtime configuration changes
- JWT-based authentication with Role-Based Access Control (RBAC)
- Arrow Flight SQL passthrough via Envoy TCP proxy

## Ports

| Service | Port |
|---------|------|
| gRPC (xDS API) | 18000 |
| HTTP Management API | 18001 |
| Flight SQL (controllers) | 59307 |

## Quick Start

```bash
# Build
mvn clean package

# Run
mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication"

# Run with authentication disabled (development only)
DISABLE_AUTH=true mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication"
```

```bash
# 1. Get a JWT token
TOKEN=$(curl -s -X POST http://localhost:18001/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

# 2. Health check (no auth required)
curl http://localhost:18001/health
```

## HTTP API

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/health` | GET | No | Health check |
| `/login` | POST | No | Authenticate and get JWT token |
| `/reload` | POST | Yes | Reload configuration |
| `/api/clusters` | POST | Yes | Add a new cluster |
| `/api/endpoints` | POST | Yes | Update cluster endpoints |
| `/api/routes` | POST | Yes | Add a dynamic route |
| `/api/routes` | GET | Yes | List all dynamic routes |
| `/api/routes/{domain}` | DELETE | Yes | Delete a route |
| `/api/routes/template/{name}` | POST | Yes | Add route from template |
| `/api/controllers/register` | POST | Yes | Register a new controller |
| `/api/controllers/deregister` | DELETE | Yes | Deregister a controller |

## Dynamic Controller Registration

Register controllers at runtime with no code or config changes required.

```bash
curl -X POST http://localhost:18001/api/controllers/register \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "domain": "controller3.one211.com",
    "host": "controller3",
    "httpPort": 9008,
    "flightPort": 59308
  }'
```

**Request fields:**
- `domain` (required): Domain for this controller
- `host` (required): Container/service hostname
- `httpPort` (optional): HTTP port (default: 9006)
- `flightPort` (optional): Arrow Flight port (default: 59307)

```bash
# Deregister
curl -X DELETE http://localhost:18001/api/controllers/deregister \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"domain": "controller3.one211.com", "host": "controller3"}'
```

## Dynamic Routing

### Route Matching Priority

Routes are matched in order (lowest number = highest priority):

1. **Exact domain** (priority 1–20) — `controller3.one211.com`
2. **Pattern** (priority 50–99) — `controller*.one211.com`
3. **Template** (priority 100–999)
4. **Wildcard** (priority 1000+) — `*.one211.com`
5. **Frontend catch-all** — last resort

### Route Management

```bash
# Add exact route
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domain": "controller3.one211.com", "cluster": "sql_controller3_http", "prefix": "/", "priority": 10}'

# Add wildcard route
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domain": "*.one211.com", "cluster": "sql_controller_lb_http", "prefix": "/", "priority": 1000}'

# List routes
curl -X GET http://localhost:18001/api/routes -H "Authorization: Bearer $TOKEN"

# Delete route (URL-encode * as %2A)
curl -X DELETE "http://localhost:18001/api/routes/%2A.one211.com" -H "Authorization: Bearer $TOKEN"
```

### Route Templates

```bash
curl -X POST http://localhost:18001/api/routes/template/controller \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cluster_override": "custom_lb_cluster"}'
```

**Available templates:**
- `controller`: `controller*.one211.com` → `sql_controller_lb_http` (priority 50)
- `wildcard`: `*.one211.com` → `sql_controller_lb_http` (priority 1000)
- `xyz_pattern`: `xyz_*.one211.com` → `sql_controller_lb_http` (priority 100)

## Authentication

### Default Users

| Username | Password | Role |
|----------|----------|------|
| `admin` | `admin123` | ADMIN |
| `operator` | `operator123` | OPERATOR |
| `viewer` | `viewer123` | VIEWER |
| `service-account` | `service-secret` | SERVICE |

> **Warning:** Change default credentials before deploying to production.

### Roles

| Role | Permissions |
|------|-------------|
| ADMIN | Full access including user management |
| OPERATOR | reload, add-cluster, update-endpoints, view-logs |
| VIEWER | view-logs, health-check |
| SERVICE | add-cluster, update-endpoints |

```bash
# Get token
curl -X POST http://localhost:18001/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

## Configuration

Edit `src/main/resources/application.conf`:

| Key | Default | Description |
|-----|---------|-------------|
| `server.grpc.port` | 18000 | gRPC xDS port |
| `server.http.port` | 18001 | HTTP management port |
| `xds.flight-ports.controllers` | 59307 | Arrow Flight SQL port for controllers |
| `xds.flight-ports.ollylake` | — | Arrow Flight SQL port for OllyLake |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `JWT_SECRET_KEY` | Secret key for JWT signing (256-bit) |
| `DEFAULT_ADMIN_PASSWORD` | Default admin password |
| `DISABLE_AUTH` | Set `true` to disable auth (dev only) |

## Architecture

```
  Client Request
  controller3.one211.com
         │
         ▼
  Envoy Gateway
  ├── HTTPS :443 → route matching (exact > pattern > wildcard)
  └── TCP :59307  → controller_flight_cluster (Arrow Flight SQL)
         │
         ▼
  xDS Control Plane :18000 (gRPC) / :18001 (HTTP)
  └── Controller Registry (dynamic registration via API)
         │
    ┌────┴────┐
    ▼         ▼
  Controller1  Controller2  ... (registered dynamically)
  :9006 HTTP   :9007 HTTP
  :59307 Flight :59307 Flight
```

## Running Tests

```bash
mvn test
```

## Docker

```bash
# Build
docker build -t one211/xds-control-plane .

# Run
docker run -p 18000:18000 -p 18001:18001 one211/xds-control-plane

# With custom secrets
docker run -p 18000:18000 -p 18001:18001 \
  -e JWT_SECRET_KEY="your-secure-256-bit-secret-key" \
  -e DEFAULT_ADMIN_PASSWORD="your-secure-password" \
  one211/xds-control-plane
```

## Dependencies

- Envoy Control Plane Java API v1.0.49
- gRPC v1.79.0
- JJWT v0.12.5
- BouncyCastle v1.78
- JUnit 5 / Mockito 5
