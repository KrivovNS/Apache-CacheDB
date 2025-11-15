package com.mipt.controller;

import java.util.List;

public class ValidationResult {
  private boolean valid;
  private List<String> errors;

  public ValidationResult() {
  }

  public boolean getValid() {
    return this.valid;
  }

  public String getErrors() {
    return String.join(";", errors);
  }

  public void setValid(boolean valid) {
    this.valid = valid;
  }

  public void addError(String error) {
    this.errors.add(error);
  }
}
