package com.hiasoon.heg.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HegOperationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;

    private String appId;

    private String dbType;

    private String operationType;

    private String sql;

    private Map<String, Object> params;

    private List<Object> paramList;

    private RedisOperation redisOp;

    private MongoOperation mongoOp;

    private Long timestamp;

    private String moduleId;

    private Integer pageNum;

    private Integer pageSize;

    private Boolean needPagination;

    private Boolean isCountQuery;
}