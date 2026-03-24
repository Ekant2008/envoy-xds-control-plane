package com.one211.xds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

/**
 * Tests for controller endpoint deduplication and shared domain support.
 *
 * This test verifies the fix for:
 * - Host+Port deduplication (not just Host)
 * - Multiple controllers per shared domain
 * - Correct deregistration of controllers on shared domains
 */
public class ControllerDeduplicationTest {

    private XdsConfigManager configManager;

    @BeforeEach
    public void setUp() {
        configManager = new XdsConfigManager();
    }

    @Test
    public void testHostPortDeduplication() throws IOException {
        // Register controller1 on same host, different port
        configManager.registerController(
            "controller1.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        // Register controller2 on same host, different port
        // Should NOT overwrite controller1 because ports are different
        configManager.registerController(
            "controller2.one211.com",
            "sql-controller1",
            9007,
            59308
        );

        // Both endpoints should exist in cluster
        List<XdsConfigManager.EndpointConfig> endpoints = 
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");
        
        assertNotNull(endpoints, "Endpoints should exist");
        assertEquals(2, endpoints.size(), 
            "Should have 2 endpoints on same host with different ports");
        
        // Verify both ports are present
        boolean hasPort9006 = endpoints.stream().anyMatch(e -> e.getPort() == 9006);
        boolean hasPort9007 = endpoints.stream().anyMatch(e -> e.getPort() == 9007);
        
        assertTrue(hasPort9006, "Should have endpoint on port 9006");
        assertTrue(hasPort9007, "Should have endpoint on port 9007");
    }

    @Test
    public void testSameHostSamePortIsIdempotent() throws IOException {
        // Register controller1
        configManager.registerController(
            "controller1.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        // Register same host with same HTTP port — should be idempotent
        configManager.registerController(
            "controller2.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        List<XdsConfigManager.EndpointConfig> httpEndpoints =
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");

        assertNotNull(httpEndpoints, "Endpoints should exist");
        assertEquals(1, httpEndpoints.size(),
            "Should have only 1 HTTP endpoint (same host+port deduplicated)");

        List<XdsConfigManager.EndpointConfig> flightEndpoints =
            configManager.getDynamicEndpoints().get("controller_flight_cluster");
        assertEquals(1, flightEndpoints.size(),
            "Should have only 1 flight endpoint (same host+port deduplicated)");
    }

    @Test
    public void testSameHostDifferentPortsCoexist() throws IOException {
        // Register two processes on same host with different ports
        configManager.registerController(
            "controller1.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        configManager.registerController(
            "controller2.one211.com",
            "sql-controller1",
            9007,
            59309
        );

        List<XdsConfigManager.EndpointConfig> httpEndpoints =
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");

        assertNotNull(httpEndpoints, "Endpoints should exist");
        assertEquals(2, httpEndpoints.size(),
            "Should have 2 HTTP endpoints (different ports on same host)");

        List<XdsConfigManager.EndpointConfig> flightEndpoints =
            configManager.getDynamicEndpoints().get("controller_flight_cluster");
        assertEquals(2, flightEndpoints.size(),
            "Should have 2 flight endpoints (different ports on same host)");
    }

    @Test
    public void testSharedDomainMultipleControllers() throws IOException {
        // Register both controllers under shared domain
        configManager.registerController(
            "controller.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        configManager.registerController(
            "controller.one211.com",
            "sql-controller2",
            9007,
            59301
        );

        // Both endpoints should be in cluster
        List<XdsConfigManager.EndpointConfig> httpEndpoints = 
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");
        
        assertNotNull(httpEndpoints, "HTTP endpoints should exist");
        assertEquals(2, httpEndpoints.size(), 
            "Shared domain should have 2 controllers");
        
        // Verify both hosts are present
        boolean hasController1 = httpEndpoints.stream()
            .anyMatch(e -> "sql-controller1".equals(e.getHost()) && e.getPort() == 9006);
        boolean hasController2 = httpEndpoints.stream()
            .anyMatch(e -> "sql-controller2".equals(e.getHost()) && e.getPort() == 9007);
        
        assertTrue(hasController1, "Should have sql-controller1:9006");
        assertTrue(hasController2, "Should have sql-controller2:9007");
    }

    @Test
    public void testDeregisterControllerRemovesCorrectEndpoints() throws IOException {
        // Register two controllers on shared domain
        configManager.registerController(
            "controller.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        configManager.registerController(
            "controller.one211.com",
            "sql-controller2",
            9007,
            59301
        );

        // Also register controller2 under its own domain
        configManager.registerController(
            "controller2.one211.com",
            "sql-controller2",
            9007,
            59301
        );

        // Deregister only sql-controller1 (using shared domain)
        // Should only remove sql-controller1 endpoints, not sql-controller2
        configManager.deregisterController("controller.one211.com", "sql-controller1");

        List<XdsConfigManager.EndpointConfig> httpEndpoints = 
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");
        
        assertNotNull(httpEndpoints, "HTTP endpoints should still exist");
        assertEquals(1, httpEndpoints.size(), 
            "Should have 1 endpoint after deregistration");
        
        // Verify only sql-controller2:9007 remains
        XdsConfigManager.EndpointConfig remaining = httpEndpoints.get(0);
        assertEquals("sql-controller2", remaining.getHost());
        assertEquals(9007, remaining.getPort());

        // Flight cluster should also have only sql-controller2
        List<XdsConfigManager.EndpointConfig> flightEndpoints = 
            configManager.getDynamicEndpoints().get("controller_flight_cluster");
        assertNotNull(flightEndpoints, "Flight endpoints should still exist");
        assertEquals(1, flightEndpoints.size(), 
            "Flight cluster should have 1 endpoint");
    }

    @Test
    public void testFlightClusterDeduplication() throws IOException {
        // Register controllers with same host, different flight ports
        configManager.registerController(
            "controller1.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        configManager.registerController(
            "controller2.one211.com",
            "sql-controller1",
            9007,
            59307  // Same flight port
        );

        List<XdsConfigManager.EndpointConfig> flightEndpoints = 
            configManager.getDynamicEndpoints().get("controller_flight_cluster");
        
        assertNotNull(flightEndpoints, "Flight endpoints should exist");
        
        // Both should be in HTTP cluster (different HTTP ports)
        List<XdsConfigManager.EndpointConfig> httpEndpoints = 
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");
        assertEquals(2, httpEndpoints.size(), 
            "HTTP cluster should have 2 endpoints");
        
        // Flight cluster deduplication by host+port
        // The second one replaces the first if same host+flight-port
        assertEquals(1, flightEndpoints.size(), 
            "Flight cluster should have 1 endpoint (deduplicated by host+port)");
    }

    @Test
    public void testEmptyDomainDeregistration() {
        // Deregister a domain that was never registered
        configManager.deregisterController("nonexistent.one211.com", "sql-controller1");
        
        // Should not throw exception
        // Should log warning message (verified in logs)
    }

    @Test
    public void testMultipleDedicatedDomainsSameHost() throws IOException {
        // Test scenario from original bug report:
        // Two dedicated domains pointing to controllers on same machine
        configManager.registerController(
            "controller1.one211.com",
            "sql-controller1",
            9006,
            59307
        );

        configManager.registerController(
            "controller2.one211.com",
            "sql-controller1",
            9007,
            59301
        );

        List<XdsConfigManager.EndpointConfig> httpEndpoints = 
            configManager.getDynamicEndpoints().get("sql_controller_lb_http");
        
        assertEquals(2, httpEndpoints.size(), 
            "Two dedicated domains on same host should create 2 endpoints");
        
        // Verify both ports are present
        boolean has9006 = httpEndpoints.stream().anyMatch(e -> e.getPort() == 9006);
        boolean has9007 = httpEndpoints.stream().anyMatch(e -> e.getPort() == 9007);
        
        assertTrue(has9006, "Should have port 9006 for controller1");
        assertTrue(has9007, "Should have port 9007 for controller2");
    }
}
