package com.devmind;

import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindQuotaProperties;
import com.devmind.config.DevMindSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({DevMindProperties.class, DevMindSecurityProperties.class, DevMindQuotaProperties.class})
@EnableScheduling
public class DevMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevMindApplication.class, args);
    }
}
