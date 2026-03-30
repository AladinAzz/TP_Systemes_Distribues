package org.example.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository pour gérer la persistance des utilisateurs dans MySQL.
 */
public class UserRepository {

    /**
     * Récupère le hash et le salt du mot de passe d'un utilisateur.
     * @param username Le nom de l'utilisateur.
     * @return Un tableau [hash, salt] ou null si non trouvé.
     */
    public String[] getAuthData(String username) {
        try (Connection conn = DatabaseManager.getConnection();
             CallableStatement cs = conn.prepareCall("{CALL authenticate_user(?, ?, ?)}")) {
            
            cs.setString(1, username);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.registerOutParameter(3, Types.VARCHAR);
            
            cs.execute();
            
            String hash = cs.getString(2);
            String salt = cs.getString(3);
            
            if (hash != null) {
                return new String[]{hash, salt};
            }
        } catch (SQLException e) {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT password_hash, salt FROM users WHERE username = ? AND active = TRUE")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new String[]{rs.getString("password_hash"), rs.getString("salt")};
                    }
                }
            } catch (SQLException fallbackError) {
                System.err.println("[UserRepository] Error in getAuthData: " + fallbackError.getMessage());
            }
        }
        return null;
    }

    /**
     * Vérifie si un utilisateur existe.
     */
    public boolean exists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ? AND active = TRUE";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[UserRepository] Error in exists: " + e.getMessage());
        }
        return false;
    }

    /**
     * Crée un nouvel utilisateur.
     */
    public boolean createUser(String username, String hash, String salt) {
        String sql = "INSERT INTO users (username, password_hash, salt) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[UserRepository] Error in createUser: " + e.getMessage());
        }
        return false;
    }

    /**
     * Met à jour le mot de passe d'un utilisateur.
     */
    public boolean updatePassword(String username, String hash, String salt) {
        try (Connection conn = DatabaseManager.getConnection();
             CallableStatement cs = conn.prepareCall("{CALL update_password(?, ?, ?)}")) {
            cs.setString(1, username);
            cs.setString(2, hash);
            cs.setString(3, salt);
            return executeCallReturningSuccess(cs);
        } catch (SQLException e) {
            System.err.println("[UserRepository] Error in updatePassword: " + e.getMessage());
        }
        return false;
    }

    private boolean executeCallReturningSuccess(CallableStatement cs) throws SQLException {
        boolean hasResultSet = cs.execute();
        if (hasResultSet) {
            try (ResultSet rs = cs.getResultSet()) {
                if (rs != null && rs.next()) {
                    Object first = rs.getObject(1);
                    if (first instanceof Number n) {
                        return n.intValue() > 0;
                    }
                }
            }
        }
        return cs.getUpdateCount() > 0;
    }

    /**
     * Supprime (désactive) un utilisateur.
     */
    public boolean deleteUser(String username) {
        String sql = "UPDATE users SET active = FALSE WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[UserRepository] Error in deleteUser: " + e.getMessage());
        }
        return false;
    }

    /**
     * Récupère tous les utilisateurs actifs.
     */
    public List<String> getAllUsers() {
        List<String> userList = new ArrayList<>();
        String sql = "SELECT username FROM users WHERE active = TRUE";
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                userList.add(rs.getString("username"));
            }
        } catch (SQLException e) {
            System.err.println("[UserRepository] Error in getAllUsers: " + e.getMessage());
        }
        return userList;
    }
}
