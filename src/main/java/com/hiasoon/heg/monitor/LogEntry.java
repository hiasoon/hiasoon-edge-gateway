package com.hiasoon.heg.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEntry {

    private int id;

    private String type;

    private Long timestamp;

    private String content;

    private String params;

    private String status;

    private Long duration;

    private Integer httpCode;

    private String errorMsg;

    public String getFormattedTime() {
        if (timestamp == null) {
            return "";
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        return sdf.format(new java.util.Date(timestamp));
    }
}