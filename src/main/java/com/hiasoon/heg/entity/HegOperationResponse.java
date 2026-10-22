package com.hiasoon.heg.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HegOperationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;

    private boolean success;

    private String message;

    private Object data;

    private Long timestamp;

    private String errorCode;

    public static HegOperationResponse success(String requestId, Object data) {
        return HegOperationResponse.builder()
                .requestId(requestId)
                .success(true)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static HegOperationResponse error(String requestId, String message) {
        return HegOperationResponse.builder()
                .requestId(requestId)
                .success(false)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static HegOperationResponse error(String requestId, String errorCode, String message) {
        return HegOperationResponse.builder()
                .requestId(requestId)
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}