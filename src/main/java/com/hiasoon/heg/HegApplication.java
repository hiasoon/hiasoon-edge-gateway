package com.hiasoon.heg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication(exclude = {
        MongoAutoConfiguration.class,
        MongoDataAutoConfiguration.class
})
@EnableConfigurationProperties
@EnableScheduling
public class HegApplication {

    public static void main(String[] args) {
        log.info("========== HEG正在启动 ==========");
        SpringApplication.run(HegApplication.class, args);
        log.info("========== HEG启动完成 ==========");
        log.info("========== http://localhost:9000/monitor ==========");
    }
}