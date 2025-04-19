package com.eric.ericlike;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.eric.ericlike.mapper")
public class EricLikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EricLikeApplication.class, args);
    }

}
