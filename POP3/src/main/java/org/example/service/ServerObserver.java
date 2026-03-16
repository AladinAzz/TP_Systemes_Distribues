package org.example.service;

public interface ServerObserver {
    void logRequest(String clientEnv, String message);
    void logResponse(String message);
    void logEvent(String message);
    void updateClientCount(int count);
}
