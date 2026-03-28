package org.example.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface distante (RMI) définissant les opérations d'authentification 
 * et d'administration des utilisateurs.
 */
public interface IAuthService extends Remote {
    
    /**
     * Tente d'authentifier un utilisateur avec ses identifiants.
     * @param username Nom d'utilisateur
     * @param password Mot de passe
     * @return Un token JWT si l'authentification réussit, null sinon.
     * @throws RemoteException en cas de problème de communication RMI
     */
    String authenticate(String username, String password) throws RemoteException;
    
    /**
     * Vérifie si un utilisateur donné existe dans la base de données (JSON).
     * Particulièrement utile pour le serveur SMTP (MAIL FROM).
     * @param username Nom d'utilisateur à rechercher
     * @return true si trouvé, false sinon
     * @throws RemoteException en cas de problème de communication RMI
     */
    boolean userExists(String username) throws RemoteException;

    /**
     * Crée un nouveau compte utilisateur.
     * @param username Le nom de l'utilisateur
     * @param password Le mot de passe de l'utilisateur
     * @return true si la création a réussi (utilisateur n'existait pas), false sinon
     * @throws RemoteException
     */
    boolean createUser(String username, String password) throws RemoteException;

    /**
     * Met à jour le mot de passe d'un compte utilisateur existant.
     * @param username Le nom de l'utilisateur cible
     * @param newPassword Le nouveau mot de passe
     * @return true si la modification a réussi (utilisateur existant), false sinon
     * @throws RemoteException
     */
    boolean updateUser(String username, String newPassword) throws RemoteException;

    /**
     * Supprime un compte utilisateur existant.
     * @param username Le nom de l'utilisateur à supprimer
     * @return true si la suppression a réussi, false sinon (utilisateur non trouvé)
     * @throws RemoteException
     */
    boolean deleteUser(String username) throws RemoteException;

    /**
     * Retourne la liste de tous les noms d'utilisateurs.
     * @return Une liste de chaînes contenant les noms d'utilisateurs.
     * @throws RemoteException
     */
    List<String> getAllUsers() throws RemoteException;
}
