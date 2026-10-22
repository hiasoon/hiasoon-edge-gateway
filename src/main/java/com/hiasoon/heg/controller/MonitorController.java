package com.hiasoon.heg.controller;

import com.hiasoon.heg.config.HegConfig;
import com.hiasoon.heg.config.RuntimeConfigService;
import com.hiasoon.heg.monitor.LogCollector;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.util.*;

@Controller
@RequestMapping("/monitor")
public class MonitorController {

    private final LogCollector logCollector;
    private final HegConfig hegConfig;
    private final RuntimeConfigService runtimeConfigService;

    public MonitorController(LogCollector logCollector, HegConfig hegConfig, RuntimeConfigService runtimeConfigService) {
        this.logCollector = logCollector;
        this.hegConfig = hegConfig;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public String monitorPage(Model model) {
        model.addAttribute("websocketUrl", hegConfig.getCloud().getWebsocketUrl());
        model.addAttribute("apiBaseUrl", hegConfig.getCloud().getApiBaseUrl());
        return "monitor";
    }

    @GetMapping("/config")
    public String configPage(Model model) {
        return "config";
    }

    @GetMapping("/api/connection-status")
    @ResponseBody
    public Map<String, Object> getConnectionStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", logCollector.getConnectionStatuses());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/api/sql-logs")
    @ResponseBody
    public Map<String, Object> getSqlLogs(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", logCollector.getSqlLogs(limit));
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/api/proxy-logs")
    @ResponseBody
    public Map<String, Object> getProxyLogs(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", logCollector.getProxyLogs(limit));
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @PostMapping("/api/clear/sql")
    @ResponseBody
    public Map<String, Object> clearSqlLogs() {
        logCollector.clearSqlLogs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "SQL日志已清空");
        return result;
    }

    @PostMapping("/api/clear/proxy")
    @ResponseBody
    public Map<String, Object> clearProxyLogs() {
        logCollector.clearProxyLogs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "Proxy日志已清空");
        return result;
    }

    @PostMapping("/api/clear/all")
    @ResponseBody
    public Map<String, Object> clearAllLogs() {
        logCollector.clearAllLogs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "所有日志已清空");
        return result;
    }

    @GetMapping("/api/config")
    @ResponseBody
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", runtimeConfigService.getCurrentConfig());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @PostMapping("/api/config/cloud")
    @ResponseBody
    public Map<String, Object> updateCloudConfig(@RequestBody Map<String, String> cloudConfig) {
        return runtimeConfigService.updateCloudConfig(cloudConfig);
    }

    @PostMapping("/api/config/proxy-headers")
    @ResponseBody
    public Map<String, Object> updateProxyHeaders(@RequestBody List<String> headers) {
        return runtimeConfigService.updateProxyHeaders(headers);
    }

    @DeleteMapping("/api/config/proxy-headers/{header}")
    @ResponseBody
    public Map<String, Object> removeProxyHeader(@PathVariable String header) {
        return runtimeConfigService.removeProxyHeader(header);
    }

    @PostMapping("/api/config/mysql")
    @ResponseBody
    public Map<String, Object> updateMysqlConfig(@RequestBody List<Map<String, Object>> mysqlList) {
        return runtimeConfigService.updateMysqlDataSource(mysqlList);
    }

    @PostMapping("/api/config/redis")
    @ResponseBody
    public Map<String, Object> updateRedisConfig(@RequestBody List<Map<String, Object>> redisList) {
        return runtimeConfigService.updateRedisDataSource(redisList);
    }

    @PostMapping("/api/config/mongodb")
    @ResponseBody
    public Map<String, Object> updateMongoConfig(@RequestBody List<Map<String, Object>> mongoList) {
        return runtimeConfigService.updateMongoDataSource(mongoList);
    }

    @PostMapping("/api/config/reload")
    @ResponseBody
    public Map<String, Object> reloadAll() {
        return runtimeConfigService.reloadAll();
    }

    @PostMapping("/api/config/log-persistence")
    @ResponseBody
    public Map<String, Object> updateLogPersistence(@RequestBody Map<String, Object> logConfig) {
        return runtimeConfigService.updateLogPersistence(logConfig);
    }

    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        
        Map<String, Object> data = new HashMap<>();
        data.put("uptime", ManagementFactory.getRuntimeMXBean().getStartTime());
        data.put("mysqlPoolSize", getMysqlPoolSize());
        data.put("redisPoolSize", getRedisPoolSize());
        data.put("mongoPoolSize", getMongoPoolSize());
        
        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    private int getMysqlPoolSize() {
        List<?> mysqlList = hegConfig.getMysql();
        return mysqlList != null ? mysqlList.size() : 0;
    }

    private int getRedisPoolSize() {
        List<?> redisList = hegConfig.getRedis();
        return redisList != null ? redisList.size() : 0;
    }

    private int getMongoPoolSize() {
        List<?> mongoList = hegConfig.getMongodb();
        return mongoList != null ? mongoList.size() : 0;
    }
}