package com.mipt.service;

import java.security.SecureRandom;
import java.util.UUID;

public class TokenGenerators {
  public static String generateSessionToken() {
    return UUID.randomUUID().toString();
  }

  public static String generateStorageToken(int length) {
    SecureRandom random = new SecureRandom();
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    StringBuilder token = new StringBuilder(length);

    for (int i = 0; i < length; i++) {
      int index = random.nextInt(alphabet.length()); // Лучше!
      token.append(alphabet.charAt(index));
    }

    return token.toString();
  }
}
