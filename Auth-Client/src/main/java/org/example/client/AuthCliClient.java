package org.example.client;

import org.example.rmi.IAuthService;
import java.rmi.Naming;

public class AuthCliClient {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java org.example.client.AuthCliClient <action> <args...>");
            System.err.println("Actions: create <user> <pass>, delete <user>, authenticate <user> <pass>");
            System.exit(1);
        }

        try {
            IAuthService authService = (IAuthService) Naming.lookup("rmi://localhost:1099/AuthService");
            String action = args[0];

            if ("create".equalsIgnoreCase(action) && args.length == 3) {
                boolean result = authService.createUser(args[1], args[2]);
                System.out.println(result ? "SUCCESS" : "FAIL_CREATE");
            } else if ("delete".equalsIgnoreCase(action) && args.length == 2) {
                boolean result = authService.deleteUser(args[1]);
                System.out.println(result ? "SUCCESS" : "FAIL_DELETE");
            } else if ("authenticate".equalsIgnoreCase(action) && args.length == 3) {
                String token = authService.authenticate(args[1], args[2]);
                System.out.println(token != null ? "TOKEN:" + token : "FAIL_AUTH");
            } else {
                System.err.println("Invalid action or arguments.");
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
