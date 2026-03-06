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
import io.envoyproxy.envoy.type.v3.*;
import com.google.protobuf.Duration;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages xDS configuration generation and updates
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
    public io.envoyproxy.controlplane.cache.v3.Snapshot generateInitialSnapshot() {
        try {
            List<Listener> listeners = generateListeners();
            List<RouteConfiguration> routes = generateRoutes();
            List<Cluster> clusters = generateClusters();
            List<ClusterLoadAssignment> endpoints = generateEndpoints();

            // Increment snapshot version on each generation
            snapshotVersionCounter.incrementAndGet();
            String currentVersion = VERSION + "-v" + snapshotVersionCounter.get();

            return io.envoyproxy.controlplane.cache.v3.Snapshot.create(
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

    /**
     * Generates all listeners for the configuration
     */
    private List<Listener> generateListeners() {
        List<Listener> listeners = new ArrayList<>();

        // HTTPS Gateway Listener
        listeners.add(Listener.newBuilder()
                .setName("https_gateway")
                .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                                .setAddress("0.0.0.0")
                                .setPortValue(XdsConfig.HTTPS_GATEWAY_PORT)
                                .build())
                        .build())
                .addFilterChains(FilterChain.newBuilder()
                        .addFilters(Filter.newBuilder()
                                .setName("envoy.filters.network.http_connection_manager")
                                .setTypedConfig(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                        .setValue(HttpConnectionManager.newBuilder()
                                                .setStatPrefix("ingress_https")
                                                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                                                .setRouteConfig(RouteConfiguration.newBuilder()
                                                        .setName("local_route")
                                                        .addAllVirtualHosts(generateVirtualHosts())
                                                        .build())
                                                .addHttpFilters(HttpFilter.newBuilder()
                                                        .setName("envoy.filters.http.router")
                                                        .setTypedConfig(Any.newBuilder()
                                                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.router.v3.Router")
                                                                .build())
                                                        .build())
                                                .build()
                                                        .toByteString())
                                        .build())
                                .build())
                        .build())
                .build());

        // TCP Listeners for Arrow Flight SQL
        listeners.add(generateTcpListener("flight_listener_controllers", XdsConfig.FLIGHT_PORT_CONTROLLERS, "controller_flight_cluster"));
        listeners.add(generateTcpListener("flight_listener_ollylake", XdsConfig.FLIGHT_PORT_OLLYLAKE, "ollylake_flight_cluster"));

        // TCP Listeners for PostgreSQL
        listeners.add(generateTcpListener("tcp_backend_database", XdsConfig.POSTGRES_PORT_BACKEND, "backend_database_tcp"));
        listeners.add(generateTcpListener("tcp_cluster_database", XdsConfig.POSTGRES_PORT_CLUSTER, "cluster_database_tcp"));

        // HTTP Listener for MinIO S3 API
        listeners.add(Listener.newBuilder()
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
                                        .setValue(HttpConnectionManager.newBuilder()
                                                .setStatPrefix("minio_s3_api")
                                                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                                                .setRouteConfig(RouteConfiguration.newBuilder()
                                                        .setName("minio_api_route")
                                                        .addVirtualHosts(VirtualHost.newBuilder()
                                                                .setName("minio_api")
                                                                .addDomains("*")
                                                                .addRoutes(Route.newBuilder()
                                                                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                                                                        .setRoute(RouteAction.newBuilder()
                                                                                .setCluster("minio_api_http")
                                                                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
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
                                                .build()
                                                        .toByteString())
                                        .build())
                                .build())
                        .build())
                .build());

        return listeners;
    }

    private Listener generateTcpListener(String name, int port, String cluster) {
        io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy tcpProxy = io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy.newBuilder()
                .setStatPrefix(name)
                .setCluster(cluster)
                .setIdleTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_TCP_IDLE).build())
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
                                        .setTypeUrl("type.googleapis.com/io.envoyproxy.envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy")
                                        .setValue(tcpProxy.toByteString())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Generates all virtual hosts for the HTTPS gateway
     */
    private List<VirtualHost> generateVirtualHosts() {
        List<VirtualHost> virtualHosts = new ArrayList<>();

        // === Static Virtual Hosts (Highest Priority - Most Specific) ===

        // Backend host
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("backend_host")
                .addDomains("backend.one211.com")
                .addDomains("backend.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("backend_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("backend.one211.com")
                                .build())
                        .build())
                .build());

        // Controller load balancer
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("controller_lb_host")
                .addDomains("controller.one211.com")
                .addDomains("controller.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix("/")
                                .addHeaders(HeaderMatcher.newBuilder()
                                        .setName(":method")
                                        .setStringMatch(StringMatcher.newBuilder().setExact("OPTIONS").build())
                                        .build())
                                .build())
                        .setDirectResponse(DirectResponseAction.newBuilder().setStatus(204).build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Origin").setValue("https://dazzleduck-ui.netlify.app").build())
                                .build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Methods").setValue("GET, POST, PUT, DELETE, OPTIONS, PATCH").build())
                                .build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Headers").setValue("Authorization, Content-Type").build())
                                .build())
                        .build())
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("sql_controller_lb_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                                .build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Origin").setValue("https://dazzleduck-ui.netlify.app").build())
                                .build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Methods").setValue("GET, POST, PUT, DELETE, OPTIONS, PATCH").build())
                                .build())
                        .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                                .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                                .setHeader(HeaderValue.newBuilder().setKey("Access-Control-Allow-Headers").setValue("Authorization, Content-Type").build())
                                .build())
                        .build())
                .build());

        // Controller routes are now managed dynamically via ServiceRegistry
        // Use the /api/routes endpoints to add controller routes at runtime

        // MinIO console host
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("minio_console_host")
                .addDomains("minio.one211.com")
                .addDomains("minio.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("minio_console_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("minio.one211.com")
                                .build())
                        .build())
                .build());

        // OllyLake host
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("ollylake_host")
                .addDomains("ollylake.one211.com")
                .addDomains("ollylake.one211.com:*")
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("ollylake_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_SHORT).build())
                                .setHostRewriteLiteral("ollylake.one211.com")
                                .build())
                        .build())
                .build());

        // === Dynamic Virtual Hosts (Sorted by Priority) ===
        // Lower priority number = matched first
        List<RouteConfig> sortedRoutes = dynamicRoutes.values().stream()
                .sorted(Comparator.comparingInt(RouteConfig::getPriority))
                .collect(Collectors.toList());

        for (RouteConfig route : sortedRoutes) {
            VirtualHost.Builder vhBuilder = VirtualHost.newBuilder()
                    .setName("dynamic_" + route.getDomain().replace(".", "_").replace("*", "wildcard"))
                    .addDomains(route.getDomain())
                    .addDomains(route.getDomain() + ":*");

            // Add the route
            vhBuilder.addRoutes(Route.newBuilder()
                    .setMatch(RouteMatch.newBuilder().setPrefix(route.getPrefix()).build())
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

        // Frontend host (catch-all) - Keep at the end for lowest priority
        virtualHosts.add(VirtualHost.newBuilder()
                .setName("frontend_host")
                .addDomains("frontend.one211.com")
                .addDomains("frontend.one211.com:*")
                .addDomains("www.one211.com")
                .addDomains("www.one211.com:*")
                .addDomains("localhost")
                .addDomains("localhost:*")
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
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_IMMEDIATE).build())
                                .build())
                        .build())
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/api/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("backend_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                                .build())
                        .build())
                .addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder()
                                .setCluster("frontend_http")
                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_STANDARD).build())
                                .setHostRewriteLiteral("localhost:5173")
                                .build())
                        .build())
                .build());

        return virtualHosts;
    }

    /**
     * Generates route configurations
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
                                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                                        .setRoute(RouteAction.newBuilder()
                                                .setCluster("minio_api_http")
                                                .setTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_API_LONG).build())
                                                .build())
                                        .build())
                                .build())
                        .build()
        );
    }

    /**
     * Generates all cluster configurations
     */
    private List<Cluster> generateClusters() {
        List<Cluster> clusters = new ArrayList<>();

        // Backend HTTP
        clusters.add(createHttpCluster("backend_http", "application-backend", XdsConfig.SERVICE_PORT_BACKEND));
        // Frontend HTTP
        clusters.add(createHttpCluster("frontend_http", "frontend", XdsConfig.SERVICE_PORT_FRONTEND));
        // MinIO S3 API
        clusters.add(createHttpCluster("minio_api_http", "minio", XdsConfig.SERVICE_PORT_MINIO_API));
        // MinIO Console
        clusters.add(createHttpCluster("minio_console_http", "minio", XdsConfig.SERVICE_PORT_MINIO_CONSOLE));
        // OllyLake HTTP
        clusters.add(createHttpCluster("ollylake_http", "ollylake", XdsConfig.SERVICE_PORT_OLLYLAKE_HTTP));

        // Controller Load Balancer - endpoints populated from ServiceRegistry
        clusters.add(Cluster.newBuilder()
                .setName("sql_controller_lb_http")
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .build());

        clusters.add(createTcpCluster("controller_flight_cluster"));
        clusters.add(createTcpCluster("ollylake_flight_cluster"));
        clusters.add(createTcpCluster("backend_database_tcp"));
        clusters.add(createTcpCluster("cluster_database_tcp"));

        return clusters;
    }

    private Cluster createHttpCluster(String name, String address, int port) {
        return Cluster.newBuilder()
                .setName(name)
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
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

    private Cluster createTcpCluster(String name) {
        return Cluster.newBuilder()
                .setName(name)
                .setType(Cluster.DiscoveryType.EDS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setConnectTimeout(Duration.newBuilder().setSeconds(XdsConfig.TIMEOUT_TCP_CONNECT).build())
                .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                        .setEdsConfig(ConfigSource.newBuilder()
                                .setApiConfigSource(ApiConfigSource.newBuilder()
                                        .setApiType(ApiConfigSource.ApiType.GRPC)
                                        .addGrpcServices(GrpcService.newBuilder()
                                                .setEnvoyGrpc(GrpcService.EnvoyGrpc.newBuilder()
                                                        .setClusterName("xds_cluster")
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Generates endpoint configurations
     */
    private List<ClusterLoadAssignment> generateEndpoints() {
        List<ClusterLoadAssignment> endpoints = new ArrayList<>();
        // Controller endpoints are populated from ServiceRegistry via dynamicEndpoints

        // Database Endpoints
        endpoints.add(ClusterLoadAssignment.newBuilder()
                .setClusterName("backend_database_tcp")
                .addEndpoints(LocalityLbEndpoints.newBuilder()
                        .addLbEndpoints(LbEndpoint.newBuilder()
                                .setEndpoint(Endpoint.newBuilder()
                                        .setAddress(Address.newBuilder()
                                                .setSocketAddress(SocketAddress.newBuilder()
                                                        .setAddress("backend-database")
                                                        .setPortValue(5432)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());

        endpoints.add(ClusterLoadAssignment.newBuilder()
                .setClusterName("cluster_database_tcp")
                .addEndpoints(LocalityLbEndpoints.newBuilder()
                        .addLbEndpoints(LbEndpoint.newBuilder()
                                .setEndpoint(Endpoint.newBuilder()
                                        .setAddress(Address.newBuilder()
                                                .setSocketAddress(SocketAddress.newBuilder()
                                                        .setAddress("org1-cluster1-postgres")
                                                        .setPortValue(5432)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());

        // Add dynamic endpoints from runtime API calls
        for (Map.Entry<String, List<EndpointConfig>> entry : dynamicEndpoints.entrySet()) {
            String clusterName = entry.getKey();
            List<EndpointConfig> endpointConfigs = entry.getValue();

            ClusterLoadAssignment.Builder clusterAssignment = ClusterLoadAssignment.newBuilder()
                    .setClusterName(clusterName);

            LocalityLbEndpoints.Builder localityLbEndpoints = LocalityLbEndpoints.newBuilder();
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

            logger.debug("Added dynamic endpoint configuration for cluster {} with {} endpoints",
                    clusterName, endpointConfigs.size());
        }

        return endpoints;
    }

    /**
     * Checks if configuration files have changed
     *
     * @return true if any configuration file has been modified since last check
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
                                // File has changed or is new
                                changed.set(true);
                                // Update timestamp after checking
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

    /**
     * Adds a new cluster dynamically
     *
     * @param clusterConfig JSON string with cluster configuration
     *                   Format: {"name": "cluster_name", "type": "STRICT_DNS", "address": "host", "port": 8080}
     */
    public void addCluster(String clusterConfig) throws IOException {
        logger.info("Adding new cluster: {}", clusterConfig);

        try {
            // Try to parse as AddClusterRequest format first
            try {
                AddClusterRequest request = jsonMapper.readValue(clusterConfig, AddClusterRequest.class);
                logger.info("Parsed cluster request: name={}, type={}, address={}, port={}",
                        request.name(), request.type(), request.address(), request.port());

                // Create a single endpoint for the cluster
                EndpointConfig endpoint = new EndpointConfig(request.name(), request.address(), request.port());
                dynamicEndpoints.put(request.name(), List.of(endpoint));

                logger.info("Added cluster {} with endpoint {}:{}", request.name(), request.address(), request.port());
            } catch (Exception e) {
                // If that fails, try the simpler cluster_name format
                SimpleClusterRequest request = jsonMapper.readValue(clusterConfig, SimpleClusterRequest.class);
                logger.info("Parsed simple cluster request: name={}", request.cluster_name());

                if (request.endpoints() != null) {
                    List<EndpointConfig> endpoints = request.endpoints().stream()
                            .map(ep -> {
                                if (ep instanceof String) {
                                    String[] parts = ((String) ep).split(":");
                                    if (parts.length != 2) {
                                        throw new IllegalArgumentException("Invalid endpoint format: " + ep);
                                    }
                                    return new EndpointConfig(request.cluster_name(), parts[0], Integer.parseInt(parts[1]));
                                }
                                // Support object format
                                Map<String, Object> epMap = (Map<String, Object>) ep;
                                String host = (String) epMap.get("host");
                                Integer port = (Integer) epMap.get("port");
                                return new EndpointConfig(request.cluster_name(), host, port);
                            })
                            .collect(Collectors.toList());

                    dynamicEndpoints.put(request.cluster_name(), endpoints);
                    logger.info("Added {} endpoints for cluster {}", endpoints.size(), request.cluster_name());
                }
            }

            // Update configuration timestamp to trigger refresh
            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to add cluster", e);
            throw new IOException("Failed to parse cluster configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Updates endpoints dynamically for an existing cluster
     *
     * @param endpointConfig JSON string with endpoint configuration
     *                     Format: {"clusterName": "cluster_name", "endpoints": [{"host": "host", "port": 8080}, ...]}
     */
    public void updateEndpoints(String endpointConfig) throws IOException {
        logger.info("Updating endpoints: {}", endpointConfig);

        try {
            // Try to parse as EndpointUpdateRequest format first
            try {
                EndpointUpdateRequest request = jsonMapper.readValue(endpointConfig, EndpointUpdateRequest.class);
                logger.info("Parsed endpoint update request: clusterName={}", request.clusterName());

                List<EndpointConfig> endpoints = request.endpoints().stream()
                        .map(ep -> new EndpointConfig(request.clusterName(), ep.host(), ep.port()))
                        .collect(Collectors.toList());

                dynamicEndpoints.put(request.clusterName(), endpoints);
                logger.info("Updated {} endpoints for cluster {}", endpoints.size(), request.clusterName());
            } catch (Exception e) {
                // If that fails, try the simpler cluster_name format
                SimpleClusterRequest request = jsonMapper.readValue(endpointConfig, SimpleClusterRequest.class);
                logger.info("Parsed simple cluster request: name={}", request.cluster_name());

                List<EndpointConfig> endpoints = request.endpoints().stream()
                        .map(ep -> {
                            if (ep instanceof String) {
                                String[] parts = ((String) ep).split(":");
                                if (parts.length != 2) {
                                    throw new IllegalArgumentException("Invalid endpoint format: " + ep);
                                }
                                return new EndpointConfig(request.cluster_name(), parts[0], Integer.parseInt(parts[1]));
                            }
                            // Support object format
                            Map<String, Object> epMap = (Map<String, Object>) ep;
                            String host = (String) epMap.get("host");
                            Integer port = (Integer) epMap.get("port");
                            return new EndpointConfig(request.cluster_name(), host, port);
                        })
                        .collect(Collectors.toList());

                dynamicEndpoints.put(request.cluster_name(), endpoints);
                logger.info("Updated {} endpoints for cluster {}", endpoints.size(), request.cluster_name());
            }

            // Update configuration timestamp to trigger refresh
            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to update endpoints", e);
            throw new IOException("Failed to parse endpoint configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Force a configuration timestamp update to trigger snapshot refresh
     */
    private void updateConfigurationTimestamp() {
        Path configDir = Paths.get("/config");
        if (Files.exists(configDir)) {
            try {
                // Update a marker file timestamp to trigger configuration reload
                Path markerFile = configDir.resolve(".update-marker");
                Files.writeString(markerFile, String.valueOf(System.currentTimeMillis()));
                Files.setLastModifiedTime(markerFile, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException e) {
                logger.warn("Failed to update configuration marker file", e);
            }
        }
    }

    /**
     * Initialize default route templates
     */

    /**
     * Registers a new controller with both HTTP and Flight endpoints
     * This is the simplified API for adding new controllers dynamically
     *
     * @param controllerConfig JSON string with controller configuration
     *                        Format: {"domain": "controller3.one211.com", "host": "controller3", "httpPort": 9008, "flightPort": 59308}
     * @throws IOException if configuration parsing fails
     */
    public void registerController(String controllerConfig) throws IOException {
        logger.info("Registering controller: {}", controllerConfig);

        try {
            ControllerRegistrationRequest request = jsonMapper.readValue(controllerConfig, ControllerRegistrationRequest.class);
            logger.info("Parsed controller registration: domain={}, host={}, httpPort={}, flightPort={}",
                request.domain(), request.host(), request.httpPort(), request.flightPort());

            // Validate required fields
            if (request.domain() == null || request.domain().trim().isEmpty()) {
                throw new IllegalArgumentException("Domain is required");
            }
            if (request.host() == null || request.host().trim().isEmpty()) {
                throw new IllegalArgumentException("Host is required");
            }

            String domain = request.domain().trim();
            String host = request.host().trim();
            int httpPort = request.httpPort() != null ? request.httpPort() : 9006;
            int flightPort = request.flightPort() != null ? request.flightPort() : 59307;

            // 1. Add/update route for this controller domain
            addRouteForController(domain);
            logger.info("Added route for domain: {}", domain);

            // 2. Add HTTP endpoint to sql_controller_lb_http cluster
            addEndpointToCluster("sql_controller_lb_http", host, httpPort);
            logger.info("Added HTTP endpoint for {}:{} to sql_controller_lb_http", host, httpPort);

            // 3. Add Flight endpoint to controller_flight_cluster
            addEndpointToCluster("controller_flight_cluster", host, flightPort);
            logger.info("Added Flight endpoint for {}:{} to controller_flight_cluster", host, flightPort);

            // Update configuration timestamp to trigger snapshot refresh
            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to register controller", e);
            throw new IOException("Failed to register controller: " + e.getMessage(), e);
        }
    }

    /**
     * Adds a route for a controller domain
     *
     * @param domain the controller domain
     * @throws IOException if route addition fails
     */
    private void addRouteForController(String domain) throws IOException {
        RouteConfig config = new RouteConfig(
            domain,
            "sql_controller_lb_http",
            "/",
            50
        );
        dynamicRoutes.put(domain, config);
    }

    /**
     * Adds an endpoint to a cluster (adds to existing list or creates new one)
     *
     * @param clusterName the cluster name
     * @param host the endpoint host
     * @param port the endpoint port
     */
    private void addEndpointToCluster(String clusterName, String host, int port) {
        List<EndpointConfig> endpoints = dynamicEndpoints.getOrDefault(clusterName, new ArrayList<>());
        
        // Check if this endpoint already exists, if so update its port
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
     * Deregisters a controller (removes its endpoints and route)
     *
     * @param domain the controller domain to deregister
     * @param host the controller host to remove
     */
    public void deregisterController(String domain, String host) {
        logger.info("Deregistering controller: domain={}, host={}", domain, host);

        // Remove the route
        RouteConfig removed = dynamicRoutes.remove(domain);
        if (removed != null) {
            logger.info("Removed route for domain: {}", domain);
        }

        // Remove HTTP endpoint
        removeEndpointFromCluster("sql_controller_lb_http", host);
        
        // Remove Flight endpoint
        removeEndpointFromCluster("controller_flight_cluster", host);

        updateConfigurationTimestamp();
        logger.info("Deregistered controller: domain={}, host={}", domain, host);
    }

    /**
     * Removes an endpoint from a cluster
     *
     * @param clusterName the cluster name
     * @param host the endpoint host to remove
     */
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

    /**
     * Inner class for parsing controller registration requests
     */
    private record ControllerRegistrationRequest(String domain, String host, Integer httpPort, Integer flightPort) {
    }


    private void initializeRouteTemplates() {
        // Template for controller subdomains
        routeTemplates.put("controller", new RouteTemplate(
            "controller*.one211.com",
            "sql_controller_lb_http",
            "/",
            50
        ));

        // Template for wildcard all subdomains
        routeTemplates.put("wildcard", new RouteTemplate(
            "*.one211.com",
            "sql_controller_lb_http",
            "/",
            1000
        ));

        // Template for xyz_* pattern
        routeTemplates.put("xyz_pattern", new RouteTemplate(
            "xyz_*.one211.com",
            "sql_controller_lb_http",
            "/",
            100
        ));

        logger.info("Initialized {} route templates", routeTemplates.size());
    }

    /**
     * Adds a new route dynamically
     *
     * @param routeConfig JSON string with route configuration
     *                   Format: {"domain": "*.one211.com", "cluster": "cluster_name", "prefix": "/", "priority": 10}
     */
    public void addRoute(String routeConfig) throws IOException {
        updateRoute(routeConfig);
    }

    /**
     * Updates or adds a route dynamically (alias for addRoute)
     *
     * @param routeConfig JSON string with route configuration
     *                   Format: {"domain": "*.one211.com", "cluster": "cluster_name", "prefix": "/", "priority": 10}
     */
    public void updateRoute(String routeConfig) throws IOException {
        logger.info("Adding/updating route: {}", routeConfig);

        try {
            EnhancedRouteRequest request = jsonMapper.readValue(routeConfig, EnhancedRouteRequest.class);
            logger.info("Parsed route request: domain={}, cluster={}, prefix={}, priority={}",
                request.domain(), request.cluster(), request.prefix(), request.priority());

            // Validate required fields
            if (request.domain() == null || request.domain().trim().isEmpty()) {
                throw new IllegalArgumentException("Domain is required");
            }
            if (request.cluster() == null || request.cluster().trim().isEmpty()) {
                throw new IllegalArgumentException("Cluster is required");
            }

            // Trim domain and cluster
            String domain = request.domain().trim();
            String cluster = request.cluster().trim();

            // Validate prefix - must start with '/'
            String prefix = request.prefix();
            if (prefix == null || prefix.trim().isEmpty()) {
                prefix = "/";
            } else {
                prefix = prefix.trim();
                if (!prefix.startsWith("/")) {
                    throw new IllegalArgumentException("Prefix must start with '/'");
                }
            }

            // Validate domain pattern
            if (!isValidDomainPattern(domain)) {
                throw new IllegalArgumentException("Invalid domain pattern: " + domain);
            }

            RouteConfig config = new RouteConfig(
                domain,
                cluster,
                prefix,
                request.priority()
            );

            dynamicRoutes.put(domain, config);
            logger.info("Added/updated route {} -> {} (priority: {})",
                domain, cluster, request.priority());

            updateConfigurationTimestamp();

        } catch (Exception e) {
            logger.error("Failed to add/update route", e);
            throw new IOException("Failed to parse route configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a route by domain pattern
     *
     * @param domainPattern the domain pattern to delete
     */
    public void deleteRoute(String domainPattern) {
        logger.info("Deleting route for domain: {}", domainPattern);

        RouteConfig removed = dynamicRoutes.remove(domainPattern);
        if (removed != null) {
            logger.info("Deleted route: {} -> {}", domainPattern, removed.getCluster());
            updateConfigurationTimestamp();
        } else {
            logger.warn("No route found for domain pattern: {}", domainPattern);
        }
    }

    /**
     * Lists all dynamic routes
     *
     * @return JSON string of all routes
     */
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

        // Sort by priority
        routes.sort(Comparator.comparingInt(r -> (Integer) r.get("priority")));

        Map<String, Object> response = new HashMap<>();
        response.put("routes", routes);
        response.put("count", routes.size());

        return jsonMapper.writeValueAsString(response);
    }

    /**
     * Adds a route from a template
     *
     * @param templateName the name of the template to use
     * @param clusterOverride optional cluster name to override the template's default
     * @throws IOException if template not found or configuration fails
     */
    public void addRouteFromTemplate(String templateName, String clusterOverride) throws IOException {
        RouteTemplate template = routeTemplates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }

        String domain = template.domain();
        String cluster = clusterOverride != null ? clusterOverride : template.cluster();

        RouteConfig config = new RouteConfig(
            domain,
            cluster,
            template.prefix(),
            template.priority()
        );

        dynamicRoutes.put(domain, config);
        logger.info("Created route from template {}: {} -> {} (priority: {})",
            templateName, domain, cluster, template.priority());

        updateConfigurationTimestamp();
    }

    /**
     * Validates a domain pattern
     *
     * @param domain the domain pattern to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidDomainPattern(String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }

        // Support exact domains, wildcards (*), and patterns (*, but no other regex)
        // Common patterns: *.one211.com, controller*.one211.com, etc.
        return domain.matches("^[a-zA-Z0-9._*:-]+$");
    }

    /**
     * Inner class for parsing cluster/endpoint requests
     */
    private record AddClusterRequest(String name, String type, String address, int port) {
    }

    /**
     * Inner class for parsing endpoint update requests
     */
    private record EndpointUpdateRequest(String clusterName, List<EndpointData> endpoints) {
    }

    /**
     * Inner class for parsing endpoint objects
     */
    private record EndpointData(String host, int port) {
    }

    /**
     * Inner class for parsing simple cluster/endpoint requests
     */
    private record SimpleClusterRequest(String cluster_name, List<Object> endpoints) {
    }

    /**
     * Inner class for endpoint configuration
     */
    public static class EndpointConfig {
        private String clusterName;
        private String host;
        private int port;

        public EndpointConfig(String clusterName, String host, int port) {
            this.clusterName = clusterName;
            this.host = host;
            this.port = port;
        }

        // Getters and setters
        public String getClusterName() { return clusterName; }
        public String getHost() { return host; }
        public int getPort() { return port; }
    }

    /**
     * Inner class for parsing route requests with enhanced fields
     */
    private record EnhancedRouteRequest(
        String domain,
        String cluster,
        String prefix,
        int priority
    ) {
        // Default values
        public EnhancedRouteRequest(String domain, String cluster, String prefix, int priority) {
            this.domain = domain;
            this.cluster = cluster;
            this.prefix = prefix != null ? prefix : "/";
            this.priority = priority > 0 ? priority : 100;
        }
    }

    /**
     * Inner class for route configuration
     */
    public static class RouteConfig {
        private String domain;
        private String cluster;
        private String prefix;
        private int priority;

        public RouteConfig(String domain, String cluster, String prefix, int priority) {
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

    /**
     * Inner class for route templates
     */
    public static class RouteTemplate {
        private String domain;
        private String cluster;
        private String prefix;
        private int priority;

        public RouteTemplate(String domain, String cluster, String prefix, int priority) {
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
