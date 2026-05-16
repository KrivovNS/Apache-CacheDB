package com.mipt.model;

public class BatchCommand {
    private String command;
    private String endpoint; // cache, user, configuration
    private String key;
    private String type;
    private String value;
    private String login;
    private String password;
    private String newLogin;
    private String permission;
    private String maxMemoryPolicy;
    private String maxStorageMemory;
    private String persistence;
    private String ttl;
    private String sessionToken;

    public BatchCommand() {}

    public BatchCommand(String command, String endpoint) {
        this.command = command;
        this.endpoint = endpoint;
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNewLogin() { return newLogin; }
    public void setNewLogin(String newLogin) { this.newLogin = newLogin; }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }

    public String getMaxMemoryPolicy() { return maxMemoryPolicy; }
    public void setMaxMemoryPolicy(String maxMemoryPolicy) { this.maxMemoryPolicy = maxMemoryPolicy; }

    public String getMaxStorageMemory() { return maxStorageMemory; }
    public void setMaxStorageMemory(String maxStorageMemory) { this.maxStorageMemory = maxStorageMemory; }

    public String getPersistence() { return persistence; }
    public void setPersistence(String persistence) { this.persistence = persistence; }

    public String getTtl() { return ttl; }
    public void setTtl(String ttl) { this.ttl = ttl; }

    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
}