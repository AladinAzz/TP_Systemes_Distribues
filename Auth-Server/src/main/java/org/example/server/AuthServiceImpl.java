package org.example.server;

import org.example.rmi.IAuthService;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class AuthServiceImpl extends UnicastRemoteObject implements IAuthService {

    private final UserManager userManager;
    private final JwtManager jwtManager;

    public AuthServiceImpl() throws RemoteException {
        super();
        this.userManager = new UserManager();
        this.jwtManager = new JwtManager();
    }

    @Override
    public String authenticate(String username, String password) throws RemoteException {
        System.out.println("[AuthServer] Tentative d'authentification pour: " + username);
        if (userManager.authenticate(username, password)) {
            String token = jwtManager.generateToken(username);
            System.out.println("[AuthServer] Auth SUCCES - Token généré pour: " + username);
            return token;
        }
        System.out.println("[AuthServer] Auth ECHEC pour: " + username);
        return null;
    }

    @Override
    public boolean userExists(String username) throws RemoteException {
        System.out.println("[AuthServer] Vérification existence pour: " + username);
        return userManager.exists(username);
    }

    @Override
    public boolean createUser(String username, String password) throws RemoteException {
        System.out.println("[AuthServer] Demande création de: " + username);
        return userManager.createUser(username, password);
    }

    @Override
    public boolean updateUser(String username, String newPassword) throws RemoteException {
        System.out.println("[AuthServer] Demande modification de: " + username);
        return userManager.updateUser(username, newPassword);
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        System.out.println("[AuthServer] Demande suppression de: " + username);
        return userManager.deleteUser(username);
    }

    @Override
    public List<String> getAllUsers() throws RemoteException {
        System.out.println("[AuthServer] Demande de la liste des utilisateurs");
        return userManager.getAllUsers();
    }
}
