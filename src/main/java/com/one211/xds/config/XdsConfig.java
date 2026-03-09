package com.one211.xds.config;

import com.typesafe.config.Config;

/**
 * Configuration values for xDS resources
 * Centralizes all magic numbers from XdsConfigManager
 */
public class XdsConfig {

    // Gateway ports
    public static final int HTTPS_GATEWAY_PORT;
    public static final int MINIO_S3_API_PORT;

    // Arrow Flight SQL ports (exposed on Envoy)
    public static final int FLIGHT_PORT_CONTROLLER1;
    public static final int FLIGHT_PORT_CONTROLLER2;
    public static final int FLIGHT_PORT_OLLYLAKE;

    // PostgreSQL ports (exposed on Envoy)
    public static final int POSTGRES_PORT_BACKEND;
    public static final int POSTGRES_PORT_CLUSTER;

    // Service ports (internal container ports)
    public static final int SERVICE_PORT_BACKEND;
    public static final int SERVICE_PORT_FRONTEND;
    public static final int SERVICE_PORT_MINIO_API;
    public static final int SERVICE_PORT_MINIO_CONSOLE;
    public static final int SERVICE_PORT_OLLYLAKE_HTTP;
    public static final int SERVICE_PORT_CONTROLLER1_HTTP;
    public static final int SERVICE_PORT_CONTROLLER2_HTTP;
    public static final int SERVICE_PORT_CONTROLLER1_FLIGHT;
    public static final int SERVICE_PORT_CONTROLLER2_FLIGHT;
    public static final int SERVICE_PORT_OLLYLAKE_FLIGHT;
    public static final int SERVICE_PORT_DATABASE;

    // Service hostnames
    public static final String HOST_BACKEND;
    public static final String HOST_FRONTEND;
    public static final String HOST_MINIO;
    public static final String HOST_OLLYLAKE;
    public static final String HOST_CONTROLLER1;
    public static final String HOST_CONTROLLER2;
    public static final String HOST_BACKEND_DATABASE;
    public static final String HOST_CLUSTER_DATABASE;

    // TLS certificate paths
    public static final String TLS_CERT_CHAIN;
    public static final String TLS_PRIVATE_KEY;

    // CORS
    public static final String CORS_ALLOWED_ORIGIN;

    // Timeouts (in seconds)
    public static final int TIMEOUT_API_SHORT;
    public static final int TIMEOUT_API_STANDARD;
    public static final int TIMEOUT_API_LONG;
    public static final int TIMEOUT_TCP_CONNECT;
    public static final int TIMEOUT_TCP_IDLE;
    public static final int TIMEOUT_IMMEDIATE;

    // Scheduler configuration
    public static final int SCHEDULER_INITIAL_DELAY_SEC;
    public static final int SCHEDULER_INTERVAL_SEC;

    static {
        Config config = ConfigLoader.getConfig();
        Config xdsConfig = config.getConfig("xds");

        // Gateway ports
        HTTPS_GATEWAY_PORT = xdsConfig.getInt("https-gateway-port");
        MINIO_S3_API_PORT = xdsConfig.getInt("minio-s3-api-port");

        // Arrow Flight SQL ports
        Config flightPorts = xdsConfig.getConfig("flight-ports");
        FLIGHT_PORT_CONTROLLER1 = flightPorts.getInt("controller1");
        FLIGHT_PORT_CONTROLLER2 = flightPorts.getInt("controller2");
        FLIGHT_PORT_OLLYLAKE = flightPorts.getInt("ollylake");

        // PostgreSQL ports
        Config postgresPorts = xdsConfig.getConfig("postgres-ports");
        POSTGRES_PORT_BACKEND = postgresPorts.getInt("backend");
        POSTGRES_PORT_CLUSTER = postgresPorts.getInt("cluster");

        // Service ports
        Config services = xdsConfig.getConfig("services");
        SERVICE_PORT_BACKEND = services.getInt("backend");
        SERVICE_PORT_FRONTEND = services.getInt("frontend");
        SERVICE_PORT_MINIO_API = services.getInt("minio-api");
        SERVICE_PORT_MINIO_CONSOLE = services.getInt("minio-console");
        SERVICE_PORT_OLLYLAKE_HTTP = services.getInt("ollylake-http");
        SERVICE_PORT_CONTROLLER1_HTTP = services.getInt("controller1-http");
        SERVICE_PORT_CONTROLLER2_HTTP = services.getInt("controller2-http");
        SERVICE_PORT_CONTROLLER1_FLIGHT = services.getInt("controller1-flight");
        SERVICE_PORT_CONTROLLER2_FLIGHT = services.getInt("controller2-flight");
        SERVICE_PORT_OLLYLAKE_FLIGHT = services.getInt("ollylake-flight");
        SERVICE_PORT_DATABASE = services.getInt("database");

        // Service hostnames
        Config hosts = xdsConfig.getConfig("hosts");
        HOST_BACKEND = hosts.getString("backend");
        HOST_FRONTEND = hosts.getString("frontend");
        HOST_MINIO = hosts.getString("minio");
        HOST_OLLYLAKE = hosts.getString("ollylake");
        HOST_CONTROLLER1 = hosts.getString("controller1");
        HOST_CONTROLLER2 = hosts.getString("controller2");
        HOST_BACKEND_DATABASE = hosts.getString("backend-database");
        HOST_CLUSTER_DATABASE = hosts.getString("cluster-database");

        // TLS
        Config tls = xdsConfig.getConfig("tls");
        TLS_CERT_CHAIN = tls.getString("cert-chain");
        TLS_PRIVATE_KEY = tls.getString("private-key");

        // CORS
        CORS_ALLOWED_ORIGIN = xdsConfig.getString("cors.allowed-origin");

        // Timeouts
        Config timeouts = xdsConfig.getConfig("timeouts");
        TIMEOUT_API_SHORT = timeouts.getInt("api-short");
        TIMEOUT_API_STANDARD = timeouts.getInt("api-standard");
        TIMEOUT_API_LONG = timeouts.getInt("api-long");
        TIMEOUT_TCP_CONNECT = timeouts.getInt("tcp-connect");
        TIMEOUT_TCP_IDLE = timeouts.getInt("tcp-idle");
        TIMEOUT_IMMEDIATE = timeouts.getInt("immediate");

        // Scheduler
        Config scheduler = xdsConfig.getConfig("scheduler");
        SCHEDULER_INITIAL_DELAY_SEC = scheduler.getInt("initial-delay-sec");
        SCHEDULER_INTERVAL_SEC = scheduler.getInt("interval-sec");
    }

    private XdsConfig() {
        // Utility class - prevent instantiation
    }
}
