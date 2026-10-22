package com.hiasoon.heg.config;

import com.hiasoon.heg.config.HegConfig.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class ConfigPersistenceService {

    private final HegConfig hegConfig;

    public ConfigPersistenceService(HegConfig hegConfig) {
        this.hegConfig = hegConfig;
    }

    @PostConstruct
    public void init() {
        ConfigPersistence persistence = hegConfig.getConfigPersistence();
        if (!"file".equalsIgnoreCase(persistence.getType())) {
            log.info("配置存储策略为内存型，跳过文件加载");
            return;
        }

        String filePath = persistence.getFilePath();
        File file = new File(filePath);
        if (!file.exists()) {
            log.info("持久化配置文件不存在: {}，使用默认配置启动", filePath);
            return;
        }

        try {
            loadConfig(file);
            log.info("从持久化文件加载配置成功: {}", filePath);
        } catch (Exception e) {
            log.error("加载持久化配置文件失败: {}", filePath, e);
        }
    }

    private void loadConfig(File file) throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> data;
        try (InputStream is = new FileInputStream(file)) {
            data = yaml.load(is);
        }

        if (data == null || !data.containsKey("heg")) {
            log.warn("持久化文件格式不正确或缺少heg节点");
            return;
        }

        Map<String, Object> heg = (Map<String, Object>) data.get("heg");

        if (heg.containsKey("cloud")) {
            Map<String, Object> cloudMap = (Map<String, Object>) heg.get("cloud");
            CloudConfig cloud = hegConfig.getCloud();
            if (cloudMap.containsKey("websocket-url")) {
                cloud.setWebsocketUrl((String) cloudMap.get("websocket-url"));
            }
            if (cloudMap.containsKey("app-id")) {
                cloud.setAppId((String) cloudMap.get("app-id"));
            }
            if (cloudMap.containsKey("secret-key")) {
                cloud.setSecretKey((String) cloudMap.get("secret-key"));
            }
            if (cloudMap.containsKey("api-base-url")) {
                cloud.setApiBaseUrl((String) cloudMap.get("api-base-url"));
            }
        }

        if (heg.containsKey("mysql")) {
            List<Map<String, Object>> mysqlList = (List<Map<String, Object>>) heg.get("mysql");
            if (mysqlList != null) {
                List<MysqlDataSource> list = new ArrayList<>();
                for (Map<String, Object> item : mysqlList) {
                    MysqlDataSource ds = new MysqlDataSource();
                    ds.setAppId((String) item.get("app-id"));
                    ds.setUrl((String) item.get("url"));
                    ds.setUsername((String) item.get("username"));
                    ds.setPassword((String) item.get("password"));
                    if (item.containsKey("initial-size")) {
                        ds.setInitialSize(toInt(item.get("initial-size")));
                    }
                    if (item.containsKey("max-active")) {
                        ds.setMaxActive(toInt(item.get("max-active")));
                    }
                    list.add(ds);
                }
                hegConfig.setMysql(list);
            }
        }

        if (heg.containsKey("redis")) {
            List<Map<String, Object>> redisList = (List<Map<String, Object>>) heg.get("redis");
            if (redisList != null) {
                List<RedisDataSource> list = new ArrayList<>();
                for (Map<String, Object> item : redisList) {
                    RedisDataSource ds = new RedisDataSource();
                    ds.setAppId((String) item.get("app-id"));
                    ds.setHost((String) item.get("host"));
                    if (item.containsKey("port")) {
                        ds.setPort(toInt(item.get("port")));
                    }
                    ds.setPassword((String) item.get("password"));
                    if (item.containsKey("database")) {
                        ds.setDatabase(toInt(item.get("database")));
                    }
                    list.add(ds);
                }
                hegConfig.setRedis(list);
            }
        }

        if (heg.containsKey("mongodb")) {
            List<Map<String, Object>> mongoList = (List<Map<String, Object>>) heg.get("mongodb");
            if (mongoList != null) {
                List<MongoDataSource> list = new ArrayList<>();
                for (Map<String, Object> item : mongoList) {
                    MongoDataSource ds = new MongoDataSource();
                    ds.setAppId((String) item.get("app-id"));
                    ds.setUri((String) item.get("uri"));
                    ds.setDatabase((String) item.get("database"));
                    list.add(ds);
                }
                hegConfig.setMongodb(list);
            }
        }

        if (heg.containsKey("proxy-headers")) {
            List<String> headers = (List<String>) heg.get("proxy-headers");
            if (headers != null) {
                hegConfig.setProxyHeaders(headers);
            }
        }

        if (heg.containsKey("log-persistence")) {
            Map<String, Object> lpMap = (Map<String, Object>) heg.get("log-persistence");
            if (lpMap != null) {
                HegConfig.LogPersistence lp = hegConfig.getLogPersistence();
                if (lpMap.containsKey("enabled")) {
                    lp.setEnabled((Boolean) lpMap.get("enabled"));
                }
                if (lpMap.containsKey("sql-log-path")) {
                    lp.setSqlLogPath((String) lpMap.get("sql-log-path"));
                }
                if (lpMap.containsKey("proxy-log-path")) {
                    lp.setProxyLogPath((String) lpMap.get("proxy-log-path"));
                }
                if (lpMap.containsKey("max-file-size-mb")) {
                    lp.setMaxFileSizeMb(toInt(lpMap.get("max-file-size-mb")));
                }
                if (lpMap.containsKey("retain-days")) {
                    lp.setRetainDays(toInt(lpMap.get("retain-days")));
                }
            }
        }
    }

    public synchronized void save() {
        ConfigPersistence persistence = hegConfig.getConfigPersistence();
        if (!"file".equalsIgnoreCase(persistence.getType())) {
            return;
        }

        String filePath = persistence.getFilePath();
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Object> heg = new LinkedHashMap<>();

            CloudConfig cloud = hegConfig.getCloud();
            Map<String, Object> cloudMap = new LinkedHashMap<>();
            cloudMap.put("websocket-url", cloud.getWebsocketUrl());
            cloudMap.put("app-id", cloud.getAppId());
            cloudMap.put("secret-key", cloud.getSecretKey());
            cloudMap.put("api-base-url", cloud.getApiBaseUrl());
            heg.put("cloud", cloudMap);

            if (hegConfig.getMysql() != null) {
                List<Map<String, Object>> mysqlList = new ArrayList<>();
                for (MysqlDataSource ds : hegConfig.getMysql()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app-id", ds.getAppId());
                    m.put("url", ds.getUrl());
                    m.put("username", ds.getUsername());
                    m.put("password", ds.getPassword());
                    m.put("initial-size", ds.getInitialSize());
                    m.put("max-active", ds.getMaxActive());
                    mysqlList.add(m);
                }
                heg.put("mysql", mysqlList);
            }

            if (hegConfig.getRedis() != null) {
                List<Map<String, Object>> redisList = new ArrayList<>();
                for (RedisDataSource ds : hegConfig.getRedis()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app-id", ds.getAppId());
                    m.put("host", ds.getHost());
                    m.put("port", ds.getPort());
                    m.put("password", ds.getPassword());
                    m.put("database", ds.getDatabase());
                    redisList.add(m);
                }
                heg.put("redis", redisList);
            }

            if (hegConfig.getMongodb() != null) {
                List<Map<String, Object>> mongoList = new ArrayList<>();
                for (MongoDataSource ds : hegConfig.getMongodb()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app-id", ds.getAppId());
                    m.put("uri", ds.getUri());
                    m.put("database", ds.getDatabase());
                    mongoList.add(m);
                }
                heg.put("mongodb", mongoList);
            }

            if (hegConfig.getProxyHeaders() != null) {
                heg.put("proxy-headers", hegConfig.getProxyHeaders());
            }

            HegConfig.LogPersistence lp = hegConfig.getLogPersistence();
            Map<String, Object> lpMap = new LinkedHashMap<>();
            lpMap.put("enabled", lp.isEnabled());
            lpMap.put("sql-log-path", lp.getSqlLogPath());
            lpMap.put("proxy-log-path", lp.getProxyLogPath());
            lpMap.put("max-file-size-mb", lp.getMaxFileSizeMb());
            lpMap.put("retain-days", lp.getRetainDays());
            heg.put("log-persistence", lpMap);

            data.put("heg", heg);

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            Yaml yaml = new Yaml(options);

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                yaml.dump(data, writer);
            }

            log.info("配置已持久化到文件: {}", filePath);
        } catch (Exception e) {
            log.error("保存持久化配置文件失败: {}", filePath, e);
        }
    }

    public boolean isFileStorageEnabled() {
        return "file".equalsIgnoreCase(hegConfig.getConfigPersistence().getType());
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return 0;
    }
}