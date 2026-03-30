-- Initialisation de la base de données messagerie

CREATE DATABASE IF NOT EXISTS messagerie;
USE messagerie;

-- Table des utilisateurs
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    salt VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE
);

-- Table des emails
CREATE TABLE IF NOT EXISTS emails (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sender VARCHAR(100) NOT NULL,
    recipient VARCHAR(100) NOT NULL,
    subject VARCHAR(255),
    body TEXT,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_read BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    flags VARCHAR(255) DEFAULT ''
);

DELIMITER //

-- Procédure d'authentification : retourne le hash et le salt pour un utilisateur
CREATE PROCEDURE IF NOT EXISTS authenticate_user(
    IN p_username VARCHAR(50), 
    OUT p_hash VARCHAR(255), 
    OUT p_salt VARCHAR(255)
)
BEGIN
    SELECT password_hash, salt INTO p_hash, p_salt
    FROM users WHERE username = p_username AND active = TRUE;
END //

-- Procédure de stockage d'un email
CREATE PROCEDURE IF NOT EXISTS store_email(
    IN p_sender VARCHAR(100),
    IN p_recipient VARCHAR(100),
    IN p_subject VARCHAR(255),
    IN p_body TEXT
)
BEGIN
    INSERT INTO emails(sender, recipient, subject, body)
    VALUES (p_sender, p_recipient, p_subject, p_body);
END //

-- Récupération de tous les emails pour un destinataire (POP3/IMAP)
CREATE PROCEDURE IF NOT EXISTS fetch_emails(
    IN p_username VARCHAR(50)
)
BEGIN
    SELECT id, sender, recipient, subject, body, sent_at, is_read, flags
    FROM emails
    WHERE recipient LIKE CONCAT(p_username, '%') AND is_deleted = FALSE
    ORDER BY sent_at ASC;
END //

-- Marquage d'un email comme supprimé (Soft Delete)
CREATE PROCEDURE IF NOT EXISTS delete_email(
    IN p_email_id INT
)
BEGIN
    UPDATE emails SET is_deleted = TRUE WHERE id = p_email_id;
END //

-- Mise à jour du mot de passe
CREATE PROCEDURE IF NOT EXISTS update_password(
    IN p_username VARCHAR(50),
    IN p_hash VARCHAR(255),
    IN p_salt VARCHAR(255)
)
BEGIN
    UPDATE users SET password_hash = p_hash, salt = p_salt
    WHERE username = p_username;
END //

-- Mise à jour des drapeaux (IMAP)
CREATE PROCEDURE IF NOT EXISTS update_flags(
    IN p_email_id INT,
    IN p_flags VARCHAR(255)
)
BEGIN
    UPDATE emails SET flags = p_flags WHERE id = p_email_id;
END //

DELIMITER ;
