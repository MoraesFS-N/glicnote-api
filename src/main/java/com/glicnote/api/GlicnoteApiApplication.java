package com.glicnote.api;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GlicnoteApiApplication {
    public static void main(String[] args) { SpringApplication.run(GlicnoteApiApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
