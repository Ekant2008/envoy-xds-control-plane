# xDS Control Plane

Dynamic xDS Control Plane for Envoy proxy that allows adding new executors and controllers without restarting Envoy.

## Features

- Dynamic configuration updates via xDS API
- HTTP management API for runtime configuration changes
- Support for adding clusters and endpoints dynamically
- Dynamic controller registration with zero downtime
- Dynamic routing with wildcard domain patterns
- Configuration file watching for automatic updates
- JWT-based authentication and authorization
- Role-based access control (RBAC)
- Secure token-based API access

## Quick Start

```bash
# Build
mvn clean package

# Run with authentication enabled (default)
mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication"

# Run with custom ports
mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication" \
    -Dexec.args="-Dgrpc.port=18000 -Dhttp.port=18001"

# Run with authentication disabled (development only)
export DISABLE_AUTH=true
mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication"
```

### Quick Authentication Test

```bash
# 1. Get a JWT token
TOKEN=$(curl -s -X POST http://localhost:18001/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

# 2. Use the token to access protected endpoints
curl -X POST http://localhost:18001/api/clusters \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "cluster_name": "test-cluster",
    "endpoints": ["localhost:8080"]
  }'

# 3. Test health endpoint (no auth required)
curl http://localhost:18001/health
```

## HTTP API

| Endpoint | Method | Authentication | Required Permission | Description |
|----------|--------|----------------|-------------------|-------------|
| `/health` | GET | No | - | Health check |
| `/login` | POST | No | - | Authenticate and get JWT token |
| `/reload` | POST | Yes | `reload` | Reload configuration from files |
| `/api/clusters` | POST | Yes | `add-cluster` | Add a new cluster |
| `/api/endpoints` | POST | Yes | `update-endpoints` | Update cluster endpoints |
| `/api/routes` | POST | Yes | `add-cluster` | Add a dynamic route |
| `/api/routes` | GET | Yes | - | List all dynamic routes |
| `/api/routes/{domain}` | DELETE | Yes | `add-cluster` | Delete a route |
| `/api/routes/template/{name}` | POST | Yes | `add-cluster` | Add route from template |
| `/api/controllers/register` | POST | Yes | `add-cluster` | Register a new controller |
| `/api/controllers/deregister` | DELETE | Yes | `add-cluster` | Deregister a controller |

## Dynamic Controller Registration

Register controllers dynamically without changing code or configuration files.

### Register a New Controller

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

**Request Body:**
```json
{
  "domain": "controller3.one211.com",   // Required: The domain for this controller
  "host": "controller3",                // Required: Container name (Kubernetes service name)
  "httpPort": 9008,                    // Optional: HTTP port (default: 9006)
  "flightPort": 59308                  // Optional: Flight port (default: 59307)
}
```

### Deregister a Controller

```bash
curl -X DELETE http://localhost:18001/api/controllers/deregister \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "domain": "controller3.one211.com",
    "host": "controller3"
  }'
```

## Dynamic Routing with Wildcard Support

### Add Exact Domain Route

```bash
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller3.one211.com",
    "cluster": "sql_controller3_http",
    "prefix": "/",
    "priority": 10
  }'
```

### Add Wildcard Route

```bash
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 1000
  }'
```

### Add Pattern Route

```bash
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 50
  }'
```

### Use Route Template

```bash
curl -X POST http://localhost:18001/api/routes/template/controller \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cluster_override": "custom_lb_cluster"}'
```

**Available Templates:**
- `controller`: `controller*.one211.com` → `sql_controller_lb_http` (priority 50)
- `wildcard`: `*.one211.com` → `sql_controller_lb_http` (priority 1000)
- `xyz_pattern`: `xyz_*.one211.com` → `sql_controller_lb_http` (priority 100)

### List All Routes

```bash
curl -X GET http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN"
```

### Delete a Route

```bash
curl -X DELETE "http://localhost:18001/api/routes/%2A.one211.com" \
  -H "Authorization: Bearer $TOKEN"
```

> **Note:** Domain patterns must be URL-encoded (e.g., `*` becomes `%2A`)

## Route Matching Priority

Routes are matched in the following order (lowest priority number first):

1. **Exact domain routes** (priority 1-20) - e.g., `controller3.one211.com`
2. **Pattern routes** (priority 50-99) - e.g., `controller*.one211.com`
3. **Template routes** (priority 100-999)
4. **Wildcard routes** (priority 1000+) - e.g., `*.one211.com`
5. **Static routes** - Always matched before dynamic routes
6. **Frontend catch-all** - Last resort

## Authentication

### Default Users

| Username | Password | Role |
|----------|----------|------|
| `admin` | `admin123` | ADMIN |
| `operator` | `operator123` | OPERATOR |
| `viewer` | `viewer123` | VIEWER |
| `service-account` | `service-secret` | SERVICE |

> **⚠️ Security Warning:** The default credentials are for development only. Change them in production!

### Role Permissions

| Role | Permissions | Description |
|------|-------------|-------------|
| ADMIN | reload, add-cluster, update-endpoints, delete-cluster, manage-users, view-logs | Full administrative access |
| OPERATOR | reload, add-cluster, update-endpoints, view-logs | Operational access (no user management) |
| VIEWER | view-logs, health-check | Read-only access |
| SERVICE | add-cluster, update-endpoints | Service account for automated tasks |

### Getting a JWT Token

```bash
curl -X POST http://localhost:18001/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin123"
  }'
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin",
  "role": "ADMIN",
  "expiresIn": 86400000
}
```

### Using the JWT Token

```bash
curl -X POST http://localhost:18001/api/clusters \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "cluster_name": "my-service",
    "endpoints": ["192.168.1.10:8080", "192.168.1.11:8080"]
  }'
```

## Configuration

### Configuration File

Configure via `src/main/resources/application.conf`:

**Server Configuration:**
- gRPC port: `server.grpc.port` (default: 18000)
- HTTP management port: `server.http.port` (default: 18001)

**xDS Configuration:**
- Gateway ports: `xds.https-gateway-port`, `xds.minio-s3-api-port`
- Arrow Flight SQL ports: `xds.flight-ports.controllers`, `xds.flight-ports.ollylake`
- PostgreSQL ports: `xds.postgres-ports.*`
- Service ports: `xds.services.*`
- Timeouts: `xds.timeouts.*` (api-short, api-standard, api-long, tcp-connect, tcp-idle)
- Scheduler: `xds.scheduler.*` (initial-delay-sec, interval-sec)

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET_KEY` | Secret key for JWT signing (256 bits) | Default dev key |
| `DEFAULT_ADMIN_PASSWORD` | Password for default admin user | `admin123` |
| `DISABLE_AUTH` | Disable authentication (true/false) | `false` |

### Set Custom JWT Secret

```bash
export JWT_SECRET_KEY="your-very-secure-256-bit-secret-key-here"
export DEFAULT_ADMIN_PASSWORD="your-secure-password-here"

mvn exec:java -Dexec.mainClass="com.one211.xds.XdsControlPlaneApplication"
```

## Ports

| Service | Port |
|---------|------|
| gRPC (xDS API) | 18000 |
| HTTP Management API | 18001 |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Client Request                          │
│                  controller3.one211.com                    │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    Envoy Gateway                           │
│  - flight_listener_controllers:59305 → controller_flight_cluster  │
│  - Routes matched by priority (exact > pattern > wildcard) │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              xDS Control Plane                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              Controller Registry                       │  │
│  │  - Dynamic routes via /api/routes                      │  │
│  │  - HTTP Endpoints → sql_controller_lb_http             │  │
│  │  - Flight Endpoints → controller_flight_cluster        │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────┬───────────────────────────────────┘
                          │
        ┌───────────────────┼───────────────────┐
        ↓                   ↓                   ↓
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ Controller1 │    │ Controller2 │    │ Controller3 │
│ :9006 HTTP  │    │ :9007 HTTP  │    │ :9008 HTTP  │
│ :59305 Flight│   │ :59305 Flight│   │ :59308 Flight│
└─────────────┘    └─────────────┘    └─────────────┘
```

## Running Tests

```bash
mvn test
```

## Docker

```bash
# Build image
docker build -t one211/xds-control-plane .

# Run with default authentication
docker run -p 18000:18000 -p 18001:18001 one211/xds-control-plane

# Run with custom JWT secret
docker run -p 18000:18000 -p 18001:18001 \
  -e JWT_SECRET_KEY="your-secure-256-bit-secret-key" \
  -e DEFAULT_ADMIN_PASSWORD="your-secure-password" \
  one211/xds-control-plane

# Run with authentication disabled (development only)
docker run -p 18000:18000 -p 18001:18001 \
  -e DISABLE_AUTH=true \
  one211/xds-control-plane
```

## Dependencies

- Envoy Control Plane Java API v1.0.49
- gRPC v1.79.0
- JJWT (Java JWT) v0.12.5 - JWT token generation and validation
- BouncyCastle v1.78 - Cryptography library
- JUnit 5.10.1
- Mockito 5.11.0

## Security Best Practices

1. **Never commit JWT secrets** to version control
2. **Use environment variables** for sensitive configuration
3. **Rotate secrets** regularly in production
4. **Use HTTPS/TLS** in production environments
5. **Implement rate limiting** on the login endpoint
6. **Use strong passwords** for all users
7. **Monitor authentication logs** for suspicious activity
8. **Consider implementing** refresh tokens for better security

## License

[Add your license here]
