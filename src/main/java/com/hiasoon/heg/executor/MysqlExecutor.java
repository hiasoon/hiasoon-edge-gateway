package com.hiasoon.heg.executor;

import com.hiasoon.heg.config.DataSourcePool;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.monitor.LogCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.Date;

@Slf4j
@Component
public class MysqlExecutor {

    private final DataSourcePool dataSourcePool;
    private final LogCollector logCollector;

    public MysqlExecutor(DataSourcePool dataSourcePool, LogCollector logCollector) {
        this.dataSourcePool = dataSourcePool;
        this.logCollector = logCollector;
    }

    public HegOperationResponse execute(HegOperationRequest request) {
        long startTime = System.currentTimeMillis();
        String appId = request.getAppId();
        String sql = request.getSql();
        Map<String, Object> params = request.getParams();
        List<Object> paramList = request.getParamList();
        String operationType = request.getOperationType();
        boolean isCountQuery = Boolean.TRUE.equals(request.getIsCountQuery());

        log.info("========== MySQL执行开始 ==========");
        log.info("requestId: {}", request.getRequestId());
        log.info("appId: {}", appId);
        log.info("operationType: {}", operationType);
        log.info("isCountQuery: {}", isCountQuery);
        log.info("SQL: {}", sql);
        log.info("params Map: {}", params != null ? params.toString() : "null");
        log.info("paramList: {}", paramList != null ? paramList.toString() : "null");

        DataSource dataSource = dataSourcePool.getMysqlDataSource(appId);
        if (dataSource == null) {
            log.error("未配置MySQL数据源: appId={}", appId);
            long duration = System.currentTimeMillis() - startTime;
            logCollector.addSqlLog(sql, formatParams(params), "失败", duration, "未配置MySQL数据源");
            return HegOperationResponse.error(request.getRequestId(),
                    "MYSQL_NOT_CONFIGURED", "未配置MySQL数据源: appId=" + appId);
        }

        try (Connection conn = dataSource.getConnection()) {
            log.info("数据库连接获取成功");

            String[] sqlStatements = splitSqlStatements(sql);
            log.info("SQL语句拆分后数量: {}", sqlStatements.length);

            if (sqlStatements.length > 1) {
                return executeMultipleStatements(conn, request, sqlStatements);
            }

            String finalSql = sqlStatements[0];
            if (params != null) {
                Object firstPageHelper = params.get("First_PageHelper");
                Object secondPageHelper = params.get("Second_PageHelper");
                if (firstPageHelper != null && secondPageHelper != null) {
                    finalSql = finalSql.trim().replaceFirst("LIMIT\\s+\\?", "LIMIT ?, ?");
                    log.info("检测到PageHelper分页参数，SQL修改为: {}", finalSql);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(finalSql)) {
                bindParameters(ps, finalSql, paramList, params);

                if ("SELECT".equalsIgnoreCase(operationType)) {
                    log.info("执行SQL查询");
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        log.info("查询结果列数: {}", columnCount);
                        String[] columns = new String[columnCount];
                        for (int i = 1; i <= columnCount; i++) {
                            columns[i - 1] = metaData.getColumnName(i);
                        }
                        log.info("查询结果列名: {}", Arrays.toString(columns));

                        if (isCountQuery) {
                            Long count = extractCount(rs);
                            log.info("COUNT查询结果: {}", count);
                            Map<String, Object> countResult = new LinkedHashMap<>();
                            countResult.put("COUNT(0)", count);
                            log.info("========== MySQL执行结束 (COUNT) ==========");
                            long duration = System.currentTimeMillis() - startTime;
                            logCollector.addSqlLog(sql, formatParams(params), "成功", duration, null);
                            return HegOperationResponse.success(request.getRequestId(), countResult);
                        }
                        List<Map<String, Object>> result = resultSetToList(rs);
                        log.info("查询结果行数: {}", result.size());
                        if (!result.isEmpty()) {
                            log.info("查询结果第一条: {}", result.get(0));
                        }
                        log.info("========== MySQL执行结束 (SELECT) ==========");
                        long duration = System.currentTimeMillis() - startTime;
                        logCollector.addSqlLog(sql, formatParams(params), "成功", duration, null);
                        return HegOperationResponse.success(request.getRequestId(), result);
                    }
                } else {
                    log.info("执行SQL更新");
                    int affectedRows = ps.executeUpdate();
                    log.info("更新影响行数: {}", affectedRows);
                    log.info("========== MySQL执行结束 (UPDATE) ==========");
                    long duration = System.currentTimeMillis() - startTime;
                    logCollector.addSqlLog(sql, formatParams(params), "成功", duration, null);
                    return HegOperationResponse.success(request.getRequestId(), affectedRows);
                }
            }
        } catch (Exception e) {
            log.error("========== MySQL执行异常 ==========");
            log.error("SQL: {}", sql);
            log.error("错误信息: {}", e.getMessage(), e);
            long duration = System.currentTimeMillis() - startTime;
            logCollector.addSqlLog(sql, formatParams(params), "失败", duration, e.getMessage());
            return HegOperationResponse.error(request.getRequestId(),
                    "MYSQL_EXECUTION_ERROR", e.getMessage());
        }
    }

    private String[] splitSqlStatements(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new String[0];
        }

        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int parenDepth = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char nextChar = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            if (inBlockComment) {
                if (c == '*' && nextChar == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '-' && nextChar == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && nextChar == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                } else if (c == ';' && parenDepth == 0) {
                    String stmt = currentStatement.toString().trim();
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    currentStatement = new StringBuilder();
                    continue;
                }
            }

            currentStatement.append(c);
        }

        String lastStmt = currentStatement.toString().trim();
        if (!lastStmt.isEmpty()) {
            statements.add(lastStmt);
        }

        return statements.toArray(new String[0]);
    }

    private HegOperationResponse executeMultipleStatements(Connection conn, HegOperationRequest request, String[] sqlStatements) throws SQLException {
        log.info("执行多条SQL语句，共{}条", sqlStatements.length);
        long startTime = System.currentTimeMillis();
        StringBuilder sqlBuilder = new StringBuilder();
        for (int i = 0; i < sqlStatements.length; i++) {
            if (i > 0) {
                sqlBuilder.append("; ");
            }
            sqlBuilder.append(sqlStatements[i]);
        }

        conn.setAutoCommit(false);
        int totalAffectedRows = 0;

        try {
            for (int i = 0; i < sqlStatements.length; i++) {
                String stmt = sqlStatements[i];
                log.info("执行第{}条SQL: {}", i + 1, stmt);

                String upperStmt = stmt.toUpperCase().trim();
                if (upperStmt.startsWith("SELECT")) {
                    try (PreparedStatement ps = conn.prepareStatement(stmt)) {
                        try (ResultSet rs = ps.executeQuery()) {
                            List<Map<String, Object>> result = resultSetToList(rs);
                            log.info("查询结果行数: {}", result.size());
                        }
                    }
                } else {
                    try (Statement s = conn.createStatement()) {
                        int affectedRows = s.executeUpdate(stmt);
                        totalAffectedRows += affectedRows;
                        log.info("第{}条SQL影响行数: {}", i + 1, affectedRows);
                    }
                }
            }

            conn.commit();
            log.info("多条SQL执行成功，总计影响行数: {}", totalAffectedRows);
            log.info("========== MySQL执行结束 (MULTIPLE) ==========");
            long duration = System.currentTimeMillis() - startTime;
            logCollector.addSqlLog(sqlBuilder.toString(), formatParams(request.getParams()), "成功", duration, null);
            return HegOperationResponse.success(request.getRequestId(), totalAffectedRows);

        } catch (SQLException e) {
            conn.rollback();
            log.error("多条SQL执行失败，已回滚", e);
            long duration = System.currentTimeMillis() - startTime;
            logCollector.addSqlLog(sqlBuilder.toString(), formatParams(request.getParams()), "失败", duration, e.getMessage());
            throw e;
        }
    }

    private void bindParameters(PreparedStatement ps, String sql, List<Object> paramList, Map<String, Object> params) throws SQLException {
        int placeholderCount = countOccurrences(sql, "?");
        log.info("bindParameters - SQL占位符数量: {}", placeholderCount);

        if (placeholderCount == 0) {
            log.info("SQL中没有占位符，跳过参数绑定");
            return;
        }

        List<Object> orderedParams = new ArrayList<>();
        
        if (paramList != null && !paramList.isEmpty()) {
            orderedParams.addAll(paramList);
            log.info("使用paramList绑定参数，共{}个参数: {}", paramList.size(), paramList);
        } else if (params != null && !params.isEmpty()) {
            orderedParams = extractSimpleTypeValues(params);
            log.info("从params Map提取简单类型参数，共{}个: {}", orderedParams.size(), orderedParams);
        } else {
            log.warn("SQL有{}个占位符但未提供参数，SQL可能执行失败", placeholderCount);
            return;
        }

        int index = 1;
        for (Object value : orderedParams) {
            if (index > placeholderCount) {
                break;
            }
            log.info("绑定参数[{}] = {} (类型: {})", index, value, value != null ? value.getClass().getSimpleName() : "null");
            setParameter(ps, index++, value);
        }

        if (index <= placeholderCount) {
            log.warn("参数数量({})少于占位符数量({})，SQL可能执行失败", index - 1, placeholderCount);
        } else {
            log.info("参数绑定完成，共绑定{}个参数", index - 1);
        }
    }

    private List<Object> extractSimpleTypeValues(Map<String, Object> params) {
        List<Object> simpleValues = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            
            if (value instanceof Number) {
                simpleValues.add(value);
            } else if (value instanceof String) {
                simpleValues.add(value);
            } else if (value instanceof Boolean) {
                simpleValues.add(value);
            } else if (value instanceof Date) {
                simpleValues.add(value);
            } else if (value instanceof java.sql.Date) {
                simpleValues.add(value);
            } else if (value instanceof java.sql.Time) {
                simpleValues.add(value);
            } else if (value instanceof java.sql.Timestamp) {
                simpleValues.add(value);
            } else if (value instanceof Enum) {
                simpleValues.add(((Enum<?>) value).name());
            }
        }
        
        return simpleValues;
    }

    private void setParameter(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else if (value instanceof Date) {
            ps.setTimestamp(index, new Timestamp(((Date) value).getTime()));
        } else if (value instanceof java.sql.Date) {
            ps.setDate(index, (java.sql.Date) value);
        } else if (value instanceof java.sql.Time) {
            ps.setTime(index, (java.sql.Time) value);
        } else if (value instanceof java.sql.Timestamp) {
            ps.setTimestamp(index, (java.sql.Timestamp) value);
        } else if (value instanceof Enum) {
            ps.setString(index, ((Enum<?>) value).name());
        } else {
            ps.setObject(index, value);
        }
    }

    private int countOccurrences(String str, String target) {
        if (str == null || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                String camelCaseName = underscoreToCamelCase(columnName);
                Object value = rs.getObject(i);
                row.put(camelCaseName, value);
            }
            result.add(row);
        }
        return result;
    }

    private String underscoreToCamelCase(String str) {
        if (str == null || !str.contains("_")) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        boolean capitalize = false;
        for (char c : str.toCharArray()) {
            if (c == '_') {
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Long extractCount(ResultSet rs) throws SQLException {
        if (rs.next()) {
            Object value = rs.getObject(1);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                log.warn("COUNT值转换失败: {}", value);
            }
        }
        return 0L;
    }

    private String formatParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue()));
        }
        return sb.toString();
    }
}
