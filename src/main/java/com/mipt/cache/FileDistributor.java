package com.mipt.cache;

import com.mipt.service.CacheStorageService;
import java.util.Map;
import java.util.HashMap;

public class FileDistributor {
  private final CacheStorageService cacheService;
  private final Map<String, String> fileExtensionMapping;

  public FileDistributor(CacheStorageService cacheService) {
    this.cacheService = cacheService;
    this.fileExtensionMapping = initializeExtensionMapping();
    initializeStorages();
  }

  /**
   * Маппинг расширений файлов на типы хранилищ
   */
  private Map<String, String> initializeExtensionMapping() {
    Map<String, String> mapping = new HashMap<>();

    // String данные - текстовые файлы
    mapping.put("txt", "string_storage");
    mapping.put("log", "string_storage");
    mapping.put("csv", "string_storage");
    mapping.put("xml", "string_storage");
    mapping.put("html", "string_storage");
    mapping.put("css", "string_storage");
    mapping.put("js", "string_storage");

    // JSON данные
    mapping.put("json", "json_storage");
    mapping.put("jsonl", "json_storage");

    // Bytes данные - бинарные файлы
    mapping.put("jpg", "bytes_storage");
    mapping.put("jpeg", "bytes_storage");
    mapping.put("png", "bytes_storage");
    mapping.put("gif", "bytes_storage");
    mapping.put("pdf", "bytes_storage");
    mapping.put("zip", "bytes_storage");
    mapping.put("rar", "bytes_storage");
    mapping.put("exe", "bytes_storage");
    mapping.put("dll", "bytes_storage");
    mapping.put("bin", "bytes_storage");

    return mapping;
  }

  /**
   * Инициализация хранилищ
   */
  private void initializeStorages() {
    // Хранилище для String данных
    if (!cacheService.cacheExists("string_storage")) {
      cacheService.createCacheStorage("string_storage", "CONCURRENT", null);
      System.out.println("Created string_storage (CONCURRENT) for text files");
    }

    // Хранилище для JSON данных
    if (!cacheService.cacheExists("json_storage")) {
      cacheService.createCacheStorage("json_storage", "LRU", 1000);
      System.out.println("Created json_storage (LRU-1000) for JSON files");
    }

    // Хранилище для Bytes данных
    if (!cacheService.cacheExists("bytes_storage")) {
      cacheService.createCacheStorage("bytes_storage", "LRU", 200);
      System.out.println("Created bytes_storage (LRU-200) for binary files");
    }
  }

  /**
   * Определить хранилище по имени файла
   */
  public String determineStorageByFilename(String filename) {
    if (filename == null || filename.isEmpty()) {
      return "string_storage";
    }

    String extension = getFileExtension(filename).toLowerCase();
    String storage = fileExtensionMapping.get(extension);

    if (storage == null) {
      return "string_storage";
    }

    return storage;
  }

  /**
   * Получить расширение файла
   */
  private String getFileExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf(".");
    if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
      return filename.substring(lastDotIndex + 1);
    }
    return "";
  }

  /**
   * Сохранить файл в соответствующее хранилище
   */
  public boolean storeFile(String key, String filename, byte[] content) {
    if (key == null || filename == null || content == null) {
      System.err.println("Invalid parameters for storeFile");
      return false;
    }

    String storageName = determineStorageByFilename(filename);

    try {
      switch (storageName) {
        case "string_storage":
          String textContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
          cacheService.put(storageName, key, textContent);
          break;

        case "json_storage":
          String jsonContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
          cacheService.put(storageName, key, jsonContent);
          break;

        case "bytes_storage":
          cacheService.put(storageName, key, content);
          break;

        default:
          String defaultContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
          cacheService.put("string_storage", key, defaultContent);
          storageName = "string_storage";
          break;
      }

      System.out.println("Stored file '" + filename + "' in " + storageName);
      return true;

    } catch (Exception e) {
      System.err.println("Error storing file '" + filename + "': " + e.getMessage());
      return false;
    }
  }

  /**
   * Получить файл из хранилища
   */
  public Object getFile(String key, String filename) {
    if (key == null || filename == null) {
      return null;
    }

    String storageName = determineStorageByFilename(filename);
    return cacheService.get(storageName, key);
  }

  /**
   * Удалить файл из хранилища
   */
  public boolean deleteFile(String key, String filename) {
    if (key == null || filename == null) {
      System.err.println("Invalid parameters for deleteFile: key=" + key + ", filename=" + filename);
      return false;
    }

    try {
      String storageName = determineStorageByFilename(filename);

      // Проверяем существование файла перед удалением
      Object existingFile = cacheService.get(storageName, key);
      if (existingFile == null) {
        System.out.println("File not found for deletion: " + filename + " with key: " + key);
        return false;
      }

      // Удаляем файл из хранилища
      cacheService.remove(storageName, key);

      System.out.println("Successfully deleted file '" + filename + "' from " + storageName + " with key: " + key);
      return true;

    } catch (Exception e) {
      System.err.println("Error deleting file '" + filename + "': " + e.getMessage());
      return false;
    }
  }



  /**
   * Получить статистику по распределению файлов
   */
  public String getDistributionStats() {
    StringBuilder stats = new StringBuilder();
    stats.append("=== File Distribution Statistics ===\n");

    String[] storages = {"string_storage", "json_storage", "bytes_storage"};
    int totalFiles = 0;

    for (String storage : storages) {
      if (cacheService.cacheExists(storage)) {
        int size = cacheService.size(storage);
        totalFiles += size;
        stats.append(String.format("- %s: %d files\n", storage, size));
      }
    }

    stats.append(String.format("Total files: %d\n", totalFiles));
    return stats.toString();
  }
}
