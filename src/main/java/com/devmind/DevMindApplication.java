package com.devmind;

import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindQuotaProperties;
import com.devmind.config.DevMindSecurityProperties;
import com.devmind.config.McpSecurityProperties;
import com.devmind.config.McpServerProperties;
import com.devmind.config.N8nProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({DevMindProperties.class, DevMindSecurityProperties.class, DevMindQuotaProperties.class, McpSecurityProperties.class, McpServerProperties.class, N8nProperties.class})
@EnableScheduling
public class DevMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevMindApplication.class, args);
    }
}
