package com.eric.ericlike;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.eric.ericlike.mapper")
@EnableScheduling
public class EricLikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EricLikeApplication.class, args);
    }

}
