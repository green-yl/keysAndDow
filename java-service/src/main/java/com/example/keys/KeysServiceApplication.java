package com.example.keys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class KeysServiceApplication {
    private static final Logger log = LoggerFactory.getLogger(KeysServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(KeysServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner initInfra(DataSource dataSource) {
        return args -> {
            try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            boolean isWin = System.getProperty("os.name").toLowerCase().startsWith("win");
            String cache = System.getProperty("SOURCE_CACHE_DIR",
                    isWin ? "D:/srv/source_cache" : "/srv/source_cache");
            String releases = System.getProperty("RELEASES_POOL_DIR",
                    isWin ? "D:/srv/releases_pool" : "/srv/releases_pool");
            Files.createDirectories(Paths.get(cache));
            Files.createDirectories(Paths.get(releases));
            log.info("启动成功，访问 http://localhost:3003");
        };
    }
}


