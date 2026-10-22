package com.hiasoon.heg.config;

import com.hiasoon.heg.config.HegConfig.*;
import com.hiasoon.heg.monitor.LogFileWriter;
import com.hiasoon.heg.websocket.HegWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class RuntimeConfigService {

    private final HegConfig hegConfig;
    private final DataSourcePool dataSourcePool;
    private final HegWebSocketClient webSocketClient;
    private final ConfigPersistenceService configPersistenceService;
    private final LogFileWriter logFileWriter;

    private final ReentrantLock configLock = new ReentrantLock();

    public RuntimeConfigService(HegConfig hegConfig, DataSourcePool dataSourcePool,
                                HegWebSocketClient webSocketClient, ConfigPersistenceService configPersistenceService,
                                LogFileWriter logFileWriter) {
        this.hegConfig = hegConfig;
        this.dataSourcePool = dataSourcePool;
        this.webSocketClient = webSocketClient;
        this.configPersistenceService = configPersistenceService;
        this.logFileWriter = logFileWriter;
    }

    public Map<String, Object> getCurrentConfig() {
        Map<String, Object> config = new LinkedHashMap<>();

        Map<String, Object> cloud = new LinkedHashMap<>();
        cloud.put("websocketUrl", hegConfig.getCloud().getWebsocketUrl());
        cloud.put("appId", hegConfig.getCloud().getAppId());
        cloud.put("secretKey", maskSecret(hegConfig.getCloud().getSecretKey()));
        cloud.put("apiBaseUrl", hegConfig.getCloud().getApiBaseUrl());
        config.put("cloud", cloud);

        List<Map<String, Object>> mysqlList = new ArrayList<>();
        if (hegConfig.getMysql() != null) {
            for (MysqlDataSource ds : hegConfig.getMysql()) {
                Map<String, Object> mysql = new LinkedHashMap<>();
                mysql.put("appId", ds.getAppId());
                mysql.put("url", ds.getUrl());
                mysql.put("username", ds.getUsername());
                mysql.put("password", maskSecret(ds.getPassword()));
                mysql.put("initialSize", ds.getInitialSize());
                mysql.put("maxActive", ds.getMaxActive());
                mysqlList.add(mysql);
            }
        }
        config.put("mysql", mysqlList);

        List<Map<String, Object>> redisList = new ArrayList<>();
        if (hegConfig.getRedis() != null) {
            for (RedisDataSource ds : hegConfig.getRedis()) {
                Map<String, Object> redis = new LinkedHashMap<>();
                redis.put("appId", ds.getAppId());
                redis.put("host", ds.getHost());
                redis.put("port", ds.getPort());
                redis.put("password", maskSecret(ds.getPassword()));
                redis.put("database", ds.getDatabase());
                redisList.add(redis);
            }
        }
        config.put("redis", redisList);

        List<Map<String, Object>> mongoList = new ArrayList<>();
        if (hegConfig.getMongodb() != null) {
            for (MongoDataSource ds : hegConfig.getMongodb()) {
                Map<String, Object> mongo = new LinkedHashMap<>();
                mongo.put("appId", ds.getAppId());
                mongo.put("uri", maskUri(ds.getUri()));
                mongo.put("database", ds.getDatabase());
                mongoList.add(mongo);
            }
        }
        config.put("mongodb", mongoList);

        List<String> proxyHeaders = hegConfig.getProxyHeaders() != null
                ? hegConfig.getProxyHeaders() : new ArrayList<>();
        config.put("proxyHeaders", proxyHeaders);

        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("type", hegConfig.getConfigPersistence().getType());
        persistence.put("filePath", hegConfig.getConfigPersistence().getFilePath());
        config.put("persistence", persistence);

        Map<String, Object> logPersistence = new LinkedHashMap<>();
        HegConfig.LogPersistence lp = hegConfig.getLogPersistence();
        logPersistence.put("enabled", lp.isEnabled());
        logPersistence.put("sqlLogPath", lp.getSqlLogPath());
        logPersistence.put("proxyLogPath", lp.getProxyLogPath());
        logPersistence.put("maxFileSizeMb", lp.getMaxFileSizeMb());
        logPersistence.put("retainDays", lp.getRetainDays());
        config.put("logPersistence", logPersistence);

        return config;
    }

    public Map<String, Object> updateCloudConfig(Map<String, String> cloudConfig) {
        configLock.lock();
        boolean needReconnect = false;
        try {
            CloudConfig cloud = hegConfig.getCloud();
            if (cloudConfig.containsKey("websocketUrl")) {
                String newUrl = cloudConfig.get("websocketUrl");
                if (!newUrl.equals(cloud.getWebsocketUrl())) {
                    cloud.setWebsocketUrl(newUrl);
                    needReconnect = true;
                }
            }
            if (cloudConfig.containsKey("appId")) {
                String newAppId = cloudConfig.get("appId");
                if (!newAppId.equals(cloud.getAppId())) {
                    cloud.setAppId(newAppId);
                    needReconnect = true;
                }
            }
            if (cloudConfig.containsKey("secretKey")) {
                String key = cloudConfig.get("secretKey");
                if (key != null && !key.isEmpty() && !key.equals("***")) {
                    if (!key.equals(cloud.getSecretKey())) {
                        cloud.setSecretKey(key);
                        needReconnect = true;
                    }
                }
            }
            if (cloudConfig.containsKey("apiBaseUrl")) {
                cloud.setApiBaseUrl(cloudConfig.get("apiBaseUrl"));
            }
            log.info("云端配置已更新");
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        if (needReconnect) {
            log.info("检测到WebSocket连接参数变更，正在重连...");
            try {
                webSocketClient.reconnect();
            } catch (Exception e) {
                log.error("WebSocket重连失败", e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", needReconnect ? "云端配置已更新，WebSocket正在重连" : "云端配置已更新");
        return result;
    }

    public Map<String, Object> updateProxyHeaders(List<String> headers) {
        configLock.lock();
        try {
            List<String> existing = hegConfig.getProxyHeaders();
            Set<String> merged = new LinkedHashSet<>();

            // 1. 保留原有的
            if (existing != null) {
                merged.addAll(existing);
            }

            // 2. 添加新的（去重）
            if (headers != null) {
                merged.addAll(headers);
            }

            List<String> finalList = new ArrayList<>(merged);
            hegConfig.setProxyHeaders(finalList);
            log.info("代理请求头配置已更新: {}", finalList);
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "代理请求头配置已更新");
        return result;
    }

    /**
     * 删除指定的代理请求头
     */
    public Map<String, Object> removeProxyHeader(String header) {
        configLock.lock();
        try {
            List<String> existing = hegConfig.getProxyHeaders();
            if (existing != null && header != null) {
                existing.removeIf(h -> h.equalsIgnoreCase(header));
                hegConfig.setProxyHeaders(new ArrayList<>(existing));
                log.info("代理请求头已删除: {}", header);
                configPersistenceService.save();
            }
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "代理请求头已删除");
        return result;
    }

    public Map<String, Object> updateMysqlDataSource(List<Map<String, Object>> mysqlList) {
        configLock.lock();
        try {
            List<MysqlDataSource> newList = new CopyOnWriteArrayList<>();
            for (Map<String, Object> item : mysqlList) {
                MysqlDataSource ds = new MysqlDataSource();
                ds.setAppId((String) item.get("appId"));
                ds.setUrl((String) item.get("url"));
                ds.setUsername((String) item.get("username"));
                String pwd = (String) item.get("password");
                if (pwd != null && !pwd.isEmpty() && !pwd.equals("***")) {
                    ds.setPassword(pwd);
                }
                if (item.get("initialSize") != null) {
                    ds.setInitialSize(((Number) item.get("initialSize")).intValue());
                }
                if (item.get("maxActive") != null) {
                    ds.setMaxActive(((Number) item.get("maxActive")).intValue());
                }
                newList.add(ds);
            }
            hegConfig.setMysql(newList);
            dataSourcePool.reloadMysqlPools();
            log.info("MySQL数据源配置已更新");
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "MySQL数据源配置已更新，连接池已重载");
        return result;
    }

    public Map<String, Object> updateRedisDataSource(List<Map<String, Object>> redisList) {
        configLock.lock();
        try {
            List<RedisDataSource> newList = new CopyOnWriteArrayList<>();
            for (Map<String, Object> item : redisList) {
                RedisDataSource ds = new RedisDataSource();
                ds.setAppId((String) item.get("appId"));
                ds.setHost((String) item.get("host"));
                if (item.get("port") != null) {
                    ds.setPort(((Number) item.get("port")).intValue());
                }
                String pwd = (String) item.get("password");
                if (pwd != null && !pwd.isEmpty() && !pwd.equals("***")) {
                    ds.setPassword(pwd);
                }
                if (item.get("database") != null) {
                    ds.setDatabase(((Number) item.get("database")).intValue());
                }
                newList.add(ds);
            }
            hegConfig.setRedis(newList);
            dataSourcePool.reloadRedisPools();
            log.info("Redis数据源配置已更新");
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "Redis数据源配置已更新，连接池已重载");
        return result;
    }

    public Map<String, Object> updateMongoDataSource(List<Map<String, Object>> mongoList) {
        configLock.lock();
        try {
            List<MongoDataSource> newList = new CopyOnWriteArrayList<>();
            for (Map<String, Object> item : mongoList) {
                MongoDataSource ds = new MongoDataSource();
                ds.setAppId((String) item.get("appId"));
                String uri = (String) item.get("uri");
                if (uri != null && !uri.isEmpty() && !uri.startsWith("***")) {
                    ds.setUri(uri);
                }
                ds.setDatabase((String) item.get("database"));
                newList.add(ds);
            }
            hegConfig.setMongodb(newList);
            dataSourcePool.reloadMongoClients();
            log.info("MongoDB数据源配置已更新");
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "MongoDB数据源配置已更新，连接池已重载");
        return result;
    }

    public Map<String, Object> reloadAll() {
        configLock.lock();
        try {
            dataSourcePool.reloadAll();
            log.info("所有连接池已重载");
        } finally {
            configLock.unlock();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "所有连接池已重载");
        return result;
    }

    public Map<String, Object> updateLogPersistence(Map<String, Object> logConfig) {
        configLock.lock();
        try {
            HegConfig.LogPersistence lp = hegConfig.getLogPersistence();
            if (logConfig.containsKey("enabled")) {
                lp.setEnabled((Boolean) logConfig.get("enabled"));
            }
            if (logConfig.containsKey("sqlLogPath")) {
                lp.setSqlLogPath((String) logConfig.get("sqlLogPath"));
            }
            if (logConfig.containsKey("proxyLogPath")) {
                lp.setProxyLogPath((String) logConfig.get("proxyLogPath"));
            }
            if (logConfig.containsKey("maxFileSizeMb")) {
                lp.setMaxFileSizeMb(((Number) logConfig.get("maxFileSizeMb")).intValue());
            }
            if (logConfig.containsKey("retainDays")) {
                lp.setRetainDays(((Number) logConfig.get("retainDays")).intValue());
            }
            log.info("日志持久化配置已更新");
            logFileWriter.refreshConfig();
            configPersistenceService.save();
        } finally {
            configLock.unlock();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("message", "日志持久化配置已更新");
        return result;
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return "***";
    }

    private String maskUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        int atIndex = uri.indexOf('@');
        if (atIndex > 0) {
            String scheme = uri.substring(0, uri.indexOf("://") + 3);
            String rest = uri.substring(atIndex + 1);
            return scheme + "***@" + rest;
        }
        return uri;
    }
}