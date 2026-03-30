package org.example.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Gère le pool de connexions à la base de données MySQL via HikariCP.
 */
public class DatabaseManager {
    
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/messagerie?useSSL=false&serverTimezone=UTC";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASS = "046241546";

    private static HikariDataSource dataSource;
    
    private static synchronized void ensureInitialized() {
        if (dataSource == null) {
            String url = getPropertyOrEnv("DB_URL", "db.url", DEFAULT_URL);
            String user = getPropertyOrEnv("DB_USER", "db.user", DEFAULT_USER);
            String pass = getPropertyOrEnv("DB_PASS", "db.pass", DEFAULT_PASS);
            try {
                init(url, user, pass);
            } catch (Exception e) {
                System.err.println("[DatabaseManager] WARNING: Implicit initialization failed: " + e.getMessage());
                // On ne relance pas l'exception ici pour éviter ExceptionInInitializerError si appelé via static
            }
        }
    }

    /**
     * Initialise manuellement le pool (doit être appelé avant getConnection).
     */
    public static synchronized void init(String url, String user, String pass) {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        
        // Optimisations pour le pool de connexions
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);
        config.setConnectionTimeout(20000);
        
        // Compatibilité H2 (certaines propriétés MySQL font échouer H2)
        if (url != null && url.contains("jdbc:mysql")) {
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }
        
        try {
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            System.err.println("[DatabaseManager] CRITICAL: Failed to initialize pool for URL: " + url);
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Retourne une connexion à partir du pool.
     */
    public static Connection getConnection() throws SQLException {
        ensureInitialized();
        if (dataSource == null) {
            throw new SQLException("DatabaseManager has not been initialized. Call init() first.");
        }
        return dataSource.getConnection();
    }
    
    /**
     * Ferme proprement le pool de connexions (utile lors de l'arrêt du serveur).
     */
    public static void shutdown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
    
    private static String getPropertyOrEnv(String envKey, String propKey, String defaultValue) {
        String value = System.getProperty(propKey);
        if (value == null || value.isEmpty()) {
            value = System.getenv(envKey);
        }
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
