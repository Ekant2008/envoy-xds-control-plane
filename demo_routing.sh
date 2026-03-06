#!/bin/bash
# Quick demo of dynamic routing functionality
# This script demonstrates the new API endpoints without running the full test suite

set -e

echo "=== Dynamic Routing Quick Demo ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

XDS_URL="http://localhost:18001"

echo -e "${BLUE}Make sure the xDS Control Plane is running:${NC}"
echo "  mvn exec:java -Dexec.mainClass=com.one211.xds.XdsControlPlaneApplication"
echo ""
echo -e "${BLUE}Or run it directly:${NC}"
echo "  java -jar target/xds-control-plane-1.0.0.jar"
echo ""
echo -e "${YELLOW}Press Enter to continue with demo...${NC}"
read

# Get JWT token
echo -e "${YELLOW}[1/5]${NC} Getting JWT token..."
TOKEN=$(curl -s -X POST ${XDS_URL}/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo -e "${RED}✗ Failed to get JWT token${NC}"
    exit 1
fi

echo -e "${GREEN}✓${NC} JWT token obtained: ${TOKEN:0:20}..."
echo ""

# Example 1: Add exact domain route
echo -e "${YELLOW}[2/5]${NC} Example 1: Add exact domain route for controller3"
echo -e "${BLUE}Request:${NC}"
echo "POST /api/routes"
echo '{
  "domain": "controller3.one211.com",
  "cluster": "sql_controller3_http",
  "prefix": "/",
  "priority": 10
}'

RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller3.one211.com",
    "cluster": "sql_controller3_http",
    "prefix": "/",
    "priority": 10
  }')

echo -e "${BLUE}Response:${NC}"
echo "$RESPONSE" | jq '.'
echo ""

# Example 2: Add wildcard route
echo -e "${YELLOW}[3/5]${NC} Example 2: Add wildcard route for all subdomains"
echo -e "${BLUE}Request:${NC}"
echo "POST /api/routes"
echo '{
  "domain": "*.one211.com",
  "cluster": "sql_controller_lb_http",
  "prefix": "/",
  "priority": 1000
}'

RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 1000
  }')

echo -e "${BLUE}Response:${NC}"
echo "$RESPONSE" | jq '.'
echo ""

# Example 3: Add pattern route
echo -e "${YELLOW}[4/5]${NC} Example 3: Add pattern route for controller*"
echo -e "${BLUE}Request:${NC}"
echo "POST /api/routes"
echo '{
  "domain": "controller*.one211.com",
  "cluster": "sql_controller_lb_http",
  "prefix": "/",
  "priority": 50
}'

RESPONSE=$(curl -s -X POST ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "controller*.one211.com",
    "cluster": "sql_controller_lb_http",
    "prefix": "/",
    "priority": 50
  }')

echo -e "${BLUE}Response:${NC}"
echo "$RESPONSE" | jq '.'
echo ""

# Example 4: List all routes
echo -e "${YELLOW}[5/5]${NC} Example 4: List all routes"
echo -e "${BLUE}Request:${NC}"
echo "GET /api/routes"

RESPONSE=$(curl -s -X GET ${XDS_URL}/api/routes \
  -H "Authorization: Bearer $TOKEN")

echo -e "${BLUE}Response:${NC}"
echo "$RESPONSE" | jq '.'
echo ""

# Summary
echo -e "${BLUE}=== Demo Complete ===${NC}"
echo ""
echo "You now have the following routes configured:"
echo "$RESPONSE" | jq -r '.routes[] | "  - \(.domain) -> \(.cluster) (priority: \(.priority))'"
echo ""
echo -e "${BLUE}Route Matching Priority:${NC}"
echo "  1. Exact domains (priority 10)    - controller3.one211.com"
echo "  2. Pattern domains (priority 50)   - controller*.one211.com"
echo "  3. Wildcard domains (priority 1000) - *.one211.com"
echo ""
echo -e "${BLUE}Examples of routing:${NC}"
echo "  controller3.one211.com  -> Exact match (priority 10)"
echo "  controller4.one211.com  -> Pattern match (priority 50)"
echo "  sony.one211.com        -> Wildcard match (priority 1000)"
echo ""
echo -e "${GREEN}✓${NC} Demo completed successfully!"
echo ""
echo -e "${BLUE}Next steps:${NC}"
echo "  1. Add clusters and endpoints for your services"
echo "  2. Configure Envoy to connect to this xDS control plane"
echo "  3. Test routing with curl: curl -H \"Host: controller3.one211.com\" http://localhost/"
echo ""
echo "For more information, see DYNAMIC_ROUTING_README.md"
