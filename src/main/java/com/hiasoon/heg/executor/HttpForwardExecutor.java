package com.hiasoon.heg.executor;

import com.alibaba.fastjson.JSON;
import com.hiasoon.heg.config.HegConfig;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.sdk.SdkConstants;
import com.hiasoon.sdk.sign.NonceGenerator;
import com.hiasoon.sdk.sign.Sign;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 转发执行器（通过 WebSocket 指令调用）
 * 仅做：发出前签名处理 + 接收到时签名验证
 * 不做任何业务处理和响应解析
 */
@Slf4j
@Component
public class HttpForwardExecutor {

    private final OkHttpClient okHttpClient;
    private final HegConfig hegConfig;

    public HttpForwardExecutor(HegConfig hegConfig) {
        this.hegConfig = hegConfig;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public HegOperationResponse execute(HegOperationRequest request) {
        Map<String, Object> params = request.getParams();
        if (params == null) {
            return HegOperationResponse.error(request.getRequestId(),
                    "HTTP_PARAMS_MISSING", "HTTP转发参数为空");
        }

        String url = (String) params.get("url");
        String method = (String) params.get("method");
        Map<String, String> headers = (Map<String, String>) params.get("headers");
        Object body = params.get("body");

        if (url == null || url.isEmpty()) {
            return HegOperationResponse.error(request.getRequestId(),
                    "HTTP_URL_MISSING", "HTTP转发URL为空");
        }

        if (method == null || method.isEmpty()) {
            method = "GET";
        }

        try {
            String targetUrl = transformUrl(url);
            log.info("HTTP转发: {} {} -> {}", method, url, targetUrl);

            // 1. 构建带签名的请求
            Request.Builder builder = new Request.Builder().url(targetUrl);
            buildSignedHeaders(builder, body);

            // 2. 透传自定义请求头（保留头除外）
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!isReservedHeader(entry.getKey())) {
                        builder.addHeader(entry.getKey(), entry.getValue());
                    }
                }
            }

            // 3. 构建请求体
            String bodyJson = null;
            if (body != null) {
                if (body instanceof String) {
                    bodyJson = (String) body;
                } else {
                    bodyJson = JSON.toJSONString(body);
                }
            }

            RequestBody requestBody = null;
            if (bodyJson != null && !bodyJson.isEmpty()) {
                requestBody = RequestBody.create(bodyJson,
                        MediaType.parse("application/json; charset=utf-8"));
            }

            builder.method(method.toUpperCase(), requestBody);

            Request req = builder.build();

            // 4. 发送请求
            try (Response response = okHttpClient.newCall(req).execute()) {
                // 5. 验证响应签名
                verifyResponseSignatures(response);

                // 6. 原封不动返回响应（包含原始状态码、头、体）
                Map<String, Object> responseData = new HashMap<>();
                responseData.put("statusCode", response.code());
                responseData.put("headers", extractResponseHeaders(response));
                ResponseBody responseBody = response.body();
                responseData.put("body", responseBody != null ? responseBody.string() : null);

                return HegOperationResponse.success(request.getRequestId(), responseData);
            }

        } catch (Exception e) {
            log.error("HTTP转发异常: method={}, url={}", method, url, e);
            return HegOperationResponse.error(request.getRequestId(),
                    "HTTP_FORWARD_ERROR", e.getMessage());
        }
    }

    // ==================== 签名相关方法 ====================

    /**
     * 构建带签名的请求头
     */
    private void buildSignedHeaders(Request.Builder builder, Object body) throws Exception {
        builder.addHeader(SdkConstants.HEADER_APPID, hegConfig.getCloud().getAppId());
        builder.addHeader(SdkConstants.HEADER_PLATFORM, "Web");

        long timestamp = System.currentTimeMillis();
        String nonce = NonceGenerator.generateNonce();
        String secretKey = hegConfig.getCloud().getSecretKey();

        Map<String, Object> signParams = buildSignParams(body);
        String sign = Sign.generateHiasoonSign(signParams, timestamp, nonce, secretKey);

        builder.addHeader(SdkConstants.HEADER_TIMESTAMP, String.valueOf(timestamp));
        builder.addHeader(SdkConstants.HEADER_NONCE, nonce);
        builder.addHeader(SdkConstants.HEADER_SIGN, sign);
    }

    /**
     * 构建签名参数
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSignParams(Object body) {
        if (body == null) {
            return new HashMap<>();
        }
        if (body instanceof Map) {
            return (Map<String, Object>) body;
        }
        if (body instanceof String) {
            try {
                return JSON.parseObject((String) body, Map.class);
            } catch (Exception e) {
                return new HashMap<>();
            }
        }
        return new HashMap<>();
    }

    /**
     * 验证响应签名
     */
    private void verifyResponseSignatures(Response response) {
        String respTimestamp = response.header(SdkConstants.HEADER_TIMESTAMP);
        String respNonce = response.header(SdkConstants.HEADER_NONCE);
        String respSign = response.header(SdkConstants.HEADER_SIGN);

        if (respTimestamp == null || respNonce == null || respSign == null) {
            return;
        }

        // 验证时间戳
        try {
            long ts = Long.parseLong(respTimestamp);
            if (!Sign.isTimestampValid(ts)) {
                log.warn("HTTP响应时间戳过期: timestamp={}", ts);
                return;
            }
        } catch (NumberFormatException e) {
            log.warn("HTTP响应时间戳格式错误: {}", respTimestamp);
            return;
        }

        // 验证签名
        String secretKey = hegConfig.getCloud().getSecretKey();
        String responseBody = null;
        try {
            ResponseBody body = response.body();
            responseBody = body != null ? body.string() : null;
        } catch (Exception e) {
            log.warn("读取响应体失败，跳过签名验证");
            return;
        }

        Map<String, Object> respParams = new HashMap<>();
        if (responseBody != null && !responseBody.isEmpty()) {
            try {
                respParams = JSON.parseObject(responseBody, Map.class);
            } catch (Exception ignored) {
            }
        }

        boolean valid = Sign.verifyHiasoonSign(respParams, Long.parseLong(respTimestamp),
                respNonce, respSign, secretKey);

        if (!valid) {
            log.warn("HTTP响应签名验证失败: timestamp={}, nonce={}", respTimestamp, respNonce);
        } else {
            log.debug("HTTP响应签名验证通过");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 转换URL（去掉/heg/前缀）
     */
    private String transformUrl(String originalUrl) throws Exception {
        URL url = new URL(originalUrl);
        String path = url.getPath();

        if (path.startsWith("/heg/")) {
            path = path.substring(4);
        }

        String apiBaseUrl = hegConfig.getCloud().getApiBaseUrl();
        if (!apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl + "/";
        }

        return apiBaseUrl + path;
    }

    /**
     * 判断是否为保留头
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
     * 提取响应头
     */
    private Map<String, String> extractResponseHeaders(Response response) {
        Map<String, String> headers = new HashMap<>();
        for (String name : response.headers().names()) {
            headers.put(name, response.header(name));
        }
        return headers;
    }
}
