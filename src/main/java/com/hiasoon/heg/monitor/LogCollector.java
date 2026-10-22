package com.hiasoon.heg.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class LogCollector {

    private static final int MAX_LOG_SIZE = 500;

    private final CopyOnWriteArrayList<LogEntry> sqlLogs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LogEntry> proxyLogs = new CopyOnWriteArrayList<>();
    private final Map<String, ConnectionStatus> connectionStatusMap = new ConcurrentHashMap<>();
    private final AtomicInteger logIdGenerator = new AtomicInteger(0);
    private final LogFileWriter logFileWriter;

    public LogCollector(LogFileWriter logFileWriter) {
        this.logFileWriter = logFileWriter;
    }

    public LogEntry addSqlLog(String sql, String params, String status, long duration, String errorMsg) {
        LogEntry entry = LogEntry.builder()
                .id(logIdGenerator.incrementAndGet())
                .type("SQL")
                .timestamp(System.currentTimeMillis())
                .content(sql)
                .params(params)
                .status(status)
                .duration(duration)
                .errorMsg(errorMsg)
                .build();

        sqlLogs.add(entry);
        trimLogs(sqlLogs);
        logFileWriter.writeSqlLog(entry);
        log.debug("SQL日志已记录: id={}, sql={}", entry.getId(), sql);
        return entry;
    }

    public LogEntry addProxyLog(String method, String path, String status, int httpCode, long duration, String errorMsg) {
        LogEntry entry = LogEntry.builder()
                .id(logIdGenerator.incrementAndGet())
                .type("PROXY")
                .timestamp(System.currentTimeMillis())
                .content(method + " " + path)
                .params(null)
                .status(status)
                .duration(duration)
                .httpCode(httpCode)
                .errorMsg(errorMsg)
                .build();

        proxyLogs.add(entry);
        trimLogs(proxyLogs);
        logFileWriter.writeProxyLog(entry);
        log.debug("Proxy日志已记录: id={}, {} {}", entry.getId(), method, path);
        return entry;
    }

    public void updateConnectionStatus(String name, boolean connected, String details) {
        ConnectionStatus status = ConnectionStatus.builder()
                .name(name)
                .connected(connected)
                .lastCheckTime(System.currentTimeMillis())
                .details(details)
                .build();
        connectionStatusMap.put(name, status);
        log.debug("连接状态已更新: name={}, connected={}", name, connected);
    }

    public List<LogEntry> getSqlLogs(int limit) {
        return getLogs(sqlLogs, limit);
    }

    public List<LogEntry> getProxyLogs(int limit) {
        return getLogs(proxyLogs, limit);
    }

    public List<ConnectionStatus> getConnectionStatuses() {
        return new ArrayList<>(connectionStatusMap.values());
    }

    public void clearSqlLogs() {
        sqlLogs.clear();
        log.info("SQL日志已清空");
    }

    public void clearProxyLogs() {
        proxyLogs.clear();
        log.info("Proxy日志已清空");
    }

    public void clearAllLogs() {
        sqlLogs.clear();
        proxyLogs.clear();
        log.info("所有日志已清空");
    }

    private List<LogEntry> getLogs(CopyOnWriteArrayList<LogEntry> logs, int limit) {
        if (limit <= 0 || limit > logs.size()) {
            return new ArrayList<>(logs);
        }
        List<LogEntry> result = new ArrayList<>();
        int start = logs.size() - limit;
        for (int i = start; i < logs.size(); i++) {
            result.add(logs.get(i));
        }
        return result;
    }

    private void trimLogs(CopyOnWriteArrayList<LogEntry> logs) {
        while (logs.size() > MAX_LOG_SIZE) {
            logs.remove(0);
        }
    }
}