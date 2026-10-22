package com.hiasoon.heg.config;

import com.hiasoon.sdk.api.HiasoonApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SdkConfig {

    @Bean
    public HiasoonApiClient hiasoonApiClient(HegConfig hegConfig, ConfigPersistenceService configPersistenceService) {
        HegConfig.CloudConfig cloud = hegConfig.getCloud();

        return HiasoonApiClient.builder()
                .appId(cloud.getAppId())
                .appSecret(cloud.getSecretKey())
                .baseUrl(cloud.getApiBaseUrl())
                .platform("HEG")
                .proxyHeaders(hegConfig.getProxyHeaders())
                .build();
    }
}