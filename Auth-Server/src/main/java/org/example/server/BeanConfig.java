package org.example.server;

import org.example.db.DatabaseManager;
import org.example.db.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }

    @Bean
    public JwtManager jwtManager() {
        return new JwtManager();
    }

    @Bean
    public CommandLineRunner databaseInitializer(
            @Value("${db.url}") String dbUrl,
            @Value("${db.user}") String dbUser,
            @Value("${db.pass}") String dbPass
    ) {
        return args -> DatabaseManager.init(dbUrl, dbUser, dbPass);
    }
}
