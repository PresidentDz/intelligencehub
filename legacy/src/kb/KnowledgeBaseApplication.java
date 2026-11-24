package com.zhixu.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.nio.file.Path;

@SpringBootApplication
public class KnowledgeBaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }

    @Bean
    DataSource dataSource() {
        String location = System.getenv().getOrDefault("KB_DB", "data/kb.db");
        Path path = Path.of(location).toAbsolutePath();
        path.getParent().toFile().mkdirs();
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.sqlite.JDBC");
        source.setUrl("jdbc:sqlite:" + path + "?foreign_keys=on&busy_timeout=10000");
        return source;
    }

    @Bean
    WebMvcConfigurer staticFiles() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**")
                        .addResourceLocations(Path.of("static").toAbsolutePath().toUri() + "/", "classpath:/static/");
            }
        };
    }
}
