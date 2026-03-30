package org.example.db;

import java.sql.Timestamp;

/**
 * POJO représentant un enregistrement d'email dans la base de données.
 */
public class EmailRecord {
    private int id;
    private String sender;
    private String recipient;
    private String subject;
    private String body;
    private Timestamp sentAt;
    private boolean isRead;
    private String flags;

    public EmailRecord(int id, String sender, String recipient, String subject, 
                       String body, Timestamp sentAt, boolean isRead, String flags) {
        this.id = id;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.sentAt = sentAt;
        this.isRead = isRead;
        this.flags = flags;
    }

    // Getters
    public int getId() { return id; }
    public String getSender() { return sender; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Timestamp getSentAt() { return sentAt; }
    public boolean isRead() { return isRead; }
    public String getFlags() { return flags; }


    @Override
    public String toString() {
        return "EmailRecord{" +
                "id=" + id +
                ", sender='" + sender + '\'' +
                ", recipient='" + recipient + '\'' +
                ", subject='" + subject + '\'' +
                ", sentAt=" + sentAt +
                ", flags='" + flags + '\'' +
                '}';
    }

    public void setFlags(String updated) {
        this.flags = updated;
        if (updated != null) {
            this.isRead = updated.contains("\\Seen");
        }
    }
}
