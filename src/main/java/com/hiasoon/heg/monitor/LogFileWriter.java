package com.hiasoon.heg.monitor;

import com.alibaba.fastjson.JSON;
import com.hiasoon.heg.config.ConfigPersistenceService;
import com.hiasoon.heg.config.HegConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class LogFileWriter {

    private final HegConfig hegConfig;
    private final ConfigPersistenceService configPersistenceService;

    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, PrintWriter> writers = new ConcurrentHashMap<>();
    private final Map<String, String> currentFileDates = new ConcurrentHashMap<>();

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public LogFileWriter(HegConfig hegConfig, ConfigPersistenceService configPersistenceService) {
        this.hegConfig = hegConfig;
        this.configPersistenceService = configPersistenceService;
    }

    @PostConstruct
    public void init() {
        refreshConfig();
    }

    public void refreshConfig() {
        HegConfig.LogPersistence config = hegConfig.getLogPersistence();
        if (config.isEnabled()) {
            log.info("日志文件持久化已启用: sqlPath={}, proxyPath={}, maxSize={}MB, retainDays={}",
                    config.getSqlLogPath(), config.getProxyLogPath(), config.getMaxFileSizeMb(), config.getRetainDays());
            createDirectoryIfNotExists(config.getSqlLogPath());
            createDirectoryIfNotExists(config.getProxyLogPath());
        } else {
            log.info("日志文件持久化已禁用");
            closeAllWriters();
        }
    }

    @PreDestroy
    public void destroy() {
        closeAllWriters();
    }

    public void writeSqlLog(LogEntry entry) {
        if (!hegConfig.getLogPersistence().isEnabled()) {
            return;
        }
        try {
            String filePath = getLogFilePath(hegConfig.getLogPersistence().getSqlLogPath(), "sql");
            writeLog(filePath, entry);
        } catch (Exception e) {
            log.error("写入SQL日志文件失败", e);
        }
    }

    public void writeProxyLog(LogEntry entry) {
        if (!hegConfig.getLogPersistence().isEnabled()) {
            return;
        }
        try {
            String filePath = getLogFilePath(hegConfig.getLogPersistence().getProxyLogPath(), "proxy");
            writeLog(filePath, entry);
        } catch (Exception e) {
            log.error("写入Proxy日志文件失败", e);
        }
    }

    private void writeLog(String filePath, LogEntry entry) throws IOException {
        ReentrantLock lock = fileLocks.computeIfAbsent(filePath, k -> new ReentrantLock());
        lock.lock();
        try {
            String today = LocalDate.now().format(DATE_FORMAT);
            String lastDate = currentFileDates.get(filePath);
            if (!today.equals(lastDate)) {
                switchFile(filePath, today);
            }

            PrintWriter writer = writers.get(filePath);
            if (writer == null) {
                writer = createWriter(filePath);
                writers.put(filePath, writer);
                currentFileDates.put(filePath, today);
            }

            long fileSize = getFileSize(filePath);
            if (fileSize >= hegConfig.getLogPersistence().getMaxFileSizeMb() * 1024L * 1024L) {
                rotateFile(filePath);
                writer = createWriter(filePath);
                writers.put(filePath, writer);
                currentFileDates.put(filePath, today);
            }

            String jsonLine = JSON.toJSONString(entry);
            writer.println(jsonLine);
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    private String getLogFilePath(String basePath, String prefix) {
        String today = LocalDate.now().format(DATE_FORMAT);
        return basePath + prefix + "_" + today + ".log";
    }

    private PrintWriter createWriter(String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), true);
    }

    private void switchFile(String basePath, String today) throws IOException {
        String oldPattern = basePath + "*.log";
        closeWriterByBasePath(basePath);
        currentFileDates.put(basePath, today);
    }

    private void rotateFile(String filePath) throws IOException {
        closeWriter(filePath);

        File file = new File(filePath);
        if (file.exists()) {
            String rotatedPath = filePath + "." + System.currentTimeMillis();
            File rotatedFile = new File(rotatedPath);
            file.renameTo(rotatedFile);
            log.info("日志文件已轮转: {} -> {}", filePath, rotatedPath);
        }

        writers.remove(filePath);
    }

    private void closeWriter(String filePath) {
        PrintWriter writer = writers.remove(filePath);
        if (writer != null) {
            writer.close();
        }
    }

    private void closeWriterByBasePath(String basePath) {
        Iterator<Map.Entry<String, PrintWriter>> it = writers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PrintWriter> entry = it.next();
            if (entry.getKey().startsWith(basePath)) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    private void closeAllWriters() {
        for (PrintWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (Exception e) {
                log.error("关闭日志文件写入器失败", e);
            }
        }
        writers.clear();
    }

    private long getFileSize(String filePath) {
        try {
            File file = new File(filePath);
            return file.exists() ? file.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void createDirectoryIfNotExists(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs();
                log.info("创建日志目录: {}", path);
            }
        } catch (Exception e) {
            log.error("创建日志目录失败: {}", path, e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldLogs() {
        HegConfig.LogPersistence config = hegConfig.getLogPersistence();
        if (!config.isEnabled()) {
            return;
        }

        int retainDays = config.getRetainDays();
        LocalDate cutoffDate = LocalDate.now().minusDays(retainDays);

        cleanupDirectory(config.getSqlLogPath(), cutoffDate);
        cleanupDirectory(config.getProxyLogPath(), cutoffDate);
    }

    private void cleanupDirectory(String dirPath, LocalDate cutoffDate) {
        try {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                return;
            }

            File[] files = dir.listFiles((d, name) -> name.endsWith(".log") || name.contains(".log."));
            if (files == null) {
                return;
            }

            int deletedCount = 0;
            for (File file : files) {
                LocalDate fileDate = extractDateFromFileName(file.getName());
                if (fileDate != null && fileDate.isBefore(cutoffDate)) {
                    if (file.delete()) {
                        deletedCount++;
                        log.info("删除过期日志文件: {}", file.getAbsolutePath());
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("清理过期日志完成, 删除 {} 个文件", deletedCount);
            }
        } catch (Exception e) {
            log.error("清理日志目录失败: {}", dirPath, e);
        }
    }

    private LocalDate extractDateFromFileName(String fileName) {
        try {
            for (int i = 0; i < fileName.length() - 9; i++) {
                String sub = fileName.substring(i, i + 10);
                if (sub.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return LocalDate.parse(sub, DATE_FORMAT);
                }
            }
        } catch (Exception e) {
        }
        return null;
    }
}