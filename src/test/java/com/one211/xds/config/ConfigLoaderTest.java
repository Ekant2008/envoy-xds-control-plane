package com.one211.xds.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigLoader class
 */
@DisplayName("Configuration Loader Tests")
public class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        // Ensure fresh config loading for each test
        ConfigLoader.loadConfig();
    }

    @Test
    @DisplayName("Should load gRPC port from config")
    void testGetGrpcPort() {
        int grpcPort = ConfigLoader.getGrpcPort();
        assertEquals(18000, grpcPort, "Default gRPC port should be 18000");
    }

    @Test
    @DisplayName("Should load HTTP port from config")
    void testGetHttpPort() {
        int httpPort = ConfigLoader.getHttpPort();
        assertEquals(18001, httpPort, "Default HTTP port should be 18001");
    }

    @Test
    @DisplayName("Should indicate authentication is enabled by default")
    void testIsAuthenticationEnabled() {
        assertTrue(ConfigLoader.isAuthenticationEnabled(), "Authentication should be enabled by default");
    }

    @Test
    @DisplayName("Should load JWT secret key from config")
    void testGetJwtSecretKey() {
        String secretKey = ConfigLoader.getJwtSecretKey();
        assertNotNull(secretKey, "JWT secret key should not be null");
        assertFalse(secretKey.isEmpty(), "JWT secret key should not be empty");
    }

    @Test
    @DisplayName("Should load JWT expiration time")
    void testGetJwtExpirationMs() {
        long expiration = ConfigLoader.getJwtExpirationMs();
        assertEquals(86400000, expiration, "Default expiration should be 24 hours");
    }

    @Test
    @DisplayName("Should load JWT issuer")
    void testGetJwtIssuer() {
        String issuer = ConfigLoader.getJwtIssuer();
        assertNotNull(issuer, "JWT issuer should not be null");
        assertEquals("xds-control-plane", issuer, "Default issuer should match");
    }

    @Test
    @DisplayName("Should load JWT audience")
    void testGetJwtAudience() {
        String audience = ConfigLoader.getJwtAudience();
        assertNotNull(audience, "JWT audience should not be null");
        assertEquals("xds-api", audience, "Default audience should match");
    }

    @Test
    @DisplayName("Should load users from config")
    void testGetUsers() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        assertNotNull(users, "Users map should not be null");
        assertFalse(users.isEmpty(), "Users map should not be empty");

        // Verify default demo users exist
        assertTrue(users.containsKey("admin"), "Admin user should exist");
        assertTrue(users.containsKey("operator"), "Operator user should exist");
        assertTrue(users.containsKey("viewer"), "Viewer user should exist");
        assertTrue(users.containsKey("service-account"), "Service account user should exist");
    }

    @Test
    @DisplayName("Should load user credentials correctly")
    void testUserCredentials() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        ConfigLoader.UserCredentials admin = users.get("admin");
        assertNotNull(admin, "Admin user should exist");
        assertEquals("admin", admin.username(), "Username should be 'admin'");
        assertEquals("admin123", admin.password(), "Password should match config");
        assertEquals("ADMIN", admin.role(), "Role should be ADMIN");
        assertTrue(admin.enabled(), "Admin should be enabled");
    }

    @Test
    @DisplayName("Should load disabled users but not include them")
    void testDisabledUsers() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        // Check that enabled users are included
        assertTrue(users.values().stream().allMatch(ConfigLoader.UserCredentials::enabled),
                "All loaded users should be enabled");
    }

    @Test
    @DisplayName("Should load role permissions")
    void testGetRolePermissions() {
        Map<String, ConfigLoader.RolePermissions> roles = ConfigLoader.getRolePermissions();

        assertNotNull(roles, "Roles map should not be null");
        assertFalse(roles.isEmpty(), "Roles map should not be empty");

        // Verify default roles exist
        assertTrue(roles.containsKey("ADMIN"), "ADMIN role should exist");
        assertTrue(roles.containsKey("OPERATOR"), "OPERATOR role should exist");
        assertTrue(roles.containsKey("VIEWER"), "VIEWER role should exist");
        assertTrue(roles.containsKey("SERVICE"), "SERVICE role should exist");
    }

    @Test
    @DisplayName("Should load ADMIN role permissions correctly")
    void testAdminRolePermissions() {
        Map<String, ConfigLoader.RolePermissions> roles = ConfigLoader.getRolePermissions();
        ConfigLoader.RolePermissions admin = roles.get("ADMIN");

        assertNotNull(admin, "ADMIN role should exist");
        assertEquals("ADMIN", admin.roleName(), "Role name should be ADMIN");
        assertNotNull(admin.permissions(), "Permissions should not be null");
        assertFalse(admin.permissions().isEmpty(), "ADMIN should have permissions");

        // Verify some expected permissions
        assertTrue(admin.permissions().contains("reload"), "ADMIN should have reload permission");
        assertTrue(admin.permissions().contains("add-cluster"), "ADMIN should have add-cluster permission");
        assertTrue(admin.permissions().contains("update-endpoints"), "ADMIN should have update-endpoints permission");
        assertTrue(admin.permissions().contains("manage-users"), "ADMIN should have manage-users permission");
    }

    @Test
    @DisplayName("Should load OPERATOR role permissions correctly")
    void testOperatorRolePermissions() {
        Map<String, ConfigLoader.RolePermissions> roles = ConfigLoader.getRolePermissions();
        ConfigLoader.RolePermissions operator = roles.get("OPERATOR");

        assertNotNull(operator, "OPERATOR role should exist");
        assertEquals("OPERATOR", operator.roleName(), "Role name should be OPERATOR");

        assertTrue(operator.permissions().contains("reload"), "OPERATOR should have reload permission");
        assertTrue(operator.permissions().contains("add-cluster"), "OPERATOR should have add-cluster permission");
        assertTrue(operator.permissions().contains("update-endpoints"), "OPERATOR should have update-endpoints permission");

        // OPERATOR should NOT have manage-users permission
        assertFalse(operator.permissions().contains("manage-users"),
                "OPERATOR should not have manage-users permission");
    }

    @Test
    @DisplayName("Should load VIEWER role permissions correctly")
    void testViewerRolePermissions() {
        Map<String, ConfigLoader.RolePermissions> roles = ConfigLoader.getRolePermissions();
        ConfigLoader.RolePermissions viewer = roles.get("VIEWER");

        assertNotNull(viewer, "VIEWER role should exist");
        assertEquals("VIEWER", viewer.roleName(), "Role name should be VIEWER");

        // VIEWER should have read-only permissions
        assertTrue(viewer.permissions().contains("view-logs"), "VIEWER should have view-logs permission");
        assertTrue(viewer.permissions().contains("health-check"), "VIEWER should have health-check permission");

        // VIEWER should NOT have write permissions
        assertFalse(viewer.permissions().contains("reload"), "VIEWER should not have reload permission");
        assertFalse(viewer.permissions().contains("add-cluster"), "VIEWER should not have add-cluster permission");
    }

    @Test
    @DisplayName("Should load cache node ID")
    void testGetCacheNodeId() {
        String nodeId = ConfigLoader.getCacheNodeId();
        assertNotNull(nodeId, "Cache node ID should not be null");
        assertEquals("envoy-gateway", nodeId, "Default node ID should be .envoy-gateway.");
    }

    @Test
    @DisplayName("Should load cache refresh interval")
    void testGetCacheRefreshIntervalSec() {
        int interval = ConfigLoader.getCacheRefreshIntervalSec();
        assertEquals(10, interval, "Default refresh interval should be 10 seconds");
    }

    @Test
    @DisplayName("Should indicate demo mode is disabled by default")
    void testIsDemoMode() {
        assertFalse(ConfigLoader.isDemoMode(), "Demo mode should be disabled by default");
    }

    @Test
    @DisplayName("Should load role descriptions")
    void testRoleDescriptions() {
        Map<String, ConfigLoader.RolePermissions> roles = ConfigLoader.getRolePermissions();

        ConfigLoader.RolePermissions admin = roles.get("ADMIN");
        assertNotNull(admin.description(), "ADMIN role should have description");
        assertEquals("Full administrative access", admin.description(),
                "ADMIN description should match config");

        ConfigLoader.RolePermissions viewer = roles.get("VIEWER");
        assertNotNull(viewer.description(), "VIEWER role should have description");
        assertEquals("Read-only access", viewer.description(),
                "VIEWER description should match config");
    }

    @Test
    @DisplayName("Should load all user roles correctly")
    void testAllUserRoles() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        assertEquals("ADMIN", users.get("admin").role(), "Admin should have ADMIN role");
        assertEquals("OPERATOR", users.get("operator").role(), "Operator should have OPERATOR role");
        assertEquals("VIEWER", users.get("viewer").role(), "Viewer should have VIEWER role");
        assertEquals("SERVICE", users.get("service-account").role(), "Service account should have SERVICE role");
    }

    @Test
    @DisplayName("Should load all user passwords correctly")
    void testAllUserPasswords() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        assertEquals("admin123", users.get("admin").password(), "Admin password should match");
        assertEquals("operator123", users.get("operator").password(), "Operator password should match");
        assertEquals("viewer123", users.get("viewer").password(), "Viewer password should match");
        assertEquals("service-secret", users.get("service-account").password(), "Service account password should match");
    }

    @Test
    @DisplayName("Should verify all demo users are enabled")
    void testAllDemoUsersEnabled() {
        Map<String, ConfigLoader.UserCredentials> users = ConfigLoader.getUsers();

        assertTrue(users.get("admin").enabled(), "Admin should be enabled");
        assertTrue(users.get("operator").enabled(), "Operator should be enabled");
        assertTrue(users.get("viewer").enabled(), "Viewer should be enabled");
        assertTrue(users.get("service-account").enabled(), "Service account should be enabled");
    }

    @Test
    @DisplayName("Should handle multiple config loads")
    void testMultipleConfigLoads() {
        // Load config twice to ensure it works correctly
        ConfigLoader.loadConfig();
        int firstPort = ConfigLoader.getHttpPort();

        ConfigLoader.loadConfig();
        int secondPort = ConfigLoader.getHttpPort();

        assertEquals(firstPort, secondPort, "Config should be consistent across loads");
    }
}
