package com.mipt.utils;

import com.mipt.service.CacheStorageService;
import com.mipt.model.MaxMemoryPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class TestUtils {

  public static CacheStorageService createTestService(
      MaxMemoryPolicy policy,
      long maxMemory,
      boolean persistence) throws Exception {

    CacheStorageService service = new CacheStorageService();

    // Устанавливаем поля через рефлексию
    setPrivateField(service, "maxMemory", maxMemory);
    setPrivateField(service, "maxMemoryPolicy", policy);
    setPrivateField(service, "persistence", persistence);
    setPrivateField(service, "canPolicyBeChanged", true);

    // Вызываем createCacheStorages
    Method createMethod = CacheStorageService.class.getDeclaredMethod("createCacheStorages");
    createMethod.setAccessible(true);
    createMethod.invoke(service);

    try {
      Field schedulerField = CacheStorageService.class.getDeclaredField("cleanupScheduler");
      schedulerField.setAccessible(true);
      Object scheduler = schedulerField.get(service);
      if (scheduler != null) {
        Method shutdownMethod = scheduler.getClass().getMethod("shutdown");
        shutdownMethod.invoke(scheduler);
      }
    } catch (Exception e) {
    }

    return service;
  }

  public static void setPrivateField(Object obj, String fieldName, Object value)
      throws Exception {
    Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(obj, value);
  }

  public static Object getPrivateField(Object obj, String fieldName)
      throws Exception {
    Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(obj);
  }
}