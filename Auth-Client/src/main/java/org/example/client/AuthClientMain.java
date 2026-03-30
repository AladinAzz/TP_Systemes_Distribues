package org.example.client;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Interface graphique d'administration des utilisateurs.
 * Mise à jour pour utiliser l'API REST via AuthRestClient.
 */
public class AuthClientMain extends JFrame {
    private final AuthRestClient authClient;
    private DefaultListModel<String> listModel;
    private JList<String> userList;

    public AuthClientMain() {
        this.authClient = new AuthRestClient();
        
        setTitle("Administration des Utilisateurs (Client REST)");
        setSize(500, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initComponents();
        refreshUserList();
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Centre : Liste des utilisateurs
        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(userList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Droite : Boutons
        JPanel buttonPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        
        JButton btnAdd = new JButton("Ajouter");
        JButton btnEdit = new JButton("Modifier Mdp");
        JButton btnDelete = new JButton("Supprimer");
        JButton btnRefresh = new JButton("Actualiser");

        btnAdd.addActionListener(e -> addUser());
        btnEdit.addActionListener(e -> editUser());
        btnDelete.addActionListener(e -> deleteUser());
        btnRefresh.addActionListener(e -> refreshUserList());

        buttonPanel.add(btnAdd);
        buttonPanel.add(btnEdit);
        buttonPanel.add(btnDelete);
        buttonPanel.add(btnRefresh);

        mainPanel.add(buttonPanel, BorderLayout.EAST);
        
        // Haut : Titre
        JLabel lblTitle = new JLabel("Comptes Utilisateurs (REST API) :");
        lblTitle.setFont(new Font("Arial", Font.BOLD, 14));
        mainPanel.add(lblTitle, BorderLayout.NORTH);

        setContentPane(mainPanel);
    }

    private void refreshUserList() {
        new Thread(() -> {
            List<String> users = authClient.getAllUsers();
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                for (String u : users) {
                    listModel.addElement(u);
                }
            });
        }).start();
    }

    private void addUser() {
        JTextField txtUser = new JTextField();
        JPasswordField txtPass = new JPasswordField();
        Object[] message = {
            "Utilisateur:", txtUser,
            "Mot de passe:", txtPass
        };

        int option = JOptionPane.showConfirmDialog(this, message, "Ajouter un utilisateur", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            String username = txtUser.getText().trim();
            String password = new String(txtPass.getPassword());
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Les champs ne peuvent pas être vides.");
                return;
            }
            new Thread(() -> {
                boolean success = authClient.createUser(username, password);
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        JOptionPane.showMessageDialog(this, "Utilisateur ajouté.");
                        refreshUserList();
                    } else {
                        JOptionPane.showMessageDialog(this, "L'utilisateur existe déjà ou erreur serveur.");
                    }
                });
            }).start();
        }
    }

    private void editUser() {
        String selected = userList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Veuillez sélectionner un utilisateur.");
            return;
        }

        JPasswordField txtPass = new JPasswordField();
        Object[] message = { "Nouveau mot de passe pour " + selected + ":", txtPass };

        int option = JOptionPane.showConfirmDialog(this, message, "Modifier", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            String newPassword = new String(txtPass.getPassword());
            if (newPassword.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Le mot de passe ne peut pas être vide.");
                return;
            }

            new Thread(() -> {
                boolean success = authClient.updatePassword(selected, newPassword);
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        JOptionPane.showMessageDialog(this, "Mot de passe mis à jour avec succès.");
                        refreshUserList();
                    } else {
                        JOptionPane.showMessageDialog(this, "Erreur lors de la modification du mot de passe.");
                    }
                });
            }).start();
        }
    }

    private void deleteUser() {
        String selected = userList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Veuillez sélectionner un utilisateur.");
            return;
        }

        int option = JOptionPane.showConfirmDialog(this, "Voulez-vous vraiment supprimer " + selected + " ?", "Confirmation", JOptionPane.YES_NO_OPTION);
        if (option == JOptionPane.YES_OPTION) {
            new Thread(() -> {
                boolean success = authClient.deleteUser(selected);
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        JOptionPane.showMessageDialog(this, "Utilisateur supprimé.");
                        refreshUserList();
                    } else {
                        JOptionPane.showMessageDialog(this, "Échec de la suppression.");
                    }
                });
            }).start();
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            new AuthClientMain().setVisible(true);
        });
    }
}
