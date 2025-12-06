package com.mipt.service;

import java.util.UUID;

public class TokenGenerators {
  public static String generateSessionToken() {
    return UUID.randomUUID().toString();
  }
}
