package org.example.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository pour gérer la persistance des emails dans MySQL.
 */
public class EmailRepository {

    /**
     * Enregistre un nouvel email en base via la procédure stockée.
     */
    public boolean storeEmail(String sender, String recipient, String subject, String body) {
        try (Connection conn = DatabaseManager.getConnection();
             CallableStatement cs = conn.prepareCall("{CALL store_email(?, ?, ?, ?)}")) {
            
            cs.setString(1, sender);
            cs.setString(2, recipient);
            cs.setString(3, subject);
            cs.setString(4, body);

            return executeCallReturningSuccess(cs);
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in storeEmail: " + e.getMessage());
        }
        return false;
    }

    /**
     * Récupère la liste des emails pour un utilisateur donné via la procédure stockée.
     */
    public List<EmailRecord> fetchEmails(String username) {
        List<EmailRecord> emails = new ArrayList<>();
        String normalized = normalizeMailbox(username);
        String sql = "SELECT * FROM emails " +
                "WHERE is_deleted = FALSE " +
                "AND (LOWER(recipient) = ? OR LOWER(recipient) LIKE ?) " +
                "ORDER BY sent_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            ps.setString(2, normalized + "@%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    emails.add(new EmailRecord(
                            rs.getInt("id"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getTimestamp("sent_at"),
                            rs.getBoolean("is_read"),
                            rs.getString("flags"),
                            "received"
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in fetchEmails: " + e.getMessage());
        }
        return emails;
    }

    public List<EmailRecord> fetchSentEmails(String username) {
        List<EmailRecord> emails = new ArrayList<>();
        String normalized = normalizeMailbox(username);
        String sql = "SELECT * FROM emails " +
                "WHERE is_deleted = FALSE " +
                "AND (LOWER(sender) = ? OR LOWER(sender) LIKE ?) " +
                "ORDER BY sent_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            ps.setString(2, normalized + "@%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    emails.add(new EmailRecord(
                            rs.getInt("id"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getTimestamp("sent_at"),
                            rs.getBoolean("is_read"),
                            rs.getString("flags"),
                            "sent"
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in fetchSentEmails: " + e.getMessage());
        }
        return emails;
    }

    /**
     * Supprime un email (soft delete).
     */
    public boolean deleteEmail(int emailId) {
        try (Connection conn = DatabaseManager.getConnection();
             CallableStatement cs = conn.prepareCall("{CALL delete_email(?)}")) {
            cs.setInt(1, emailId);
            return executeCallReturningSuccess(cs);
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in deleteEmail: " + e.getMessage());
        }
        return false;
    }

    /**
     * Met à jour les drapeaux d'un email (IMAP).
     */
    public boolean updateFlags(int emailId, String flags) {
        try (Connection conn = DatabaseManager.getConnection();
             CallableStatement cs = conn.prepareCall("{CALL update_flags(?, ?)}")) {
            cs.setInt(1, emailId);
            cs.setString(2, flags);
            return executeCallReturningSuccess(cs);
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in updateFlags: " + e.getMessage());
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
     * Recherche d'emails (simple exemple pour IMAP SEARCH).
     */
    public List<EmailRecord> searchEmails(String username, String criteria, String value) {
        List<EmailRecord> results = new ArrayList<>();
        // Note: Ici on pourrait faire une version SQL, mais pour rester simple on filtre en Java ou on adapte SQL
        String sql = "SELECT * FROM emails WHERE is_deleted = FALSE " +
                "AND (LOWER(recipient) = ? OR LOWER(recipient) LIKE ?) ";
        
        if (criteria.equalsIgnoreCase("FROM")) {
            sql += "AND sender LIKE ?";
        } else if (criteria.equalsIgnoreCase("SUBJECT")) {
            sql += "AND subject LIKE ?";
        } else if (criteria.equalsIgnoreCase("BODY")) {
            sql += "AND body LIKE ?";
        }
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            String normalized = normalizeMailbox(username);
            ps.setString(1, normalized);
            ps.setString(2, normalized + "@%");
            if (!criteria.equalsIgnoreCase("ALL")) {
                ps.setString(3, "%" + value + "%");
            }
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EmailRecord(
                        rs.getInt("id"),
                        rs.getString("sender"),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getTimestamp("sent_at"),
                        rs.getBoolean("is_read"),
                        rs.getString("flags"),
                        "received"
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EmailRepository] Error in searchEmails: " + e.getMessage());
        }
        return results;
    }

    private String normalizeMailbox(String username) {
        if (username == null) {
            return "";
        }
        String value = username.trim().toLowerCase();
        int at = value.indexOf('@');
        return at > 0 ? value.substring(0, at) : value;
    }
}
