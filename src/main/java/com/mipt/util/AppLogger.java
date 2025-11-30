package com.mipt.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppLogger {
  private final Logger logger;

  private AppLogger(Class<?> clazz) {
    this.logger = LoggerFactory.getLogger(clazz);
  }

  public static AppLogger getLogger(Class<?> clazz) {
    return new AppLogger(clazz);
  }

  // Информационные сообщения (замена System.out.println)
  public void info(String msg) {
    logger.info(msg);
  }

  // Предупреждения
  public void warn(String msg) {
    logger.warn(msg);
  }

  // Ошибки (замена System.err.println + e.printStackTrace)
  public void error(String msg) {
    logger.error(msg);
  }

  public void error(String msg, Throwable t) {
    logger.error(msg, t);
  }
}
