package com.hiasoon.heg.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MongoOperation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String operationType;

    private String collectionName;

    private String query;

    private String document;

    private String updateOperators;

    private String sort;

    private String projection;

    private Integer skip;

    private Integer limit;

    private String aggregationPipeline;

    private Map<String, Object> options;

    private String databaseName;
}