package com.mipt.controller;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {
  private boolean valid = true;
  private List<String> errors = new ArrayList<>();

  public ValidationResult() {
  }

  public boolean getValid() {
    return this.valid && errors.isEmpty();
  }

  public String getErrors() {
    return String.join("; ", errors);
  }

  public void setValid(boolean valid) {
    this.valid = valid;
  }

  public void addError(String error) {
    this.errors.add(error);
  }
}