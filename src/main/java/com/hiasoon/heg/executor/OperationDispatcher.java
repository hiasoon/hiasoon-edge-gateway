package com.hiasoon.heg.executor;

import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OperationDispatcher {

    private final MysqlExecutor mysqlExecutor;
    private final RedisExecutor redisExecutor;
    private final MongoExecutor mongoExecutor;
    private final HttpForwardExecutor httpForwardExecutor;

    public OperationDispatcher(MysqlExecutor mysqlExecutor,
                               RedisExecutor redisExecutor,
                               MongoExecutor mongoExecutor,
                               HttpForwardExecutor httpForwardExecutor) {
        this.mysqlExecutor = mysqlExecutor;
        this.redisExecutor = redisExecutor;
        this.mongoExecutor = mongoExecutor;
        this.httpForwardExecutor = httpForwardExecutor;
    }

    public HegOperationResponse dispatch(HegOperationRequest request) {
        String dbType = request.getDbType();
        
        try {
            if ("MYSQL".equalsIgnoreCase(dbType)) {
                return mysqlExecutor.execute(request);
            } else if ("REDIS".equalsIgnoreCase(dbType)) {
                return redisExecutor.execute(request);
            } else if ("MONGODB".equalsIgnoreCase(dbType)) {
                return mongoExecutor.execute(request);
            } else if ("HTTP".equalsIgnoreCase(dbType)) {
                return httpForwardExecutor.execute(request);
            } else {
                log.error("不支持的数据库类型: {}", dbType);
                return HegOperationResponse.error(request.getRequestId(),
                        "UNSUPPORTED_DB_TYPE", "不支持的数据库类型: " + dbType);
            }
        } catch (Exception e) {
            log.error("执行操作失败: dbType={}", dbType, e);
            return HegOperationResponse.error(request.getRequestId(),
                    "EXECUTION_ERROR", e.getMessage());
        }
    }
}