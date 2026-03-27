package com.mipt.controller;

import com.mipt.model.DataType;
import java.util.Base64;

public class MemoryCalculator {

  /**
   * Возвращает размер данных в байтах.
   * @param type тип данных (STRING, JSON, BYTES)
   * @param data строковое представление данных
   * @return количество байт
   */
  public static long calculateBytes(DataType type, String data) {
    if (data == null) return 0;

    return switch (type) {
      case STRING -> data.length() * 2L; // UTF-16: каждый символ = 2 байта
      case JSON   -> data.length() * 2L; // JSON хранится как строка
      case BYTES  -> Base64.getDecoder().decode(data).length; // длина массива байтов
    };
  }

  /**
   * Возвращает размер данных в битах.
   * @param type тип данных (STRING, JSON, BYTES)
   * @param data строковое представление данных
   * @return количество бит
   */
  public static long calculateBits(DataType type, String data) {
    return calculateBytes(type, data) * 8;
  }
}