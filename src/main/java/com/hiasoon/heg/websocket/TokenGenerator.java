package com.hiasoon.heg.websocket;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Slf4j
public class TokenGenerator {

    public static String generate(String appId, String secretKey) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String data = appId + ":" + timestamp + ":" + secretKey;
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes("UTF-8"));
            String signature = Base64.getEncoder().encodeToString(hmacBytes);
            
            String tokenContent = appId + ":" + timestamp + ":" + signature;
            return Base64.getEncoder().encodeToString(tokenContent.getBytes("UTF-8"));
        } catch (Exception e) {
            log.error("生成Token失败", e);
            throw new RuntimeException("生成Token失败", e);
        }
    }
}