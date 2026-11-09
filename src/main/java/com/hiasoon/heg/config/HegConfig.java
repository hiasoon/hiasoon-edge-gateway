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
        private Tls tls = new Tls();
    }

    /**
     * 云端连接 TLS 配置。
     *
     * <p>默认使用 JDK 内置信任库（cacerts），由公共 CA（如 Let's Encrypt）签发的
     * 云端证书无需任何配置即可通过校验。仅在私有 CA / 自签证书场景下才需要
     * 指定 {@code trust-store}。
     */
    @Data
    public static class Tls {
        /**
         * 自定义信任库路径。支持 PEM 证书（或 CA 证书链）与 JKS / PKCS12 密钥库。
         * 为空表示使用 JDK 默认信任库。
         */
        private String trustStore;
        /**
         * 密钥库口令。仅 JKS / PKCS12 格式需要；PEM 格式忽略此项。
         */
        private String trustStorePassword;
        /**
         * 是否跳过证书校验。默认 false。
         *
         * <p><b>仅允许用于自签证书的临时联调环境。</b>开启后链路可被中间人攻击
         * 解密与篡改，生产环境必须保持关闭。
         */
        private boolean insecureSkipVerify = false;
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