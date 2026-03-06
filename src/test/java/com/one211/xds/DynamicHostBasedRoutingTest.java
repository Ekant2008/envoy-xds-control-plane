package com.one211.xds;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for dynamic host-based routing support in xDS control plane.
 *
 * Acceptance Criteria:
 * 1. Add cluster sony_http with endpoint sony-service:8080
 * 2. Call /api/routes with domain sony.one211.com -> sony_http
 * 3. curl with Host header sony.one211.com routes to sony_http
 */
class DynamicHostBasedRoutingTest {

    private XdsConfigManager configManager;

    @BeforeEach
    void setUp() {
        configManager = new XdsConfigManager();
        // Clear any existing dynamic routes/endpoints
        clearDynamicRoutes();
        clearDynamicEndpoints();
    }

    @Test
    void testAddDynamicClusterWithEndpoints() throws Exception {
        // Given: A new cluster configuration for sony_http with endpoint sony-service:8080
        String clusterConfigJson = """
                {
                    "cluster_name": "sony_http",
                    "endpoints": [{"host": "sony-service", "port": 8080}]
                }
                """;

        // When: The cluster is added
        configManager.addCluster(clusterConfigJson);

        // Then: The cluster should be present in dynamic endpoints
        Map<String, List<XdsConfigManager.EndpointConfig>> dynamicEndpoints = getDynamicEndpoints();
        assertTrue(dynamicEndpoints.containsKey("sony_http"), "sony_http cluster should be added");

        List<XdsConfigManager.EndpointConfig> endpoints = dynamicEndpoints.get("sony_http");
        assertNotNull(endpoints, "Endpoints list should not be null");
        assertEquals(1, endpoints.size(), "Should have 1 endpoint");
        assertEquals("sony-service", endpoints.get(0).getHost());
        assertEquals(8080, endpoints.get(0).getPort());
    }

    @Test
    void testAddDynamicRouteForDomain() throws Exception {
        // Given: A cluster exists (add it first)
        String clusterConfigJson = """
                {
                    "cluster_name": "sony_http",
                    "endpoints": [{"host": "sony-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: A route is added for sony.one211.com -> sony_http
        String routeConfigJson = """
                {
                    "domain": "sony.one211.com",
                    "cluster": "sony_http",
                    "prefix": "/"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // Then: The route should be present in dynamic routes
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        assertTrue(dynamicRoutes.containsKey("sony.one211.com"), "Route for sony.one211.com should be added");

        Object route = dynamicRoutes.get("sony.one211.com");
        assertRouteConfigFields(route, "sony.one211.com", "sony_http", "/");
    }

    @Test
    void testGeneratedSnapshotIncludesDynamicRoute() throws Exception {
        // Given: Add cluster and route
        String clusterConfigJson = """
                {
                    "cluster_name": "sony_http",
                    "endpoints": [{"host": "sony-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        String routeConfigJson = """
                {
                    "domain": "sony.one211.com",
                    "cluster": "sony_http",
                    "prefix": "/"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // When: Generate snapshot
        io.envoyproxy.controlplane.cache.Snapshot snapshot = configManager.generateInitialSnapshot();

        // Then: Routes should include to dynamic route
        List<RouteConfiguration> routes = snapshot.resources(
            io.envoyproxy.controlplane.cache.Resources.ResourceType.ROUTE
        ).values().stream()
            .map(resource -> (RouteConfiguration) resource)
            .toList();

        assertFalse(routes.isEmpty(), "Routes should not be empty");

        // Check if any route configuration contains to dynamic virtual host
        boolean hasDynamicVirtualHost = routes.stream()
            .flatMap(route -> route.getVirtualHostsList().stream())
            .anyMatch(vh -> vh.getDomainsList().contains("sony.one211.com"));

        assertTrue(hasDynamicVirtualHost, "Virtual host for sony.one211.com should exist in generated routes");
    }

    @Test
    void testGeneratedEndpointsIncludesDynamicClusterEndpoint() throws Exception {
        // Given: Add cluster with endpoint
        String clusterConfigJson = """
                {
                    "cluster_name": "sony_http",
                    "endpoints": [{"host": "sony-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);


        // When: Generate snapshot
        io.envoyproxy.controlplane.cache.Snapshot snapshot = configManager.generateInitialSnapshot();

        // Then: Endpoints should include to dynamic cluster endpoint
        List<ClusterLoadAssignment> endpoints = snapshot.resources(
            io.envoyproxy.controlplane.cache.Resources.ResourceType.ENDPOINT
        ).values().stream()
            .map(resource -> (ClusterLoadAssignment) resource)
            .toList();

        assertFalse(endpoints.isEmpty(), "Endpoints should not be empty");

        // Check if sony_http cluster endpoint is present
        boolean hasSonyHttpEndpoint = endpoints.stream()
            .anyMatch(endpoint -> "sony_http".equals(endpoint.getClusterName()));

        assertTrue(hasSonyHttpEndpoint, "Endpoint for sony_http cluster should exist in generated endpoints");
    }

    @Test
    void testUpdateRouteWithoutPrefixDefaultsToRoot() throws Exception {
        // Given: Add cluster first
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: Add route without specifying prefix
        String routeConfigJson = """
                {
                    "domain": "test.example.com",
                    "cluster": "test_cluster"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // Then: Prefix should default to "/"
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        Object route = dynamicRoutes.get("test.example.com");
        String prefix = getRouteConfigField(route, "prefix");
        assertEquals("/", prefix, "Prefix should default to '/'");
    }

    @Test
    void testUpdateRouteWithCustomPrefix() throws Exception {
        // Given: Add cluster first
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: Add route with custom prefix
        String routeConfigJson = """
                {
                    "domain": "test.example.com",
                    "cluster": "test_cluster",
                    "prefix": "/api"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // Then: Prefix should be "/api"
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        Object route = dynamicRoutes.get("test.example.com");
        String prefix = getRouteConfigField(route, "prefix");
        assertEquals("/api", prefix, "Prefix should be '/api'");
    }

    @Test
    void testUpdateRouteWithInvalidPrefixThrowsException() {
        // Given: Add cluster first
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        assertDoesNotThrow(() -> configManager.addCluster(clusterConfigJson));

        // When/Then: Route with invalid prefix should throw exception
        String routeConfigJson = """
                {
                    "domain": "test.example.com",
                    "cluster": "test_cluster",
                    "prefix": "invalid-prefix"
                }
                """;

        assertThrows(java.io.IOException.class, () -> configManager.updateRoute(routeConfigJson),
            "Route with prefix not starting with '/' should throw exception");
    }

    @Test
    void testUpdateRouteWithMissingDomainThrowsException() {
        // When: Add route without domain
        String routeConfigJson = """
                {
                    "cluster": "test_cluster"
                }
                """;

        // Then: Should throw exception
        assertThrows(java.io.IOException.class, () -> configManager.updateRoute(routeConfigJson),
            "Route without domain should throw exception");
    }

    @Test
    void testUpdateRouteWithMissingClusterThrowsException() {
        // When: Add route without cluster
        String routeConfigJson = """
                {
                    "domain": "test.example.com"
                }
                """;

        // Then: Should throw exception
        assertThrows(java.io.IOException.class, () -> configManager.updateRoute(routeConfigJson),
            "Route without cluster should throw exception");
    }

    @Test
    void testMultipleDynamicRoutes() throws Exception {
        // Given: Add cluster
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: Add multiple routes for different domains
        String route1Json = """
                {
                    "domain": "api.example.com",
                    "cluster": "test_cluster",
                    "prefix": "/api"
                }
                """;
        String route2Json = """
                {
                    "domain": "web.example.com",
                    "cluster": "test_cluster",
                    "prefix": "/"
                }
                """;
        String route3Json = """
                {
                    "domain": "admin.example.com",
                    "cluster": "test_cluster",
                    "prefix": "/admin"
                }
                """;

        configManager.updateRoute(route1Json);
        configManager.updateRoute(route2Json);
        configManager.updateRoute(route3Json);

        // Then: All routes should be present
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        assertEquals(3, dynamicRoutes.size(), "Should have 3 dynamic routes");
        assertTrue(dynamicRoutes.containsKey("api.example.com"));
        assertTrue(dynamicRoutes.containsKey("web.example.com"));
        assertTrue(dynamicRoutes.containsKey("admin.example.com"));
    }

    @Test
    void testUpdateExistingRoute() throws Exception {
        // Given: Add cluster and route
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        String routeConfigJson = """
                {
                    "domain": "test.example.com",
                    "cluster": "test_cluster",
                    "prefix": "/"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // When: Update route to point to a different cluster
        String clusterConfigJson2 = """
                {
                    "cluster_name": "test_cluster_2",
                    "endpoints": [{"host": "test-service-2", "port": 9090}]
                }
                """;
        configManager.addCluster(clusterConfigJson2);

        String updatedRouteConfigJson = """
                {
                    "domain": "test.example.com",
                    "cluster": "test_cluster_2",
                    "prefix": "/api"
                }
                """;
        configManager.updateRoute(updatedRouteConfigJson);

        // Then: Route should be updated
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        Object route = dynamicRoutes.get("test.example.com");
        String cluster = getRouteConfigField(route, "cluster");
        String prefix = getRouteConfigField(route, "prefix");
        assertEquals("test_cluster_2", cluster, "Route should be updated to point to test_cluster_2");
        assertEquals("/api", prefix, "Prefix should be updated to /api");
    }

    @Test
    void testRouteDomainWithPort() throws Exception {
        // Given: Add cluster and route
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: Add route for domain with port notation
        String routeConfigJson = """
                {
                    "domain": "test.example.com:8080",
                    "cluster": "test_cluster",
                    "prefix": "/"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // Then: Route should be created correctly
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        assertTrue(dynamicRoutes.containsKey("test.example.com:8080"));
        Object route = dynamicRoutes.get("test.example.com:8080");
        String domain = getRouteConfigField(route, "domain");
        String cluster = getRouteConfigField(route, "cluster");
        assertEquals("test.example.com:8080", domain);
        assertEquals("test_cluster", cluster);
    }

    @Test
    void testDomainIsTrimmed() throws Exception {
        // Given: Add cluster
        String clusterConfigJson = """
                {
                    "cluster_name": "test_cluster",
                    "endpoints": [{"host": "test-service", "port": 8080}]
                }
                """;
        configManager.addCluster(clusterConfigJson);

        // When: Add route with whitespace in domain and cluster (not in prefix)
        String routeConfigJson = """
                {
                    "domain": "  test.example.com  ",
                    "cluster": "  test_cluster  ",
                    "prefix": "/api"
                }
                """;
        configManager.updateRoute(routeConfigJson);

        // Then: Values should be trimmed
        Map<String, Object> dynamicRoutes = getDynamicRoutes();
        assertTrue(dynamicRoutes.containsKey("test.example.com"),
            "Domain should be trimmed (without leading/trailing spaces)");
        Object route = dynamicRoutes.get("test.example.com");
        String cluster = getRouteConfigField(route, "cluster");
        String prefix = getRouteConfigField(route, "prefix");
        assertEquals("test_cluster", cluster, "Cluster should be trimmed");
        assertEquals("/api", prefix, "Prefix should be trimmed");
    }

    // Helper methods using reflection

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDynamicRoutes() {
        try {
            Field field = XdsConfigManager.class.getDeclaredField("dynamicRoutes");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(configManager);
        } catch (Exception e) {
            fail("Failed to access dynamicRoutes field: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<XdsConfigManager.EndpointConfig>> getDynamicEndpoints() {
        try {
            Field field = XdsConfigManager.class.getDeclaredField("dynamicEndpoints");
            field.setAccessible(true);
            return (Map<String, List<XdsConfigManager.EndpointConfig>>) field.get(configManager);
        } catch (Exception e) {
            fail("Failed to access dynamicEndpoints field: " + e.getMessage());
            return null;
        }
    }

    private void clearDynamicRoutes() {
        try {
            Map<String, Object> dynamicRoutes = getDynamicRoutes();
            if (dynamicRoutes != null) {
                dynamicRoutes.clear();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void clearDynamicEndpoints() {
        try {
            Map<String, List<XdsConfigManager.EndpointConfig>> dynamicEndpoints = getDynamicEndpoints();
            if (dynamicEndpoints != null) {
                dynamicEndpoints.clear();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void assertRouteConfigFields(Object routeConfig, String expectedDomain,
                                          String expectedCluster, String expectedPrefix) {
        try {
            String domain = getRouteConfigField(routeConfig, "domain");
            String cluster = getRouteConfigField(routeConfig, "cluster");
            String prefix = getRouteConfigField(routeConfig, "prefix");
            assertEquals(expectedDomain, domain, "Domain should match");
            assertEquals(expectedCluster, cluster, "Cluster should match");
            assertEquals(expectedPrefix, prefix, "Prefix should match");
        } catch (Exception e) {
            fail("Failed to assert RouteConfig fields: " + e.getMessage());
        }
    }

    private String getRouteConfigField(Object routeConfig, String fieldName) {
        try {
            // RouteConfig is a regular class, use direct field access via reflection
            Class<?> configClass = routeConfig.getClass();
            java.lang.reflect.Field field = configClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(routeConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access RouteConfig field: " + fieldName, e);
        }
    }
}
