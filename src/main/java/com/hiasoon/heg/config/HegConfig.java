package com.hiasoon.heg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "heg")
public class HegConfig {

    private CloudConfig cloud = new CloudConfig();

    private List<MysqlDataSource> mysql = null;

    private List<RedisDataSource> redis = null;

    private List<MongoDataSource> mongodb = null;

    private List<String> proxyHeaders;

    private ConfigPersistence configPersistence = new ConfigPersistence();

    private LogPersistence logPersistence = new LogPersistence();

    @Data
    public static class ConfigPersistence {
        private String type = "memory";
        private String filePath = "heg-runtime-config.yml";
    }

    @Data
    public static class LogPersistence {
        private boolean enabled = false;
        private String sqlLogPath = "logs/sql/";
        private String proxyLogPath = "logs/proxy/";
        private int maxFileSizeMb = 100;
        private int retainDays = 30;
    }

    @Data
    public static class CloudConfig {
        private String websocketUrl;
        private String appId;
        private String secretKey;
        private String apiBaseUrl = "https://api.hiasoon.com";
    }

    @Data
    public static class MysqlDataSource {
        private String appId;
        private String url;
        private String username;
        private String password;
        private Integer initialSize = 5;
        private Integer maxActive = 20;
    }

    @Data
    public static class RedisDataSource {
        private String appId;
        private String host;
        private Integer port = 6379;
        private String password;
        private Integer database = 0;
    }

    @Data
    public static class MongoDataSource {
        private String appId;
        private String uri;
        private String database;
    }
}