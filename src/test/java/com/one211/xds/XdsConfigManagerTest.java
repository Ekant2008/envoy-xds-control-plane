package com.one211.xds;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XdsConfigManager
 */
class XdsConfigManagerTest {

    private final XdsConfigManager configManager = new XdsConfigManager();

    @Test
    void testGenerateInitialSnapshotNotNull() {
        io.envoyproxy.controlplane.cache.Snapshot snapshot = configManager.generateInitialSnapshot();

        assertNotNull(snapshot, "Snapshot should not be null");
    }

    @Test
    void testSnapshotVersion() {
        io.envoyproxy.controlplane.cache.Snapshot snapshot = configManager.generateInitialSnapshot();

        // version() requires ResourceType and resource names
        // Dynamic versioning appends "-vN" to the base version
        String version = snapshot.version(io.envoyproxy.controlplane.cache.Resources.ResourceType.CLUSTER, List.of());
        assertTrue(version.startsWith("1.0.0-v"),
                "Snapshot version should start with 1.0.0-v, got: " + version);
    }

    @Test
    void testSnapshotHasResources() {
        io.envoyproxy.controlplane.cache.Snapshot snapshot = configManager.generateInitialSnapshot();

        assertNotNull(snapshot, "Snapshot should not be null");
        // Verify snapshot has resources for different types
        assertNotNull(snapshot.resources(io.envoyproxy.controlplane.cache.Resources.ResourceType.CLUSTER),
                "Should have clusters");
        assertNotNull(snapshot.resources(io.envoyproxy.controlplane.cache.Resources.ResourceType.LISTENER),
                "Should have listeners");
        assertNotNull(snapshot.resources(io.envoyproxy.controlplane.cache.Resources.ResourceType.ROUTE),
                "Should have routes");
    }

    @Test
    void testGenerateListenersNotEmpty() throws Exception {
        // Use reflection to access private method
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateListeners");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Listener> listeners = (List<Listener>) method.invoke(configManager);

        assertNotNull(listeners, "Listeners should not be null");
        assertFalse(listeners.isEmpty(), "Listeners should not be empty");
        assertTrue(listeners.size() >= 6, "Should have at least 6 listeners");
    }

    @Test
    void testHttpsGatewayListenerExists() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateListeners");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Listener> listeners = (List<Listener>) method.invoke(configManager);

        boolean hasHttpsGateway = listeners.stream()
                .anyMatch(l -> "https_gateway".equals(l.getName()));

        assertTrue(hasHttpsGateway, "Should have https_gateway listener");
    }

    @Test
    void testTcpListenersExist() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateListeners");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Listener> listeners = (List<Listener>) method.invoke(configManager);

        String[] expectedTcpListeners = {
                "flight_listener_controllers",
                "flight_listener_ollylake",
                "tcp_backend_database",
                "tcp_cluster_database"
        };

        for (String expectedName : expectedTcpListeners) {
            boolean exists = listeners.stream()
                    .anyMatch(l -> expectedName.equals(l.getName()));
            assertTrue(exists, "Should have " + expectedName + " listener");
        }
    }

    @Test
    void testMinioApiListenerExists() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateListeners");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Listener> listeners = (List<Listener>) method.invoke(configManager);

        boolean hasMinioApi = listeners.stream()
                .anyMatch(l -> "minio_s3_api".equals(l.getName()));

        assertTrue(hasMinioApi, "Should have minio_s3_api listener");
    }

    @Test
    void testGenerateRoutesNotEmpty() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateRoutes");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RouteConfiguration> routes = (List<RouteConfiguration>) method.invoke(configManager);

        assertNotNull(routes, "Routes should not be null");
        assertFalse(routes.isEmpty(), "Routes should not be empty");
        assertTrue(routes.size() >= 2, "Should have at least 2 route configurations");
    }

    @Test
    void testRouteConfigurations() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateRoutes");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RouteConfiguration> routes = (List<RouteConfiguration>) method.invoke(configManager);

        boolean hasLocalRoute = routes.stream()
                .anyMatch(r -> "local_route".equals(r.getName()));

        boolean hasMinioRoute = routes.stream()
                .anyMatch(r -> "minio_api_route".equals(r.getName()));

        assertTrue(hasLocalRoute, "Should have local_route configuration");
        assertTrue(hasMinioRoute, "Should have minio_api_route configuration");
    }

    @Test
    void testVirtualHostsCount() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateVirtualHosts");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<VirtualHost> virtualHosts = (List<VirtualHost>) method.invoke(configManager);

        assertNotNull(virtualHosts, "Virtual hosts should not be null");
        assertEquals(5, virtualHosts.size(), "Should have 5 virtual hosts (controller1_host and controller2_host removed - now managed dynamically)");
    }

    @Test
    void testBackendVirtualHostExists() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateVirtualHosts");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<VirtualHost> virtualHosts = (List<VirtualHost>) method.invoke(configManager);

        boolean hasBackend = virtualHosts.stream()
                .anyMatch(vh -> "backend_host".equals(vh.getName()));

        assertTrue(hasBackend, "Should have backend_host virtual host");
    }

    @Test
    void testControllerVirtualHostsExist() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateVirtualHosts");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<VirtualHost> virtualHosts = (List<VirtualHost>) method.invoke(configManager);

        // Controller1_host and controller2_host removed - now managed dynamically via /api/routes
        String[] expectedHosts = {
                "controller_lb_host"
        };

        for (String expectedName : expectedHosts) {
            boolean exists = virtualHosts.stream()
                    .anyMatch(vh -> expectedName.equals(vh.getName()));
            assertTrue(exists, "Should have " + expectedName + " virtual host");
        }

        // Verify controller1_host and controller2_host do NOT exist
        assertFalse(virtualHosts.stream().anyMatch(vh -> "controller1_host".equals(vh.getName())),
                "Should NOT have controller1_host (now managed dynamically)");
        assertFalse(virtualHosts.stream().anyMatch(vh -> "controller2_host".equals(vh.getName())),
                "Should NOT have controller2_host (now managed dynamically)");
    }

    @Test
    void testGenerateClustersNotEmpty() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateClusters");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Cluster> clusters = (List<Cluster>) method.invoke(configManager);

        assertNotNull(clusters, "Clusters should not be null");
        assertFalse(clusters.isEmpty(), "Clusters should not be empty");
        assertTrue(clusters.size() >= 10, "Should have at least 10 clusters");
    }

    @Test
    void testExpectedClustersExist() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateClusters");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Cluster> clusters = (List<Cluster>) method.invoke(configManager);

        String[] expectedClusters = {
                "backend_http",
                "frontend_http",
                "sql_controller_lb_http",
                "controller_flight_cluster",
                "ollylake_http",
                "backend_database_tcp",
                "cluster_database_tcp"
        };

        for (String expectedName : expectedClusters) {
            boolean exists = clusters.stream()
                    .anyMatch(c -> expectedName.equals(c.getName()));
            assertTrue(exists, "Should have " + expectedName + " cluster");
        }
    }

    @Test
    void testGenerateEndpointsEmptyWhenNoDynamicRegistrations() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateEndpoints");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ClusterLoadAssignment> endpoints = (List<ClusterLoadAssignment>) method.invoke(configManager);

        assertNotNull(endpoints, "Endpoints should not be null");
        // With no dynamic registrations, generateEndpoints() returns empty.
        // Static clusters (backend_database_tcp, etc.) have their endpoints
        // inline in the cluster definition, not via EDS.
        assertTrue(endpoints.isEmpty(), "Endpoints should be empty when no dynamic services are registered");
    }

    @Test
    void testControllerEndpointsNotInStaticEDS() throws Exception {
        java.lang.reflect.Method method = XdsConfigManager.class.getDeclaredMethod("generateEndpoints");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ClusterLoadAssignment> endpoints = (List<ClusterLoadAssignment>) method.invoke(configManager);

        // With dynamic-only controller topology, generateEndpoints() returns empty
        // when no controllers have registered. Static infrastructure clusters
        // (backend_database_tcp, cluster_database_tcp, ollylake_flight_cluster)
        // have their endpoints inline in the cluster definition.
        assertTrue(endpoints.isEmpty(),
                "No EDS endpoints should exist before any dynamic registrations");

        // Verify no controller endpoints leak into static EDS
        assertFalse(endpoints.stream().anyMatch(e -> "controller_flight_cluster".equals(e.getClusterName())),
                "Should NOT have controller_flight_cluster static endpoint");
        assertFalse(endpoints.stream().anyMatch(e -> "sql_controller_lb_http".equals(e.getClusterName())),
                "Should NOT have sql_controller_lb_http static endpoint");
    }

    @Test
    void testFileTimestampsInitiallyEmpty() {
        Map<String, java.nio.file.attribute.FileTime> timestamps = getFileTimestamps();

        assertNotNull(timestamps, "File timestamps map should not be null");
        assertTrue(timestamps.isEmpty(), "File timestamps should be empty initially");
    }

    @Test
    void testDynamicEndpointsInitiallyEmpty() {
        Map<String, List<XdsConfigManager.EndpointConfig>> endpoints = getDynamicEndpoints();

        assertNotNull(endpoints, "Dynamic endpoints map should not be null");
        assertTrue(endpoints.isEmpty(), "Dynamic endpoints should be empty initially");
    }

    @Test
    void testEndpointConfigConstructor() {
        XdsConfigManager.EndpointConfig endpoint = new XdsConfigManager.EndpointConfig("test_cluster", "test-host", 8080);

        assertEquals("test_cluster", endpoint.getClusterName());
        assertEquals("test-host", endpoint.getHost());
        assertEquals(8080, endpoint.getPort());
    }

    // Helper methods to access private fields via reflection
    private Map<String, java.nio.file.attribute.FileTime> getFileTimestamps() {
        try {
            java.lang.reflect.Field field = XdsConfigManager.class.getDeclaredField("fileTimestamps");
            field.setAccessible(true);
            return (Map<String, java.nio.file.attribute.FileTime>) field.get(configManager);
        } catch (Exception e) {
            fail("Failed to access fileTimestamps field: " + e.getMessage());
            return null;
        }
    }

    private Map<String, List<XdsConfigManager.EndpointConfig>> getDynamicEndpoints() {
        try {
            java.lang.reflect.Field field = XdsConfigManager.class.getDeclaredField("dynamicEndpoints");
            field.setAccessible(true);
            return (Map<String, List<XdsConfigManager.EndpointConfig>>) field.get(configManager);
        } catch (Exception e) {
            fail("Failed to access dynamicEndpoints field: " + e.getMessage());
            return null;
        }
    }
}
