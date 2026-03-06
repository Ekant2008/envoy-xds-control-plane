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

    // Arrow Flight SQL ports
    public static final int FLIGHT_PORT_CONTROLLERS;
    public static final int FLIGHT_PORT_OLLYLAKE;

    // PostgreSQL ports
    public static final int POSTGRES_PORT_BACKEND;
    public static final int POSTGRES_PORT_CLUSTER;

    // Service ports
    public static final int SERVICE_PORT_BACKEND;
    public static final int SERVICE_PORT_FRONTEND;
    public static final int SERVICE_PORT_MINIO_API;
    public static final int SERVICE_PORT_MINIO_CONSOLE;
    public static final int SERVICE_PORT_OLLYLAKE_HTTP;

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
        FLIGHT_PORT_CONTROLLERS = flightPorts.getInt("controllers");
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
