# Dynamic Routing with Wildcard Support

This document describes the new dynamic routing functionality added to the xDS Control Plane, enabling zero-downtime addition of controllers and services using wildcard domain patterns.

## Overview

The xDS Control Plane now supports:
- ✅ **Exact domain routing** (e.g., `controller3.one211.com`)
- ✅ **Wildcard subdomain routing** (e.g., `*.one211.com`)
- ✅ **Pattern-based routing** (e.g., `controller*.one211.com`)
- ✅ **Route templates** for common patterns
- ✅ **Priority-based route matching**
- ✅ **Full CRUD operations** for routes

## API Endpoints

### 1. Add Route

**Endpoint:** `POST /api/routes`

**Request Body:**
```json
{
  "domain": "*.one211.com",
  "cluster": "sql_controller_lb_http",
  "prefix": "/",
  "priority": 1000
}
```

**Parameters:**
- `domain` (required): Domain pattern. Supports:
  - Exact: `controller3.one211.com`
  - Wildcard: `*.one211.com`
  - Pattern: `controller*.one211.com`, `xyz_*.one211.com`
- `cluster` (required): Target cluster name
- `prefix` (optional): Route prefix (default: `/`)
- `priority` (optional): Matching priority (default: `100`, lower = higher priority)

**Response:**
```json
{
  "status": "ok",
  "message": "Route added"
}
```

**Example:**
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

### 2. List Routes

**Endpoint:** `GET /api/routes`

**Response:**
```json
{
  "routes": [
    {
      "domain": "controller3.one211.com",
      "cluster": "sql_controller3_http",
      "prefix": "/",
      "priority": 10
    },
    {
      "domain": "controller*.one211.com",
      "cluster": "sql_controller_lb_http",
      "prefix": "/",
      "priority": 50
    },
    {
      "domain": "*.one211.com",
      "cluster": "sql_controller_lb_http",
      "prefix": "/",
      "priority": 1000
    }
  ],
  "count": 3
}
```

**Example:**
```bash
curl -X GET http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN"
```

### 3. Delete Route

**Endpoint:** `DELETE /api/routes/{domain_pattern}`

**Note:** Domain pattern must be URL-encoded (e.g., `*` becomes `%2A`)

**Response:**
```json
{
  "status": "ok",
  "message": "Route deleted"
}
```

**Example:**
```bash
curl -X DELETE "http://localhost:18001/api/routes/%2A.one211.com" \
  -H "Authorization: Bearer $TOKEN"
```

### 4. Create Route from Template

**Endpoint:** `POST /api/routes/template/{template_name}`

**Request Body (optional):**
```json
{
  "cluster_override": "custom_cluster"
}
```

**Available Templates:**
- `controller`: `controller*.one211.com` → `sql_controller_lb_http` (priority 50)
- `wildcard`: `*.one211.com` → `sql_controller_lb_http` (priority 1000)
- `xyz_pattern`: `xyz_*.one211.com` → `sql_controller_lb_http` (priority 100)

**Response:**
```json
{
  "status": "ok",
  "message": "Route created from template"
}
```

**Example:**
```bash
curl -X POST http://localhost:18001/api/routes/template/xyz_pattern \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cluster_override": "custom_lb_cluster"}'
```

## Route Matching Priority

Routes are matched in the following order (lowest priority number first):

1. **Exact domain routes** (priority 1-20)
   - `controller3.one211.com` → `sql_controller3_http`
   - Most specific, matched first

2. **Pattern routes** (priority 50-99)
   - `controller*.one211.com` → `sql_controller_lb_http`
   - `xyz_*.one211.com` → `custom_cluster`
   - Wildcard patterns within the prefix

3. **Template routes** (priority 100-999)
   - Created from predefined templates
   - Medium specificity

4. **Wildcard routes** (priority 1000+)
   - `*.one211.com` → `sql_controller_lb_http`
   - Least specific, matched last before catch-all

5. **Static routes** (hardcoded in `XdsConfigManager`)
   - `backend.one211.com`, `controller1.one211.com`, etc.
   - Always have high priority

6. **Frontend catch-all** (lowest priority)
   - `frontend.one211.com`, `www.one211.com`, `localhost`, etc.
   - Last resort

## Use Cases

### 1. Adding a New Controller (Exact Domain)

```bash
# Step 1: Add the cluster
curl -X POST http://localhost:18001/api/clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cluster_name": "sql_controller3_http",
    "endpoints": [{"host": "sql-controller3", "port": 9008}]
  }'

# Step 2: Add the route
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller3.one211.com",
    "cluster": "sql_controller3_http",
    "prefix": "/",
    "priority": 10
  }'

# Result: controller3.one211.com → sql-controller3:9008
```

### 2. Adding Multiple Controllers with Pattern

```bash
# Add pattern route for all controllers
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 50
  }'

# Result:
# - controller3.one211.com → sql_controller_lb_http (pattern match)
# - controller4.one211.com → sql_controller_lb_http (pattern match)
# - controller99.one211.com → sql_controller_lb_http (pattern match)
```

### 3. Wildcard Catch-all

```bash
# Add wildcard route
curl -X POST http://localhost:18001/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 1000
  }'

# Result: Matches any subdomain of .one211.com
# - sony.one211.com → sql_controller_lb_http
# - xyz_value.one211.com → sql_controller_lb_http
# - anything.one211.com → sql_controller_lb_http
```

### 4. Using Templates

```bash
# Create route from template
curl -X POST http://localhost:18001/api/routes/template/controller \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cluster_override": "custom_lb_cluster"}'

# Result: controller*.one211.com → custom_lb_cluster (priority 50)
```

## Testing

Run the comprehensive test script:

```bash
chmod +x test_dynamic_routing_wildcard.sh
./test_dynamic_routing_wildcard.sh
```

This script demonstrates:
1. Adding clusters and endpoints
2. Adding exact domain routes
3. Adding wildcard routes
4. Adding pattern routes
5. Listing routes
6. Using templates
7. Deleting routes

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
│  1. Check exact route controller3.one211.com (priority 10) │
│  2. Check pattern route controller*.one211.com (priority 50)│
│  3. Check wildcard route *.one211.com (priority 1000)      │
│  4. Check static routes                                    │
│  5. Check frontend catch-all                                │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              sql_controller3_http Cluster                  │
│              Endpoints: sql-controller3:9008               │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│            SQL Controller 3 Service                        │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Details

### Files Modified

1. **XdsConfigManager.java**
   - Added `dynamicRoutes` map for storing dynamic routes
   - Added `routeTemplates` map for predefined templates
   - Added `addRoute()`, `deleteRoute()`, `listRoutes()`, `addRouteFromTemplate()`
   - Added `isValidDomainPattern()` for validation
   - Added `EnhancedRouteRequest`, `RouteConfig`, `RouteTemplate` classes
   - Modified `generateVirtualHosts()` to include dynamic routes with priority sorting

2. **XdsControlPlaneApplication.java**
   - Added `jsonMapper` for JSON parsing
   - Added `/api/routes` endpoint (POST, GET, DELETE)
   - Added `/api/routes/template/{template_name}` endpoint (POST)

### Priority System

The priority system ensures that:
- More specific routes are matched first
- Exact domains have priority 1-20
- Pattern routes have priority 50-99
- Template routes have priority 100-999
- Wildcard routes have priority 1000+
- Static routes are always matched before dynamic routes
- Frontend catch-all is the last resort

## Best Practices

1. **Use exact domains** when you know the specific controller/service
2. **Use patterns** for related services (e.g., `controller*.one211.com`)
3. **Use wildcards sparingly** as they match broadly
4. **Set appropriate priorities** to control matching order
5. **Test routing** before production deployment
6. **Monitor logs** for routing conflicts
7. **Use templates** for common patterns to reduce configuration errors

## Troubleshooting

### Route Not Matching

1. Check route priority - ensure more specific routes have lower priority numbers
2. Verify the domain pattern matches the request
3. Check logs for routing conflicts
4. List all routes to see the current configuration

### Wildcard Issues

1. Wildcards (`*`) must be URL-encoded in API calls: `%2A`
2. Wildcards match any subdomain, not subdomains + path
3. Use patterns for more controlled matching

### Configuration Not Updating

1. Check that the xDS connection is healthy
2. Verify the control plane is running
3. Check logs for errors
4. Wait for the scheduler interval (default 10 seconds)

## Security

- All route management endpoints require authentication
- JWT tokens are required for all operations
- Domain patterns are validated before adding
- Only admin/operator roles can modify routes

## Next Steps

1. **Persistence**: Add database storage for routes (currently in-memory)
2. **Validation**: Add more robust domain and cluster validation
3. **Versioning**: Add route versioning for rollback capability
4. **Metrics**: Add metrics for route performance and usage
5. **Health Checks**: Add health check validation before route addition
6. **Rate Limiting**: Add rate limiting for API endpoints
