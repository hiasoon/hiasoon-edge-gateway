package com.hiasoon.heg.controller;

import com.alibaba.fastjson.JSON;
import com.hiasoon.heg.config.HegConfig;
import com.hiasoon.heg.monitor.LogCollector;
import com.hiasoon.sdk.SdkConstants;
import com.hiasoon.sdk.sign.NonceGenerator;
import com.hiasoon.sdk.sign.Sign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 接口透明转发控制器
 * 仅做：发出前签名处理 + 接收到时签名验证
 * 不做任何业务处理和响应解析
 */
@Slf4j
@RestController
public class ProxyController {

    private final HegConfig hegConfig;
    private final LogCollector logCollector;
    private final RestTemplate restTemplate;

    public ProxyController(HegConfig hegConfig, LogCollector logCollector) {
        this.hegConfig = hegConfig;
        this.logCollector = logCollector;
        this.restTemplate = new RestTemplate();
    }

    // ==================== POST 请求 ====================

    @PostMapping("/proxy/**")
    public ResponseEntity<?> proxyPost(HttpServletRequest request,
                                        @RequestBody(required = false) String body) {
        return forward(request, body, "POST");
    }

    // ==================== GET 请求 ====================

    @GetMapping("/proxy/**")
    public ResponseEntity<?> proxyGet(HttpServletRequest request) {
        return forward(request, null, "GET");
    }

    // ==================== 核心转发逻辑 ====================

    private ResponseEntity<?> forward(HttpServletRequest request, String body, String method) {
        long startTime = System.currentTimeMillis();

        // 1. 构建目标URL
        String targetUrl = buildTargetUrl(request);
        log.info("网关转发: {} -> {}", method, targetUrl);

        try {
            // 2. 构建请求头（添加签名）
            HttpHeaders headers = buildSignedHeaders(body);

            // 3. 透传客户端请求头（白名单控制）
            Map<String, String> clientHeaders = extractClientHeaders(request);
            for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
                if (!isReservedHeader(entry.getKey())) {
                    headers.set(entry.getKey(), entry.getValue());
                }
            }

            // 4. 构建请求实体
            HttpEntity<String> entity;
            if ("GET".equalsIgnoreCase(method)) {
                entity = new HttpEntity<>(headers);
            } else {
                entity = new HttpEntity<>(body != null ? body : "", headers);
            }

            // 5. 发送请求（透传，不解析响应）
            ResponseEntity<String> response;
            if ("GET".equalsIgnoreCase(method)) {
                response = restTemplate.exchange(targetUrl, HttpMethod.GET, entity, String.class);
            } else {
                response = restTemplate.exchange(targetUrl, HttpMethod.POST, entity, String.class);
            }

            // 6. 验证响应签名（如果有签名头）
            verifyResponseSignature(response, body);

            // 7. 原封不动返回响应
            long duration = System.currentTimeMillis() - startTime;
            int httpCode = response.getStatusCodeValue();
            String status = httpCode >= 200 && httpCode < 400 ? "成功" : "失败";
            logCollector.addProxyLog(method, targetUrl, status, httpCode, duration, null);

            HttpHeaders responseHeaders = new HttpHeaders();
            if (response.getHeaders() != null) {
                for (Map.Entry<String, java.util.List<String>> entry : response.getHeaders().entrySet()) {
                    if (!isHopByHopHeader(entry.getKey())) {
                        responseHeaders.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            return ResponseEntity.status(httpCode)
                    .headers(responseHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());

        } catch (Exception e) {
            log.error("转发异常: method={}, url={}", method, targetUrl, e);
            long duration = System.currentTimeMillis() - startTime;
            logCollector.addProxyLog(method, targetUrl, "失败", 500, duration, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildErrorJson(-1, "转发异常: " + e.getMessage()));
        }
    }

    // ==================== 签名相关方法 ====================

    /**
     * 构建带签名的请求头
     */
    private HttpHeaders buildSignedHeaders(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 添加 APPID
        headers.set(SdkConstants.HEADER_APPID, hegConfig.getCloud().getAppId());
//        headers.set(SdkConstants.HEADER_PLATFORM, "Web");

        // 生成签名
        long timestamp = System.currentTimeMillis();
        String nonce = NonceGenerator.generateNonce();
        String secretKey = hegConfig.getCloud().getSecretKey();

        Map<String, Object> signParams = buildSignParams(body);
        String sign = null;
        try {
            sign = Sign.generateHiasoonSign(signParams, timestamp, nonce, secretKey);
        } catch (Exception e) {
            log.error("签名生成失败", e);
        }

        headers.set(SdkConstants.HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.set(SdkConstants.HEADER_NONCE, nonce);
        if (sign != null) {
            headers.set(SdkConstants.HEADER_SIGN, sign);
        }

        return headers;
    }

    /**
     * 构建签名参数
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSignParams(String body) {
        if (body == null || body.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return JSON.parseObject(body, Map.class);
        } catch (Exception e) {
            log.warn("解析请求体失败，跳过签名参数");
            return new HashMap<>();
        }
    }

    /**
     * 验证响应签名
     */
    private void verifyResponseSignature(ResponseEntity<String> response, String requestBody) {
        HttpHeaders responseHeaders = response.getHeaders();
        if (responseHeaders == null) {
            return;
        }

        String respTimestamp = responseHeaders.getFirst(SdkConstants.HEADER_TIMESTAMP);
        String respNonce = responseHeaders.getFirst(SdkConstants.HEADER_NONCE);
        String respSign = responseHeaders.getFirst(SdkConstants.HEADER_SIGN);

        if (respTimestamp == null || respNonce == null || respSign == null) {
            return;
        }

        // 验证时间戳有效性
        try {
            long ts = Long.parseLong(respTimestamp);
            if (!Sign.isTimestampValid(ts)) {
                log.warn("响应时间戳过期: timestamp={}", ts);
                return;
            }
        } catch (NumberFormatException e) {
            log.warn("响应时间戳格式错误: {}", respTimestamp);
            return;
        }

        // 验证签名
        Map<String, Object> respParams = buildSignParams(response.getBody());
        String secretKey = hegConfig.getCloud().getSecretKey();
        boolean valid = Sign.verifyHiasoonSign(respParams, Long.parseLong(respTimestamp),
                respNonce, respSign, secretKey);

        if (!valid) {
            log.warn("响应签名验证失败: timestamp={}, nonce={}", respTimestamp, respNonce);
        } else {
            log.debug("响应签名验证通过");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建目标URL
     */
    private String buildTargetUrl(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String path = requestURI.replaceFirst("/proxy", "");
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            path = path + "?" + queryString;
        }

        String apiBaseUrl = hegConfig.getCloud().getApiBaseUrl();
        if (!apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl + "/";
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        return apiBaseUrl + path;
    }

    /**
     * 提取客户端请求头
     */
    private Map<String, String> extractClientHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    /**
     * 判断是否为保留头（不应从客户端透传）
     */
    private boolean isReservedHeader(String header) {
        String lower = header.toLowerCase();
        return lower.equalsIgnoreCase(SdkConstants.HEADER_APPID) ||
               lower.equalsIgnoreCase(SdkConstants.HEADER_TIMESTAMP) ||
               lower.equalsIgnoreCase(SdkConstants.HEADER_NONCE) ||
               lower.equalsIgnoreCase(SdkConstants.HEADER_SIGN) ||
               lower.equalsIgnoreCase(SdkConstants.HEADER_PLATFORM) ||
               lower.equalsIgnoreCase("content-type");
    }

    /**
     * 判断是否为hop-by-hop headers（不应被透传）
     */
    private boolean isHopByHopHeader(String header) {
        String lower = header.toLowerCase();
        return lower.equals("connection") ||
               lower.equals("keep-alive") ||
               lower.equals("proxy-authenticate") ||
               lower.equals("proxy-authorization") ||
               lower.equals("te") ||
               lower.equals("trailer") ||
               lower.equals("transfer-encoding") ||
               lower.equals("upgrade");
    }

    /**
     * 构建错误JSON响应
     */
    private String buildErrorJson(int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("timestamp", System.currentTimeMillis());
        return JSON.toJSONString(error);
    }
}
