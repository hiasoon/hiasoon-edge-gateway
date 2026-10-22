package com.hiasoon.heg.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.hiasoon.heg.config.HegConfig.*;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class DataSourcePool {

    private final AtomicReference<Map<String, DruidDataSource>> mysqlPoolsRef = new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, JedisPool>> redisPoolsRef = new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, com.mongodb.client.MongoClient>> mongoClientsRef = new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, String>> mongoDefaultDatabasesRef = new AtomicReference<>(new ConcurrentHashMap<>());

    private final HegConfig hegConfig;
    private final ConfigPersistenceService configPersistenceService;

    public DataSourcePool(HegConfig hegConfig, ConfigPersistenceService configPersistenceService) {
        this.hegConfig = hegConfig;
        this.configPersistenceService = configPersistenceService;
    }

    @PostConstruct
    public void init() {
        initMysqlPools();
        initRedisPools();
        initMongoClients();
    }

    @PreDestroy
    public void destroy() {
        closeMysqlPools();
        closeRedisPools();
        closeMongoClients();
    }

    private void initMysqlPools() {
        Map<String, DruidDataSource> newPools = new ConcurrentHashMap<>();
        if (hegConfig.getMysql() != null) {
            for (MysqlDataSource config : hegConfig.getMysql()) {
                if (isBlank(config.getUrl())) {
                    log.info("MySQL数据源未配置(URL为空)，跳过初始化: appId={}", config.getAppId());
                    continue;
                }
                try {
                    DruidDataSource ds = createMysqlDataSource(config);
                    newPools.put(config.getAppId(), ds);
                    log.info("MySQL连接池初始化成功: appId={}", config.getAppId());
                } catch (Exception e) {
                    log.error("MySQL连接池初始化失败: appId={}", config.getAppId(), e);
                }
            }
        }
        mysqlPoolsRef.set(newPools);
    }

    private DruidDataSource createMysqlDataSource(MysqlDataSource config) throws Exception {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(config.getUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setInitialSize(config.getInitialSize());
        ds.setMaxActive(config.getMaxActive());
        ds.setValidationQuery("SELECT 1");
        ds.setBreakAfterAcquireFailure(true);
        ds.setConnectionErrorRetryAttempts(0);
        ds.init();
        return ds;
    }

    private void initRedisPools() {
        Map<String, JedisPool> newPools = new ConcurrentHashMap<>();
        if (hegConfig.getRedis() != null) {
            for (RedisDataSource config : hegConfig.getRedis()) {
                if (isBlank(config.getHost())) {
                    log.info("Redis数据源未配置(host为空)，跳过初始化: appId={}", config.getAppId());
                    continue;
                }
                try {
                    JedisPool pool = createRedisPool(config);
                    newPools.put(config.getAppId(), pool);
                    log.info("Redis连接池初始化成功: appId={}", config.getAppId());
                } catch (Exception e) {
                    log.error("Redis连接池初始化失败: appId={}", config.getAppId(), e);
                }
            }
        }
        Map<String, JedisPool> oldPools = redisPoolsRef.getAndSet(newPools);
        closeRedisPools(oldPools);
    }

    private JedisPool createRedisPool(RedisDataSource config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(100);
        poolConfig.setMaxIdle(20);
        poolConfig.setMinIdle(5);
        poolConfig.setTestOnBorrow(true);

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                    Protocol.DEFAULT_TIMEOUT, config.getPassword(), config.getDatabase());
        } else {
            return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                    Protocol.DEFAULT_TIMEOUT, null, config.getDatabase());
        }
    }

    private void initMongoClients() {
        Map<String, com.mongodb.client.MongoClient> newClients = new ConcurrentHashMap<>();
        Map<String, String> newDatabases = new ConcurrentHashMap<>();
        if (hegConfig.getMongodb() != null) {
            for (MongoDataSource config : hegConfig.getMongodb()) {
                if (isBlank(config.getUri())) {
                    log.info("MongoDB数据源未配置(URI为空)，跳过初始化: appId={}", config.getAppId());
                    continue;
                }
                try {
                    com.mongodb.client.MongoClient client = MongoClients.create(config.getUri());
                    newClients.put(config.getAppId(), client);
                    if (config.getDatabase() != null && !config.getDatabase().isEmpty()) {
                        newDatabases.put(config.getAppId(), config.getDatabase());
                    }
                    log.info("MongoDB客户端初始化成功: appId={}, database={}", config.getAppId(), config.getDatabase());
                } catch (Exception e) {
                    log.error("MongoDB客户端初始化失败: appId={}", config.getAppId(), e);
                }
            }
        }
        mongoClientsRef.set(newClients);
        mongoDefaultDatabasesRef.set(newDatabases);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public void reloadMysqlPools() {
        log.info("开始重载MySQL连接池...");
        Map<String, DruidDataSource> oldPools = mysqlPoolsRef.get();
        initMysqlPools();
        closeMysqlPools(oldPools);
        log.info("MySQL连接池重载完成");
    }

    public void reloadRedisPools() {
        log.info("开始重载Redis连接池...");
        initRedisPools();
        log.info("Redis连接池重载完成");
    }

    public void reloadMongoClients() {
        log.info("开始重载MongoDB客户端...");
        Map<String, com.mongodb.client.MongoClient> oldClients = mongoClientsRef.get();
        initMongoClients();
        closeMongoClients(oldClients);
        log.info("MongoDB客户端重载完成");
    }

    public void reloadAll() {
        reloadMysqlPools();
        reloadRedisPools();
        reloadMongoClients();
    }

    public DruidDataSource getMysqlDataSource(String appId) {
        return mysqlPoolsRef.get().get(appId);
    }

    public JedisPool getRedisPool(String appId) {
        return redisPoolsRef.get().get(appId);
    }

    public com.mongodb.client.MongoClient getMongoClient(String appId) {
        return mongoClientsRef.get().get(appId);
    }

    public String getMongoDefaultDatabase(String appId) {
        return mongoDefaultDatabasesRef.get().get(appId);
    }

    private void closeMysqlPools() {
        closeMysqlPools(mysqlPoolsRef.get());
    }

    private void closeMysqlPools(Map<String, DruidDataSource> pools) {
        for (Map.Entry<String, DruidDataSource> entry : pools.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.error("关闭MySQL连接池失败: appId={}", entry.getKey(), e);
            }
        }
    }

    private void closeRedisPools() {
        closeRedisPools(redisPoolsRef.get());
    }

    private void closeRedisPools(Map<String, JedisPool> pools) {
        for (Map.Entry<String, JedisPool> entry : pools.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.error("关闭Redis连接池失败: appId={}", entry.getKey(), e);
            }
        }
    }

    private void closeMongoClients() {
        closeMongoClients(mongoClientsRef.get());
    }

    private void closeMongoClients(Map<String, com.mongodb.client.MongoClient> clients) {
        for (Map.Entry<String, com.mongodb.client.MongoClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.error("关闭MongoDB客户端失败: appId={}", entry.getKey(), e);
            }
        }
    }
}