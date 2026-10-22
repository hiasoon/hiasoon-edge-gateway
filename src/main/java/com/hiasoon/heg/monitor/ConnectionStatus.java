package com.hiasoon.heg.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionStatus {

    private String name;

    private boolean connected;

    private Long lastCheckTime;

    private String details;

    public String getStatusText() {
        return connected ? "已连接" : "已断开";
    }

    public String getStatusClass() {
        return connected ? "status-connected" : "status-disconnected";
    }

    public String getFormattedTime() {
        if (lastCheckTime == null) {
            return "";
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new java.util.Date(lastCheckTime));
    }
}