package com.hiasoon.heg.controller;

import com.hiasoon.heg.config.HegConfig;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.entity.MongoOperation;
import com.hiasoon.heg.entity.RedisOperation;
import com.hiasoon.heg.executor.OperationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/heg/test")
public class HegTestController {

    private final OperationDispatcher dispatcher;
    private final HegConfig hegConfig;

    public HegTestController(OperationDispatcher dispatcher, HegConfig hegConfig) {
        this.dispatcher = dispatcher;
        this.hegConfig = hegConfig;
    }

    @PostMapping("/mysql")
    public HegOperationResponse testMysql(@RequestBody Map<String, Object> request) {
        String sql = (String) request.get("sql");
        String operationType = (String) request.get("operationType");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        String appId = (String) request.get("appId");

        HegOperationRequest req = HegOperationRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .appId(appId != null ? appId : hegConfig.getCloud().getAppId())
                .dbType("MYSQL")
                .operationType(operationType != null ? operationType : "SELECT")
                .sql(sql)
                .params(params)
                .timestamp(System.currentTimeMillis())
                .build();

        log.info("MySQL测试: sql={}", sql);
        return dispatcher.dispatch(req);
    }

    @PostMapping("/redis")
    public HegOperationResponse testRedis(@RequestBody Map<String, Object> request) {
        String command = (String) request.get("command");
        String key = (String) request.get("key");
        Object value = request.get("value");
        String field = (String) request.get("field");
        String appId = (String) request.get("appId");

        RedisOperation redisOp = RedisOperation.builder()
                .command(command)
                .key(key)
                .value(value)
                .field(field)
                .build();

        HegOperationRequest req = HegOperationRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .appId(appId != null ? appId : hegConfig.getCloud().getAppId())
                .dbType("REDIS")
                .redisOp(redisOp)
                .timestamp(System.currentTimeMillis())
                .build();

        log.info("Redis测试: command={}, key={}", command, key);
        return dispatcher.dispatch(req);
    }

    @PostMapping("/mongodb")
    public HegOperationResponse testMongo(@RequestBody Map<String, Object> request) {
        String operationType = (String) request.get("operationType");
        String collectionName = (String) request.get("collectionName");
        String query = (String) request.get("query");
        String document = (String) request.get("document");
        String updateOperators = (String) request.get("updateOperators");
        String appId = (String) request.get("appId");

        MongoOperation mongoOp = MongoOperation.builder()
                .operationType(operationType)
                .collectionName(collectionName)
                .query(query)
                .document(document)
                .updateOperators(updateOperators)
                .build();

        HegOperationRequest req = HegOperationRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .appId(appId != null ? appId : hegConfig.getCloud().getAppId())
                .dbType("MONGODB")
                .mongoOp(mongoOp)
                .timestamp(System.currentTimeMillis())
                .build();

        log.info("MongoDB测试: operationType={}, collection={}", operationType, collectionName);
        return dispatcher.dispatch(req);
    }

    @PostMapping("/http-forward")
    public HegOperationResponse testHttpForward(@RequestBody Map<String, Object> request) {
        String url = (String) request.get("url");
        String method = (String) request.get("method");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) request.get("headers");
        Object body = request.get("body");

        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        params.put("method", method != null ? method : "GET");
        params.put("headers", headers);
        params.put("body", body);

        HegOperationRequest req = HegOperationRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .appId(hegConfig.getCloud().getAppId())
                .dbType("HTTP")
                .params(params)
                .timestamp(System.currentTimeMillis())
                .build();

        log.info("HTTP转发测试: method={}, url={}", method, url);
        return dispatcher.dispatch(req);
    }

    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "HEG-Verify");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}