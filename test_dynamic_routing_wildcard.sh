#!/bin/bash
# Test script for dynamic host-based routing with wildcard support
# Demonstrates:
# 1. Adding routes with exact domain patterns
# 2. Adding routes with wildcard subdomains
# 3. Adding routes with pattern matching
# 4. Using route templates
# 5. Listing and deleting routes

set -e

echo "=== Dynamic Routing with Wildcard Support Test ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

XDS_URL="http://localhost:18001"
ENVOY_URL="http://localhost"

# Step 1: Get JWT token
echo -e "${YELLOW}[1/8]${NC} Getting JWT token..."
TOKEN=$(curl -s -X POST ${XDS_URL}/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo -e "${RED}✗ Failed to get JWT token${NC}"
    exit 1
fi

echo -e "${GREEN}✓${NC} JWT token obtained"
echo ""

# Step 2: Add cluster for controller3
echo -e "${YELLOW}[2/8]${NC} Adding cluster sql_controller3_http with endpoint sql-controller3:9008..."
RESPONSE=$(curl -s -X POST ${XDS_URL}/api/clusters \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cluster_name": "sql_controller3_http",
    "endpoints": [{"host": "sql-controller3", "port": 9008}]
  }')

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Cluster sql_controller3_http added successfully"
else
    echo -e "${RED}✗ Failed to add cluster sql_controller3_http${NC}"
    exit 1
fi
echo ""

# Step 3: Add exact domain route (controller3.one211.com)
echo -e "${YELLOW}[3/8]${NC} Adding route controller3.one211.com -> sql_controller3_http (priority 10)..."
RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller3.one211.com",
    "cluster": "sql_controller3_http",
    "prefix": "/",
    "priority": 10
  }')

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Route controller3.one211.com added successfully"
else
    echo -e "${RED}✗ Failed to add route controller3.one211.com${NC}"
    exit 1
fi
echo ""

# Step 4: Add wildcard route (*.one211.com)
echo -e "${YELLOW}[4/8]${NC} Adding wildcard route *.one211.com -> sql_controller_lb_http (priority 1000)..."
RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 1000
  }')

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Wildcard route *.one211.com added successfully"
else
    echo -e "${RED}✗ Failed to add wildcard route *.one211.com${NC}"
    exit 1
fi
echo ""

# Step 5: Add pattern route (controller*.one211.com)
echo -e "${YELLOW}[5/8]${NC} Adding pattern route controller*.one211.com -> sql_controller_lb_http (priority 50)..."
RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 50
  }')

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Pattern route controller*.one211.com added successfully"
else
    echo -e "${RED}✗ Failed to add pattern route controller*.one211.com${NC}"
    exit 1
fi
echo ""

# Step 6: List all routes
echo -e "${YELLOW}[6/8]${NC} Listing all routes..."
RESPONSE=$(curl -s -X GET ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN")

echo "Response:"
echo "$RESPONSE" | jq '.'

echo -e "${GREEN}✓${NC} Routes listed successfully"
echo ""

# Step 7: Add route from template (xyz_ pattern)
echo -e "${YELLOW}[7/8]${NC} Adding route from template 'xyz_pattern'..."
RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes/template/xyz_pattern \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cluster_override": "sql_controller_lb_http"}')

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Route created from template xyz_pattern successfully"
else
    echo -e "${RED}✗ Failed to create route from template xyz_pattern${NC}"
    exit 1
fi
echo ""

# Step 8: Delete wildcard route
echo -e "${YELLOW}[8/8]${NC} Deleting wildcard route *.one211.com..."
RESPONSE=$(curl -s -X DELETE "${XDS_URL}/api/routes/%2A.one211.com" \
  -H "Authorization: Bearer $TOKEN")

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "ok"; then
    echo -e "${GREEN}✓${NC} Wildcard route deleted successfully"
else
    echo -e "${RED}✗ Failed to delete wildcard route${NC}"
    exit 1
fi
echo ""

# Final list of routes
echo -e "${BLUE}=== Final Routes ===${NC}"
RESPONSE=$(curl -s -X GET ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN")

echo "$RESPONSE" | jq '.'
echo ""

# Summary
echo "=== Summary ==="
echo -e "${GREEN}✓${NC} Cluster sql_controller3_http created with endpoint sql-controller3:9008"
echo -e "${GREEN}✓${NC} Route controller3.one211.com -> sql_controller3_http (priority 10) added"
echo -e "${GREEN}✓${NC} Wildcard route *.one211.com -> sql_controller_lb_http (priority 1000) added and deleted"
echo -e "${GREEN}✓${NC} Pattern route controller*.one211.com -> sql_controller_lb_http (priority 50) added"
echo -e "${GREEN}✓${NC} Route created from template xyz_pattern"
echo -e "${GREEN}✓${NC} All route management operations completed"
echo ""
echo "=== Route Matching Priority ==="
echo "1. Exact domains (priority 10)    - Most specific, matched first"
echo "2. Pattern domains (priority 50)   - controller*.one211.com"
echo "3. Template routes (priority 100)  - xyz_*.one211.com"
echo "4. Static routes                   - backend.one211.com, controller1.one211.com, etc."
echo "5. Frontend catch-all              - Last resort"
echo ""
echo "=== Examples of Route Matching ==="
echo -e "${BLUE}controller3.one211.com${NC}        -> Matches exact route (priority 10)"
echo -e "${BLUE}controller4.one211.com${NC}        -> Matches pattern route controller* (priority 50)"
echo -e "${BLUE}xyz_value.one211.com${NC}         -> Matches template route xyz_* (priority 100)"
echo -e "${BLUE}backend.one211.com${NC}            -> Matches static route"
echo -e "${BLUE}www.one211.com${NC}               -> Matches frontend catch-all"
echo ""
echo -e "${GREEN}All tests passed successfully!${NC}"
echo ""
echo "Note: To test actual routing with Envoy, ensure:"
echo "  1. xDS control plane is running on port 18000/18001"
echo "  2. Envoy is configured to connect to the control plane"
echo "  3. Backend services are running"
