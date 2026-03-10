package com.one211.xds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.one211.xds.config.XdsConfig;
import java.util.concurrent.atomic.AtomicLong;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.*;
import io.envoyproxy.envoy.config.endpoint.v3.*;
import io.envoyproxy.envoy.config.listener.v3.*;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.type.v3.*;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import com.google.protobuf.Duration;
import com.google.protobuf.Any;

import org.slf4j.Logger;

import java.io.File;

/**
 * Manages xDS configuration generation and updates.
 *
 * Controller topology is fully dynamic — no static controller instances are
 * hardcoded here. Controllers register via /api/controllers/register and
 * their endpoints are added to:
 *   - sql_controller_lb_http (HTTP round-robin)
 *   - controller_flight_cluster (Arrow Flight SQL)
 */
public class XdsConfigManager {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(XdsConfigManager.class);
    private static final String VERSION = "1.0.0";

    private static final AtomicLong snapshotVersionCounter = new AtomicLong(0);

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final Map<String, FileTime> fileTimestamps;
    private final Map<String, List<EndpointConfig>> dynamicEndpoints;
    private final Map<String, RouteConfig> dynamicRoutes;
    private final Map<String, RouteTemplate> routeTemplates;

    public XdsConfigManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
        this.fileTimestamps = new ConcurrentHashMap<>();
        this.dynamicEndpoints = new ConcurrentHashMap<>();
        this.dynamicRoutes = new ConcurrentHashMap<>();
        this.routeTemplates = new ConcurrentHashMap<>();
        initializeRouteTemplates();
    }

    /**
     * Generates the initial xDS snapshot with all resources
     * Uses dynamic snapshot version for proper configuration updates
     */
    public Snapshot generateInitialSnapshot() {
        try {
            List<Listener> listeners = generateListeners();
            List<RouteConfiguration> routes = generateRoutes();
            List<Cluster> clusters = generateClusters();
            List<ClusterLoadAssignment> endpoints = generateEndpoints();

            // Increment snapshot version on each generation
            snapshotVersionCounter.incrementAndGet();
            String currentVersion = VERSION + "-v" + snapshotVersionCounter.get();

            return Snapshot.create(
                    clusters,
                    endpoints,
                    listeners,
                    routes,
                    Collections.emptyList(),
                    currentVersion
            );
        } catch (Exception e) {
            logger.error("Failed to generate initial snapshot", e);
            throw new RuntimeException("Failed to generate initial snapshot", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LISTENERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates all listeners for the configuration.
     * Controller flight listeners use a single unified port → controller_flight_cluster.
     */
    private List<Listener> generateListeners() {
        List<Listener> listeners = new ArrayList<>();

        // 1. HTTPS Gateway Listener (port 443) with TLS and RDS
        listeners.add(generateHttpsGatewayListener());

        // 2. Arrow Flight SQL TCP Listeners
        //    Single unified listener for all controllers
        listeners.add(generateTcpListener("flight_listener_controllers",
                XdsConfig.FLIGHT_PORT_CONTROLLERS, "controller_flight_cluster"));
        listeners.add(generateTcpListener("flight_listener_ollylake",
                XdsConfig.FLIGHT_PORT_OLLYLAKE, "ollylake_flight_cluster"));

        // 3. PostgreSQL TCP Listeners
        listeners.add(generateTcpListener("tcp_backend_database",
                XdsConfig.POSTGRES_PORT_BACKEND, "backend_database_tcp"));
        listeners.add(generateTcpListener("tcp_cluster_database",
                XdsConfig.POSTGRES_PORT_CLUSTER, "cluster_database_tcp"));

        // 4. MinIO S3 API HTTP Listener
        listeners.add(generateMinioS3Listener());

        return listeners;
    }

    /**
     * Generates the HTTPS gateway listener with TLS termination and RDS
     */
    private Listener generateHttpsGatewayListener() {
        // TLS transport socket
        DownstreamTlsContext tlsContext = DownstreamTlsContext.newBuilder()
                .setCommonTlsContext(CommonTlsContext.newBuilder()
                        .addTlsCertificates(TlsCertificate.newBuilder()
                                .setCertificateChain(DataSource.newBuilder()
                                        .setFilename(XdsConfig.TLS_CERT_CHAIN)
                                        .build())
                                .setPrivateKey(DataSource.newBuilder()
                                        .setFilename(XdsConfig.TLS_PRIVATE_KEY)
                                        .build())
                                .build())
                        .addAlpnProtocols("h2")
                        .addAlpnProtocols("http/1.1")
                        .build())
                .build();

        // HTTP Connection Manager with RDS
        HttpConnectionManager hcm = HttpConnectionManager.newBuilder()
                .setStatPrefix("ingress_https")
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRds(Rds.newBuilder()
                        .setRouteConfigName("local_route")
                        .setConfigSource(ConfigSource.newBuilder()
                                .setAds(AggregatedConfigSource.getDefaultInstance())
                                .setResourceApiVersion(ApiVersion.V3)
                                .build())
                        .build())
                .addUpgradeConfigs(HttpConnectionManager.UpgradeConfig.newBuilder()
                        .setUpgradeType("websocket")
                        .build())
                .addHttpFilters(HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.router.v3.Router")
                                .build())
                        .build())
                .build();

        return Listener.newBuilder()
                .setName("https_gateway")
                .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                                .setAddress("0.0.0.0")
                                .setPortValue(XdsConfig.HTTPS_GATEWAY_PORT)
                                .build())
                        .build())
                .addFilterChains(FilterChain.newBuilder()
                        .setTransportSocket(TransportSocket.newBuilder()
                                .setName("envoy.transport_sockets.tls")
                                .setTypedConfig(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext")
                                        .setValue(tlsContext.toByteString())
                                        .build())
                                .build())
                        .addFilters(Filter.newBuilder()
                                .setName("envoy.filters.network.http_connection_manager")
                                .setTypedConfig(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                        .setValue(hcm.toByteString())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Generates a TCP proxy listener
     */
    private Listener generateTcpListener(String name, int port, String cluster) {
        io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy tcpProxy =
                io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy.newBuilder()
                        .setStatPrefix(name)
                        .setCluster(cluster)
                        .setIdleTimeout(Duration.newBuilder()
                                .setSeconds(XdsConfig.TIMEOUT_TCP_IDLE).build())
                        .build();

        return Listener.newBuilder()
                .setName(name)
                .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                                .setAddress("0.0.0.0")
                                .setPortValue(port)
                                .build())
                        .build())
                .addFilterChains(FilterChain.newBuilder()
                        .addFilters(Filter.newBuilder()
                                .setName("envoy.filters.network.tcp_proxy")
                                .setTypedConfig(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy")
                                        .setValue(tcpProxy.toByteString())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Generates the MinIO S3 API HTTP listener
     */
    private Listener generateMinioS3Listener() {
        HttpConnectionManager hcm = HttpConnectionManager.newBuilder()
                .setStatPrefix("minio_s3_api")
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setRouteConfig(RouteConfiguration.newBuilder()
                        .setName("minio_api_route")
                        .addVirtualHosts(VirtualHost.newBuilder()
                                .setName("minio_api")
                                .addDomains("*")
                                .addRoutes(Route.newBuilder()
                                        .setMatch(RouteMatch.newBuilder()
                                                .setPrefix("/").build())
                                        .setRoute(RouteAction.newBuilder()
                                                .setCluster("minio_api_http")
                                                .setTimeout(Duration.newBuilder()
                                                        .setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addHttpFilters(HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.router.v3.Router")
                                .build())
                        .build())
                .build();

        return Listener.newBuilder()
                .setName("minio_s3_api")
                .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                                .setAddress("0.0.0.0")
                                .setPortValue(XdsConfig.MINIO_S3_API_PORT)
                                .build())
                        .build())
                .addFilterChains(FilterChain.newBuilder()
                        .addFilters(Filter.newBuilder()
                                .setName("envoy.filters.network.http_connection_manager")
                                .setTypedConfig(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                        .setValue(hcm.toByteString())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VIRTUAL HOSTS & ROUTES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates all virtual hosts for the HTTPS gateway.
     * Controller virtual hosts (controller1_host, controller2_host) are removed —
     * individual controllers register their domains dynamically via /api/controllers/register.
     * Only the controller LB virtual host (controller.one211.com) remains as static.
     */
    private List<VirtualHost> generateVirtualHosts() {
        List<VirtualHost> virtualHosts = new ArrayList<>();

        String corsOrigin = XdsConfig.CORS_ALLOWED_ORIGIN;

        // === Static Virtual Hosts ===

        // Backend: backend.one211.com → application-backend:8080
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("backend_host")
                .addDomains("backend.one211.com")
                .addDomains("backend.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("backend_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("backend.one211.com")
                                .build())
                        .build())
                .build());

        // Controller Load Balancer: controller.one211.com → Round Robin
        virtualHosts.add(buildCorsVirtualHost(
                "controller_lb_host",
                List.of("controller.one211.com", "controller.one211.com:*"),
                "sql_controller_lb_http",
                XdsConfig.TIMEOUT_API_LONG,
                corsOrigin));

        // MinIO Console: minio.one211.com → minio:9001
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("minio_console_host")
                .addDomains("minio.one211.com")
                .addDomains("minio.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("minio_console_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("minio.one211.com")
                                .build())
                        .build())
                .build());

        // OllyLake: ollylake.one211.com → ollylake:8081
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("ollylake_host")
                .addDomains("ollylake.one211.com")
                .addDomains("ollylake.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("ollylake_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_API_SHORT).build())
                                .setHostRewriteLiteral("ollylake.one211.com")
                                .build())
                        .build())
                .build());

        // === Dynamic Virtual Hosts (sorted by priority) ===
        List<RouteConfig> sortedRoutes = dynamicRoutes.values().stream()
                .sorted(Comparator.comparingInt(RouteConfig::getPriority))
                .collect(Collectors.toList());

        for (RouteConfig route : sortedRoutes) {
            VirtualHost.Builder vhBuilder = VirtualHost.newBuilder()
                    .setName("dynamic_" + route.getDomain()
                            .replace(".", "_").replace("*", "wildcard"))
                    .addDomains(route.getDomain())
                    .addDomains(route.getDomain() + ":*");

            vhBuilder.addRoutes(Route.newBuilder()
                    .setMatch(RouteMatch.newBuilder()
                            .setPrefix(route.getPrefix()).build())
                    .setRoute(RouteAction.newBuilder()
                            .setCluster(route.getCluster())
                            .setTimeout(Duration.newBuilder()
                                    .setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                            .build())
                    .build());

            virtualHosts.add(vhBuilder.build());
            logger.info("Added dynamic route: {} -> {} (priority: {})",
                    route.getDomain(), route.getCluster(), route.getPriority());
        }

        // Frontend (catch-all) — lowest priority
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("frontend_host")
                .addDomains("frontend.one211.com")
                .addDomains("frontend.one211.com:*")
                .addDomains("www.one211.com")
                .addDomains("www.one211.com:*")
                .addDomains("localhost")
                .addDomains("localhost:*")
                .addDomains("*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix("/")
                                .addHeaders(HeaderMatcher.newBuilder()
                                        .setName("upgrade")
                                        .setPresentMatch(true)
                                        .build())
                                .build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("frontend_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_IMMEDIATE).build())
                                .build())
                        .build())
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix("/api/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("backend_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                                .build())
                        .build())
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("frontend_http")
                                .setTimeout(Duration.newBuilder()
                                        .setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("localhost:5173")
                                .build())
                        .build())
                .build());

        return virtualHosts;
    }

    /**
     * Builds a virtual host with CORS preflight handling
     */
    private VirtualHost buildCorsVirtualHost(String name, List<String> domains,
                                              String cluster, int timeoutSec,
                                              String corsOrigin) {
        VirtualHost.Builder vhBuilder = VirtualHost.newBuilder()
                .setName(name);

        for (String domain : domains) {
            vhBuilder.addDomains(domain);
        }

        // OPTIONS preflight route
        vhBuilder.addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder()
                        .setPrefix("/")
                        .addHeaders(HeaderMatcher.newBuilder()
                                .setName(":method")
                                .setStringMatch(StringMatcher.newBuilder()
                                        .setExact("OPTIONS").build())
                                .build())
                        .build())
                .setDirectResponse(DirectResponseAction.newBuilder()
                        .setStatus(204).build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Origin")
                                .setValue(corsOrigin).build())
                        .build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Methods")
                                .setValue("GET, POST, PUT, DELETE, OPTIONS, PATCH")
                                .build())
                        .build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Headers")
                                .setValue("Authorization, Content-Type").build())
                        .build())
                .build());

        // Main route with CORS response headers
        vhBuilder.addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                .setRoute(RouteAction.newBuilder()
                        .setCluster(cluster)
                        .setTimeout(Duration.newBuilder()
                                .setSeconds(timeoutSec).build())
                        .build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setAppendAction(HeaderValueOption.HeaderAppendAction
                                .OVERWRITE_IF_EXISTS_OR_ADD)
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Origin")
                                .setValue(corsOrigin).build())
                        .build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setAppendAction(HeaderValueOption.HeaderAppendAction
                                .OVERWRITE_IF_EXISTS_OR_ADD)
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Methods")
                                .setValue("GET, POST, PUT, DELETE, OPTIONS, PATCH")
                                .build())
                        .build())
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setAppendAction(HeaderValueOption.HeaderAppendAction
                                .OVERWRITE_IF_EXISTS_OR_ADD)
                        .setHeader(HeaderValue.newBuilder()
                                .setKey("Access-Control-Allow-Headers")
                                .setValue("Authorization, Content-Type").build())
                        .build())
                .build());

        return vhBuilder.build();
    }

    /**
     * Generates route configurations (used by RDS)
     */
    private List<RouteConfiguration> generateRoutes() {
        return List.of(
                RouteConfiguration.newBuilder()
                        .setName("local_route")
                        .addAllVirtualHosts(generateVirtualHosts())
                        .build(),
                RouteConfiguration.newBuilder()
                        .setName("minio_api_route")
                        .addVirtualHosts(VirtualHost.newBuilder()
                                .setName("minio_api")
                                .addDomains("*")
                                .addRoutes(Route.newBuilder()
                                        .setMatch(RouteMatch.newBuilder()
                                                .setPrefix("/").build())
                                        .setRoute(RouteAction.newBuilder()
                                                .setCluster("minio_api_http")
                                                .setTimeout(Duration.newBuilder()
                                                        .setSeconds(XdsConfig.TIMEOUT_API_LONG)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLUSTERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates all cluster configurations.
     * Controller clusters are dynamic — no static controller1/controller2 clusters.
     */
    private List<Cluster> generateClusters() {
        List<Cluster> clusters = new ArrayList<>();

        // HTTP Service Clusters (static infrastructure)
        clusters.add(createHttpCluster("backend_http",
                XdsConfig.HOST_BACKEND, XdsConfig.SERVICE_PORT_BACKEND));
        clusters.add(createHttpCluster("frontend_http",
                XdsConfig.HOST_FRONTEND, XdsConfig.SERVICE_PORT_FRONTEND));
        clusters.add(createHttpCluster("minio_api_http",
                XdsConfig.HOST_MINIO, XdsConfig.SERVICE_PORT_MINIO_API));
        clusters.add(createHttpCluster("minio_console_http",
                XdsConfig.HOST_MINIO, XdsConfig.SERVICE_PORT_MINIO_CONSOLE));
        clusters.add(createHttpCluster("ollylake_http",
                XdsConfig.HOST_OLLYLAKE, XdsConfig.SERVICE_PORT_OLLYLAKE_HTTP));

        // Controller HTTP Load Balancer — endpoints populated entirely from ServiceRegistry
        clusters.add(createLbCluster());

        // Controller Flight cluster — endpoints populated entirely from ServiceRegistry
        clusters.add(createControllerFlightCluster());

        // OllyLake Flight cluster (static — single instance)
        clusters.add(createStaticCluster("ollylake_flight_cluster",
                XdsConfig.HOST_OLLYLAKE, XdsConfig.SERVICE_PORT_OLLYLAKE_FLIGHT));

        // Database TCP Clusters
        clusters.add(createStaticCluster("backend_database_tcp",
                XdsConfig.HOST_BACKEND_DATABASE, XdsConfig.SERVICE_PORT_DATABASE));
        clusters.add(createStaticCluster("cluster_database_tcp",
                XdsConfig.HOST_CLUSTER_DATABASE, XdsConfig.SERVICE_PORT_DATABASE));

        return clusters;
    }

    /**
     * Creates an HTTP service cluster with STRICT_DNS and inline endpoints
     */
    private Cluster createHttpCluster(String name, String address, int port) {
        return Cluster.newBuilder()
                .setName(name)
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder()
                        .setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                        .setClusterName(name)
                        .addEndpoints(LocalityLbEndpoints.newBuilder()
                                .addLbEndpoints(LbEndpoint.newBuilder()
                                        .setEndpoint(Endpoint.newBuilder()
                                                .setAddress(Address.newBuilder()
                                                        .setSocketAddress(SocketAddress.newBuilder()
                                                                .setAddress(address)
                                                                .setPortValue(port)
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Creates a static cluster (TCP/Flight/Database) with STRICT_DNS
     */
    private Cluster createStaticCluster(String name, String address, int port) {
        return Cluster.newBuilder()
                .setName(name)
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder()
                        .setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                        .setClusterName(name)
                        .addEndpoints(LocalityLbEndpoints.newBuilder()
                                .addLbEndpoints(LbEndpoint.newBuilder()
                                        .setEndpoint(Endpoint.newBuilder()
                                                .setAddress(Address.newBuilder()
                                                        .setSocketAddress(SocketAddress.newBuilder()
                                                                .setAddress(address)
                                                                .setPortValue(port)
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Creates the controller HTTP load balancer cluster.
     * Endpoints come entirely from the ServiceRegistry (dynamicEndpoints).
     * No static controller endpoints are hardcoded.
     */
    private Cluster createLbCluster() {
        LocalityLbEndpoints.Builder localityBuilder = LocalityLbEndpoints.newBuilder();

        // Endpoints populated entirely from dynamic registrations
        List<EndpointConfig> dynamicLbEndpoints =
                dynamicEndpoints.get("sql_controller_lb_http");
        if (dynamicLbEndpoints != null) {
            for (EndpointConfig ep : dynamicLbEndpoints) {
                localityBuilder.addLbEndpoints(LbEndpoint.newBuilder()
                        .setEndpoint(Endpoint.newBuilder()
                                .setAddress(Address.newBuilder()
                                        .setSocketAddress(SocketAddress.newBuilder()
                                                .setAddress(ep.getHost())
                                                .setPortValue(ep.getPort())
                                                .build())
                                        .build())
                                .build())
                        .build());
            }
        }

        return Cluster.newBuilder()
                .setName("sql_controller_lb_http")
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder()
                        .setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                        .setClusterName("sql_controller_lb_http")
                        .addEndpoints(localityBuilder.build())
                        .build())
                .build();
    }

    /**
     * Creates the unified controller flight cluster.
     * Endpoints come entirely from the ServiceRegistry (dynamicEndpoints).
     * No static controller endpoints are hardcoded.
     */
    private Cluster createControllerFlightCluster() {
        LocalityLbEndpoints.Builder localityBuilder = LocalityLbEndpoints.newBuilder();

        List<EndpointConfig> dynamicFlightEndpoints =
                dynamicEndpoints.get("controller_flight_cluster");
        if (dynamicFlightEndpoints != null) {
            for (EndpointConfig ep : dynamicFlightEndpoints) {
                localityBuilder.addLbEndpoints(LbEndpoint.newBuilder()
                        .setEndpoint(Endpoint.newBuilder()
                                .setAddress(Address.newBuilder()
                                        .setSocketAddress(SocketAddress.newBuilder()
                                                .setAddress(ep.getHost())
                                                .setPortValue(ep.getPort())
                                                .build())
                                        .build())
                                .build())
                        .build());
            }
        }

        return Cluster.newBuilder()
                .setName("controller_flight_cluster")
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder()
                        .setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                        .setClusterName("controller_flight_cluster")
                        .addEndpoints(localityBuilder.build())
                        .build())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates endpoint configurations for dynamically registered services.
     * Controller endpoints (sql_controller_lb_http, controller_flight_cluster)
     * are handled inline in their cluster builders — this method handles
     * all other dynamic endpoints.
     */
    private List<ClusterLoadAssignment> generateEndpoints() {
        List<ClusterLoadAssignment> endpoints = new ArrayList<>();

        // Add dynamic endpoints (excluding controller clusters which are
        // handled inline in createLbCluster and createControllerFlightCluster)
        for (Map.Entry<String, List<EndpointConfig>> entry : dynamicEndpoints.entrySet()) {
            String clusterName = entry.getKey();
            if ("sql_controller_lb_http".equals(clusterName)
                    || "controller_flight_cluster".equals(clusterName)) {
                continue; // handled in cluster builders
            }

            List<EndpointConfig> endpointConfigs = entry.getValue();

            ClusterLoadAssignment.Builder clusterAssignment =
                    ClusterLoadAssignment.newBuilder().setClusterName(clusterName);

            LocalityLbEndpoints.Builder localityLbEndpoints =
                    LocalityLbEndpoints.newBuilder();
            for (EndpointConfig endpointConfig : endpointConfigs) {
                localityLbEndpoints.addLbEndpoints(LbEndpoint.newBuilder()
                        .setEndpoint(Endpoint.newBuilder()
                                .setAddress(Address.newBuilder()
                                        .setSocketAddress(SocketAddress.newBuilder()
                                                .setAddress(endpointConfig.getHost())
                                                .setPortValue(endpointConfig.getPort())
                                                .build())
                                        .build())
                                .build())
                        .build());
            }

            clusterAssignment.addEndpoints(localityLbEndpoints.build());
            endpoints.add(clusterAssignment.build());

            logger.debug("Added dynamic endpoint for cluster {} with {} endpoints",
                    clusterName, endpointConfigs.size());
        }

        return endpoints;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIG FILE WATCHING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Checks if configuration files have changed
     */
    public boolean hasConfigurationChanged() {
        Path configDir = Paths.get("/config");
        if (!Files.exists(configDir)) {
            return false;
        }

        try {
            AtomicBoolean changed = new AtomicBoolean(false);
            Files.list(configDir)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(p -> {
                        try {
                            FileTime current = Files.getLastModifiedTime(p);
                            FileTime previous = fileTimestamps.get(p.toString());
                            if (previous == null || !current.equals(previous)) {
                                changed.set(true);
                                fileTimestamps.put(p.toString(), current);
                                logger.debug("Configuration file changed: {}", p);
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to check file timestamp: {}", p, e);
                        }
                    });
            return changed.get();
        } catch (IOException e) {
            logger.warn("Failed to list configuration files", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DYNAMIC CLUSTER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Adds a new cluster dynamically
     */
    public void addCluster(String clusterConfig) throws IOException {
        logger.info("Adding new cluster: {}", clusterConfig);

        try {
            try {
                AddClusterRequest request = jsonMapper.readValue(
                        clusterConfig, AddClusterRequest.class);
                logger.info("Parsed cluster request: name={}, type={}, address={}, port={}",
                        request.name(), request.type(), request.address(), request.port());

                EndpointConfig endpoint = new EndpointConfig(
                        request.name(), request.address(), request.port());
                dynamicEndpoints.put(request.name(), List.of(endpoint));

                logger.info("Added cluster {} with endpoint {}:{}",
                        request.name(), request.address(), request.port());
            } catch (Exception e) {
                SimpleClusterRequest request = jsonMapper.readValue(
                        clusterConfig, SimpleClusterRequest.class);
                logger.info("Parsed simple cluster request: name={}",
                        request.cluster_name());

                if (request.endpoints() != null) {
                    List<EndpointConfig> endpoints = request.endpoints().stream()
                            .map(ep -> {
                                if (ep instanceof String) {
                                    String[] parts = ((String) ep).split(":");
                                    if (parts.length != 2) {
                                        throw new IllegalArgumentException(
                                                "Invalid endpoint format: " + ep);
                                    }
                                    return new EndpointConfig(request.cluster_name(),
                                            parts[0], Integer.parseInt(parts[1]));
                                }
                                @SuppressWarnings("unchecked")
                                Map<String, Object> epMap = (Map<String, Object>) ep;
                                String host = (String) epMap.get("host");
                                Integer port = (Integer) epMap.get("port");
                                return new EndpointConfig(
                                        request.cluster_name(), host, port);
                            })
                            .collect(Collectors.toList());

                    dynamicEndpoints.put(request.cluster_name(), endpoints);
                    logger.info("Added {} endpoints for cluster {}",
                            endpoints.size(), request.cluster_name());
                }
            }

            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to add cluster", e);
            throw new IOException(
                    "Failed to parse cluster configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Updates endpoints dynamically for an existing cluster
     */
    public void updateEndpoints(String endpointConfig) throws IOException {
        logger.info("Updating endpoints: {}", endpointConfig);

        try {
            try {
                EndpointUpdateRequest request = jsonMapper.readValue(
                        endpointConfig, EndpointUpdateRequest.class);
                logger.info("Parsed endpoint update request: clusterName={}",
                        request.clusterName());

                List<EndpointConfig> endpoints = request.endpoints().stream()
                        .map(ep -> new EndpointConfig(
                                request.clusterName(), ep.host(), ep.port()))
                        .collect(Collectors.toList());

                dynamicEndpoints.put(request.clusterName(), endpoints);
                logger.info("Updated {} endpoints for cluster {}",
                        endpoints.size(), request.clusterName());
            } catch (Exception e) {
                SimpleClusterRequest request = jsonMapper.readValue(
                        endpointConfig, SimpleClusterRequest.class);
                logger.info("Parsed simple cluster request: name={}",
                        request.cluster_name());

                List<EndpointConfig> endpoints = request.endpoints().stream()
                        .map(ep -> {
                            if (ep instanceof String) {
                                String[] parts = ((String) ep).split(":");
                                if (parts.length != 2) {
                                    throw new IllegalArgumentException(
                                            "Invalid endpoint format: " + ep);
                                }
                                return new EndpointConfig(request.cluster_name(),
                                        parts[0], Integer.parseInt(parts[1]));
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> epMap = (Map<String, Object>) ep;
                            String host = (String) epMap.get("host");
                            Integer port = (Integer) epMap.get("port");
                            return new EndpointConfig(
                                    request.cluster_name(), host, port);
                        })
                        .collect(Collectors.toList());

                dynamicEndpoints.put(request.cluster_name(), endpoints);
                logger.info("Updated {} endpoints for cluster {}",
                        endpoints.size(), request.cluster_name());
            }

            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to update endpoints", e);
            throw new IOException(
                    "Failed to parse endpoint configuration: " + e.getMessage(), e);
        }
    }

    private void updateConfigurationTimestamp() {
        Path configDir = Paths.get("/config");
        if (Files.exists(configDir)) {
            try {
                Path markerFile = configDir.resolve(".update-marker");
                Files.writeString(markerFile,
                        String.valueOf(System.currentTimeMillis()));
                Files.setLastModifiedTime(markerFile,
                        FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException e) {
                logger.warn("Failed to update configuration marker file", e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTROLLER REGISTRATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registers a new controller with both HTTP and Flight endpoints
     */
    public void registerController(String controllerConfig) throws IOException {
        logger.info("Registering controller: {}", controllerConfig);

        try {
            ControllerRegistrationRequest request = jsonMapper.readValue(
                    controllerConfig, ControllerRegistrationRequest.class);
            logger.info("Parsed controller registration: domain={}, host={}, "
                            + "httpPort={}, flightPort={}",
                    request.domain(), request.host(),
                    request.httpPort(), request.flightPort());

            if (request.domain() == null || request.domain().trim().isEmpty()) {
                throw new IllegalArgumentException("Domain is required");
            }
            if (request.host() == null || request.host().trim().isEmpty()) {
                throw new IllegalArgumentException("Host is required");
            }

            String domain = request.domain().trim();
            String host = request.host().trim();
            int httpPort = request.httpPort() != null ? request.httpPort() : 9006;
            int flightPort = request.flightPort() != null
                    ? request.flightPort() : 59307;

            // 1. Add route for this controller domain
            addRouteForController(domain);
            logger.info("Added route for domain: {}", domain);

            // 2. Add HTTP endpoint to controller LB cluster
            addEndpointToCluster("sql_controller_lb_http", host, httpPort);
            logger.info("Added HTTP endpoint for {}:{} to sql_controller_lb_http",
                    host, httpPort);

            // 3. Add Flight endpoint to controller flight cluster
            addEndpointToCluster("controller_flight_cluster", host, flightPort);
            logger.info("Added Flight endpoint for {}:{} to controller_flight_cluster",
                    host, flightPort);

            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to register controller", e);
            throw new IOException(
                    "Failed to register controller: " + e.getMessage(), e);
        }
    }

    private void addRouteForController(String domain) throws IOException {
        RouteConfig config = new RouteConfig(
                domain,
                "sql_controller_lb_http",
                "/",
                50
        );
        dynamicRoutes.put(domain, config);
    }

    private void addEndpointToCluster(String clusterName, String host, int port) {
        List<EndpointConfig> endpoints = dynamicEndpoints.getOrDefault(
                clusterName, new ArrayList<>());

        boolean found = false;
        List<EndpointConfig> updated = new ArrayList<>();
        for (EndpointConfig ep : endpoints) {
            if (ep.getHost().equals(host)) {
                updated.add(new EndpointConfig(clusterName, host, port));
                found = true;
            } else {
                updated.add(ep);
            }
        }

        if (!found) {
            updated.add(new EndpointConfig(clusterName, host, port));
        }

        dynamicEndpoints.put(clusterName, updated);
    }

    /**
     * Deregisters a controller
     */
    public void deregisterController(String domain, String host) {
        logger.info("Deregistering controller: domain={}, host={}", domain, host);

        RouteConfig removed = dynamicRoutes.remove(domain);
        if (removed != null) {
            logger.info("Removed route for domain: {}", domain);
        }

        removeEndpointFromCluster("sql_controller_lb_http", host);
        removeEndpointFromCluster("controller_flight_cluster", host);

        updateConfigurationTimestamp();
        logger.info("Deregistered controller: domain={}, host={}", domain, host);
    }

    private void removeEndpointFromCluster(String clusterName, String host) {
        List<EndpointConfig> endpoints = dynamicEndpoints.get(clusterName);
        if (endpoints != null) {
            List<EndpointConfig> filtered = endpoints.stream()
                    .filter(ep -> !ep.getHost().equals(host))
                    .collect(Collectors.toList());

            if (filtered.isEmpty()) {
                dynamicEndpoints.remove(clusterName);
            } else {
                dynamicEndpoints.put(clusterName, filtered);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROUTE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    private void initializeRouteTemplates() {
        routeTemplates.put("controller", new RouteTemplate(
                "controller*.one211.com",
                "sql_controller_lb_http",
                "/",
                50
        ));

        routeTemplates.put("wildcard", new RouteTemplate(
                "*.one211.com",
                "sql_controller_lb_http",
                "/",
                1000
        ));

        routeTemplates.put("xyz_pattern", new RouteTemplate(
                "xyz_*.one211.com",
                "sql_controller_lb_http",
                "/",
                100
        ));

        logger.info("Initialized {} route templates", routeTemplates.size());
    }

    public void addRoute(String routeConfig) throws IOException {
        updateRoute(routeConfig);
    }

    public void updateRoute(String routeConfig) throws IOException {
        logger.info("Adding/updating route: {}", routeConfig);

        try {
            EnhancedRouteRequest request = jsonMapper.readValue(
                    routeConfig, EnhancedRouteRequest.class);
            logger.info("Parsed route request: domain={}, cluster={}, "
                            + "prefix={}, priority={}",
                    request.domain(), request.cluster(),
                    request.prefix(), request.priority());

            if (request.domain() == null || request.domain().trim().isEmpty()) {
                throw new IllegalArgumentException("Domain is required");
            }
            if (request.cluster() == null || request.cluster().trim().isEmpty()) {
                throw new IllegalArgumentException("Cluster is required");
            }

            String domain = request.domain().trim();
            String cluster = request.cluster().trim();

            String prefix = request.prefix();
            if (prefix == null || prefix.trim().isEmpty()) {
                prefix = "/";
            } else {
                prefix = prefix.trim();
                if (!prefix.startsWith("/")) {
                    throw new IllegalArgumentException(
                            "Prefix must start with '/'");
                }
            }

            if (!isValidDomainPattern(domain)) {
                throw new IllegalArgumentException(
                        "Invalid domain pattern: " + domain);
            }

            RouteConfig config = new RouteConfig(
                    domain, cluster, prefix, request.priority());

            dynamicRoutes.put(domain, config);
            logger.info("Added/updated route {} -> {} (priority: {})",
                    domain, cluster, request.priority());

            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to add/update route", e);
            throw new IOException(
                    "Failed to parse route configuration: " + e.getMessage(), e);
        }
    }

    public void deleteRoute(String domainPattern) {
        logger.info("Deleting route for domain: {}", domainPattern);

        RouteConfig removed = dynamicRoutes.remove(domainPattern);
        if (removed != null) {
            logger.info("Deleted route: {} -> {}",
                    domainPattern, removed.getCluster());
            updateConfigurationTimestamp();
        } else {
            logger.warn("No route found for domain pattern: {}", domainPattern);
        }
    }

    public String listRoutes() throws IOException {
        List<Map<String, Object>> routes = new ArrayList<>();

        for (RouteConfig route : dynamicRoutes.values()) {
            Map<String, Object> routeInfo = new HashMap<>();
            routeInfo.put("domain", route.getDomain());
            routeInfo.put("cluster", route.getCluster());
            routeInfo.put("prefix", route.getPrefix());
            routeInfo.put("priority", route.getPriority());
            routes.add(routeInfo);
        }

        routes.sort(Comparator.comparingInt(r -> (Integer) r.get("priority")));

        Map<String, Object> response = new HashMap<>();
        response.put("routes", routes);
        response.put("count", routes.size());

        return jsonMapper.writeValueAsString(response);
    }

    public void addRouteFromTemplate(String templateName, String clusterOverride)
            throws IOException {
        RouteTemplate template = routeTemplates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException(
                    "Template not found: " + templateName);
        }

        String domain = template.domain();
        String cluster = clusterOverride != null
                ? clusterOverride : template.cluster();

        RouteConfig config = new RouteConfig(
                domain, cluster, template.prefix(), template.priority());

        dynamicRoutes.put(domain, config);
        logger.info("Created route from template {}: {} -> {} (priority: {})",
                templateName, domain, cluster, template.priority());

        updateConfigurationTimestamp();
    }

    private boolean isValidDomainPattern(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        return domain.matches("^[a-zA-Z0-9._*:-]+$");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER CLASSES / RECORDS
    // ═══════════════════════════════════════════════════════════════════════

    private record ControllerRegistrationRequest(
            String domain, String host, Integer httpPort, Integer flightPort) {
    }

    private record AddClusterRequest(
            String name, String type, String address, int port) {
    }

    private record EndpointUpdateRequest(
            String clusterName, List<EndpointData> endpoints) {
    }

    private record EndpointData(String host, int port) {
    }

    private record SimpleClusterRequest(
            String cluster_name, List<Object> endpoints) {
    }

    private record EnhancedRouteRequest(
            String domain, String cluster, String prefix, int priority) {
        public EnhancedRouteRequest(String domain, String cluster,
                                     String prefix, int priority) {
            this.domain = domain;
            this.cluster = cluster;
            this.prefix = prefix != null ? prefix : "/";
            this.priority = priority > 0 ? priority : 100;
        }
    }

    public static class EndpointConfig {
        private final String clusterName;
        private final String host;
        private final int port;

        public EndpointConfig(String clusterName, String host, int port) {
            this.clusterName = clusterName;
            this.host = host;
            this.port = port;
        }

        public String getClusterName() { return clusterName; }
        public String getHost() { return host; }
        public int getPort() { return port; }
    }

    public static class RouteConfig {
        private final String domain;
        private final String cluster;
        private final String prefix;
        private final int priority;

        public RouteConfig(String domain, String cluster,
                           String prefix, int priority) {
            this.domain = domain;
            this.cluster = cluster;
            this.prefix = prefix;
            this.priority = priority;
        }

        public String getDomain() { return domain; }
        public String getCluster() { return cluster; }
        public String getPrefix() { return prefix; }
        public int getPriority() { return priority; }
    }

    public static class RouteTemplate {
        private final String domain;
        private final String cluster;
        private final String prefix;
        private final int priority;

        public RouteTemplate(String domain, String cluster,
                             String prefix, int priority) {
            this.domain = domain;
            this.cluster = cluster;
            this.prefix = prefix;
            this.priority = priority;
        }

        public String domain() { return domain; }
        public String cluster() { return cluster; }
        public String prefix() { return prefix; }
        public int priority() { return priority; }
    }
}
