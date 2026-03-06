package com.one211.xds;

import com.one211.xds.auth.AuthenticationFilter;
import com.one211.xds.auth.AuthorizationHelper;
import com.one211.xds.auth.LoginHandler;
import com.one211.xds.auth.UserStore;
import com.one211.xds.config.ConfigLoader;
import com.one211.xds.config.XdsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * xDS Control Plane for Dynamic Envoy Configuration
 *
 * This service provides dynamic configuration updates to Envoy via xDS API.
 * It allows adding new executors and controllers without restarting Envoy.
 */
public class XdsControlPlaneApplication {

    private static final Logger logger = LoggerFactory.getLogger(XdsControlPlaneApplication.class);
    private static final int GRPC_PORT = 18000;
    private static final int HTTP_PORT = 18001;

    private static String nodeId;
    private static final String BASE_URL_FORMAT = "http://%s:%d";

    private final SimpleCache<String> cache;
    private final Server grpcServer;
    private final ScheduledExecutorService executorService;
    private final XdsConfigManager configManager;
    private final UserStore userStore;
    private final boolean requireAuthentication;
    private final ObjectMapper jsonMapper;
    private com.sun.net.httpserver.HttpServer httpServer;

    private static void initializeNodeId() {
        try {
            ConfigLoader.loadConfig();
            nodeId = ConfigLoader.getCacheNodeId();
            logger.info("Initialized with NODE_ID: {}", nodeId);
        } catch (Exception e) {
            logger.warn("Failed to load NODE_ID from config, using default: test-node", e);
            nodeId = "test-node";
        }
    }

    static {
        initializeNodeId();
    }

    public XdsControlPlaneApplication() {
        // The cache groups resources by node ID - the NodeGroup function maps a node to its group key
        this.cache = new SimpleCache<>(node -> nodeId);
        // The cache groups resources by node ID - the NodeGroup function maps a node to its group key;
        this.configManager = new XdsConfigManager();
        this.executorService = Executors.newScheduledThreadPool(2);

        // Initialize user store for authentication
        this.userStore = new UserStore();

        // Check if authentication should be enabled (can be disabled via environment variable)
        this.requireAuthentication = !Boolean.parseBoolean(System.getenv().getOrDefault("DISABLE_AUTH", "false"));
        this.jsonMapper = new ObjectMapper();

        logger.info("Authentication {}", requireAuthentication ? "enabled" : "disabled");
        logger.info("Using NODE_ID: {}", nodeId);

        // Initialize cache with configured NODE_ID
        initializeCache();

        // Build gRPC server with xDS services
        V3DiscoveryServer discoveryServer = new V3DiscoveryServer(cache);
        this.grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
                .build();
    }

    private void initializeCache() {
        try {
            Snapshot snapshot = configManager.generateInitialSnapshot();
            cache.setSnapshot(nodeId, snapshot);
            logger.info("Initial xDS snapshot loaded successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize cache", e);
            throw new RuntimeException("Failed to initialize cache", e);
        }
    }

    private void startConfigWatcher() {
        // Periodically check for configuration changes
        executorService.scheduleAtFixedRate(() -> {
            try {
                if (configManager.hasConfigurationChanged()) {
                    logger.info("Detected configuration changes, updating snapshot...");
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);
                    logger.info("Configuration updated from file changes");
                }
            } catch (Exception e) {
                logger.error("Failed to check for configuration changes", e);
            }
        }, XdsConfig.SCHEDULER_INITIAL_DELAY_SEC, XdsConfig.SCHEDULER_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void startHttpServer() {
        try {
            httpServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(HTTP_PORT), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Login endpoint (public, no auth required)
        httpServer.createContext("/login", new LoginHandler(userStore));
        logger.info("Login endpoint registered at /login");

        // Health check endpoint (public)
        httpServer.createContext("/health", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"status\":\"ok\"}".getBytes());
            exchange.getResponseBody().close();
        });
        logger.info("Health check endpoint registered");

        // Reload endpoint (protected)
        com.sun.net.httpserver.HttpContext reloadContext = httpServer.createContext("/reload", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Configuration reload requested by user: {}", username);

                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);
                    logger.info("Configuration reloaded successfully");

                    String response = "{\"status\":\"ok\",\"message\":\"Configuration reloaded\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to reload configuration", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        reloadContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));

        // Add cluster endpoint (protected)
        com.sun.net.httpserver.HttpContext clustersContext = httpServer.createContext("/api/clusters", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Add cluster requested by user: {}", username);

                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    configManager.addCluster(requestBody);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Cluster added\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to add cluster", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        clustersContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));

        // Update endpoints endpoint (protected)
        com.sun.net.httpserver.HttpContext endpointsContext = httpServer.createContext("/api/endpoints", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Update endpoints requested by user: {}", username);

                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    configManager.updateEndpoints(requestBody);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Endpoints updated\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to update endpoints", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        endpointsContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));

        // Add route endpoint (protected)
        com.sun.net.httpserver.HttpContext routesContext = httpServer.createContext("/api/routes", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Add route requested by user: {}", username);

                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    configManager.addRoute(requestBody);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Route added\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to add route", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else if ("GET".equals(exchange.getRequestMethod())) {
                try {
                    logger.info("List routes requested");

                    String routesJson = configManager.listRoutes();
                    byte[] responseBytes = routesJson.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to list routes", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Delete route requested by user: {}", username);

                    // Extract domain pattern from path
                    String path = exchange.getRequestURI().getPath();
                    String domainPattern = path.substring("/api/routes/".length());

                    configManager.deleteRoute(domainPattern);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Route deleted\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to delete route", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        routesContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));

        // Add route template endpoint (protected)
        com.sun.net.httpserver.HttpContext routeTemplateContext = httpServer.createContext("/api/routes/template/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Add route from template requested by user: {}", username);

                    // Extract template name from path
                    String path = exchange.getRequestURI().getPath();
                    String templateName = path.substring("/api/routes/template/".length());

                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    String clusterOverride = null;

                    if (!requestBody.isEmpty()) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode jsonNode = jsonMapper.readTree(requestBody);
                            if (jsonNode.has("cluster_override")) {
                                clusterOverride = jsonNode.get("cluster_override").asText();
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to parse cluster override from request body", e);
                        }
                    }

                    configManager.addRouteFromTemplate(templateName, clusterOverride);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Route created from template\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to create route from template", e);
                    String errorResponse = "{\"error\":\"Internal server error\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        routeTemplateContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));


        // Controller registration endpoint (protected)
        com.sun.net.httpserver.HttpContext registerContext = httpServer.createContext("/api/controllers/register", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Controller registration requested by user: {}", username);

                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    configManager.registerController(requestBody);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Controller registered successfully\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to register controller", e);
                    String errorResponse = "{\"error\":\"" + e.getMessage() + "\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        registerContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));

        // Controller deregistration endpoint (protected)
        com.sun.net.httpserver.HttpContext deregisterContext = httpServer.createContext("/api/controllers/deregister", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String username = exchange.getRequestHeaders().getFirst("X-User");
                    logger.info("Controller deregistration requested by user: {}", username);

                    // Parse request body: {"domain": "controller3.one211.com", "host": "controller3"}
                    String requestBody = new String(exchange.getRequestBody().readAllBytes());
                    com.fasterxml.jackson.databind.JsonNode jsonNode = jsonMapper.readTree(requestBody);
                    String domain = jsonNode.has("domain") ? jsonNode.get("domain").asText() : null;
                    String host = jsonNode.has("host") ? jsonNode.get("host").asText() : null;

                    if (domain == null || host == null) {
                        throw new IllegalArgumentException("Both domain and host are required");
                    }

                    configManager.deregisterController(domain, host);
                    Snapshot snapshot = configManager.generateInitialSnapshot();
                    cache.setSnapshot(nodeId, snapshot);

                    String response = "{\"status\":\"ok\",\"message\":\"Controller deregistered successfully\"}";
                    byte[] responseBytes = response.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    logger.error("Failed to deregister controller", e);
                    String errorResponse = "{\"error\":\"" + e.getMessage() + "\"}";
                    byte[] errorBytes = errorResponse.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    exchange.getResponseBody().close();
                }
            } else {
                String errorResponse = "{\"error\":\"Method not allowed\"}";
                byte[] errorBytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, errorBytes.length);
                exchange.getResponseBody().write(errorBytes);
                exchange.getResponseBody().close();
            }
        });
        deregisterContext.getFilters().add(new AuthenticationFilter(userStore, requireAuthentication));


        httpServer.setExecutor(executorService);
        httpServer.start();
        logger.info("HTTP management server started on port {}", HTTP_PORT);
    }

    public void start() throws Exception {
        logger.info("Starting xDS Control Plane...");

        // Start gRPC server
        grpcServer.start();
        logger.info("gRPC server started on port {}", GRPC_PORT);

        // Start HTTP management server
        startHttpServer();

        // Start configuration watcher
        startConfigWatcher();

        logger.info("xDS Control Plane started successfully");
        logger.info("gRPC server listening on port {}", GRPC_PORT);
        logger.info("HTTP management server listening on port {}", HTTP_PORT);
        logger.info("NODE_ID: {}", nodeId);
    }

    public void stop() {
        logger.info("Stopping xDS Control Plane...");

        // Stop HTTP server
        if (httpServer != null) {
            httpServer.stop(0);
            logger.info("HTTP server stopped");
        }

        // Shutdown gRPC server
        grpcServer.shutdown();
        logger.info("gRPC server shutdown initiated");

        // Shutdown executor service with proper waiting
        executorService.shutdown();
        try {
            // Wait for tasks to complete (up to 30 seconds)
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("ExecutorService did not terminate gracefully, forcing shutdown");
                // Force shutdown if tasks don't complete
                executorService.shutdownNow();
                // Wait another 10 seconds for forced shutdown
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("ExecutorService did not terminate even after forced shutdown");
                } else {
                    logger.info("ExecutorService forced shutdown completed");
                }
            } else {
                logger.info("ExecutorService shutdown completed gracefully");
            }
        } catch (InterruptedException e) {
            // Restore interrupted status
            Thread.currentThread().interrupt();
            logger.warn("ExecutorService shutdown interrupted", e);
            // Force shutdown if interrupted
            executorService.shutdownNow();
        }

        logger.info("xDS Control Plane stopped");
    }

    public static void main(String[] args) {
        XdsControlPlaneApplication app = new XdsControlPlaneApplication();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));

        try {
            app.start();
            // Keep the main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Failed to start xDS Control Plane", e);
            System.exit(1);
        }
    }
}
