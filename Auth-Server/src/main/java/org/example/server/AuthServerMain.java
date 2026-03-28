package org.example.server;

import org.example.rmi.IAuthService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class AuthServerMain {
    public static void main(String[] args) {
        try {
            // Création du gestionnaire de registre RMI sur le port standard 1099
            Registry registry = LocateRegistry.createRegistry(1099);
            
            // Instanciation du service distant
            IAuthService authService = new AuthServiceImpl();
            
            // Enregistrement du service dans le registre
            registry.rebind("AuthService", authService);
            
            System.out.println("✅ Serveur d'authentification RMI démarré sur le port 1099.");
            System.out.println("En attente de requêtes...");
            
            // Garder le serveur actif
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("❌ Erreur au démarrage du serveur RMI : " + e.getMessage());
            e.printStackTrace();
        }
    }
}
