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
public class RedisOperation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String command;

    private String key;

    private Object value;

    private String field;

    private Long expireSeconds;

    private Map<String, Object> extraParams;

    private Integer dbIndex;
}