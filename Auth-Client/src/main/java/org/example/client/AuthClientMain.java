package org.example.client;

import org.example.rmi.IAuthService;

import javax.swing.*;
import java.awt.*;
import java.rmi.Naming;
import java.util.List;

public class AuthClientMain extends JFrame {
    private IAuthService authService;
    private DefaultListModel<String> listModel;
    private JList<String> userList;

    public AuthClientMain() {
        setTitle("Administration des Utilisateurs (Client RMI)");
        setSize(500, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initRmiConnection();
        initComponents();
        refreshUserList();
    }

    private void initRmiConnection() {
        try {
            authService = (IAuthService) Naming.lookup("rmi://localhost:1099/AuthService");
            System.out.println("Connecté au serveur RMI avec succès.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Erreur de connexion au serveur RMI :\n" + e.getMessage(), "Erreur", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Center: List of users
        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(userList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Right: Buttons
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
        
        // Top: Info
        JLabel lblTitle = new JLabel("Comptes Utilisateurs :");
        lblTitle.setFont(new Font("Arial", Font.BOLD, 14));
        mainPanel.add(lblTitle, BorderLayout.NORTH);

        setContentPane(mainPanel);
    }

    private void refreshUserList() {
        if (authService == null) return;
        try {
            List<String> users = authService.getAllUsers();
            listModel.clear();
            for (String u : users) {
                listModel.addElement(u);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Erreur lors de la récupération : " + e.getMessage(), "Erreur", JOptionPane.ERROR_MESSAGE);
        }
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
            try {
                boolean success = authService.createUser(username, password);
                if (success) {
                    JOptionPane.showMessageDialog(this, "Utilisateur ajouté.");
                    refreshUserList();
                } else {
                    JOptionPane.showMessageDialog(this, "L'utilisateur existe déjà !");
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Erreur RMI : " + e.getMessage());
            }
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
            try {
                boolean success = authService.updateUser(selected, newPassword);
                if (success) {
                    JOptionPane.showMessageDialog(this, "Mot de passe mis à jour.");
                } else {
                    JOptionPane.showMessageDialog(this, "Échec de la mise à jour.");
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Erreur RMI : " + e.getMessage());
            }
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
            try {
                boolean success = authService.deleteUser(selected);
                if (success) {
                    JOptionPane.showMessageDialog(this, "Utilisateur supprimé.");
                    refreshUserList();
                } else {
                    JOptionPane.showMessageDialog(this, "Échec de la suppression.");
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Erreur RMI : " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // Appliquer un thème plus moderne si possible
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            new AuthClientMain().setVisible(true);
        });
    }
}
