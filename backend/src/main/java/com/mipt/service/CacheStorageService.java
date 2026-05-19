package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.cache.HashCache;
import com.mipt.cache.ListCache;
import com.mipt.database.dao.CacheEntryDAO;
import com.mipt.model.CacheStorage;
import com.mipt.model.DataType;
import com.mipt.model.HttpMethod;
import com.mipt.model.MaxMemoryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheStorageService {

    private CacheStorage database;
    private final ScheduledExecutorService cleanupScheduler;

    private long maxMemory;
    private MaxMemoryPolicy maxMemoryPolicy;
    private boolean canPolicyBeChanged;
    private boolean persistence;
    private final CacheEntryDAO cacheEntryDAO;

    // Hash and List caches
    private HashCache hashCache;
    private ListCache listCache;

    public CacheStorageService() {
        try {
            Properties props = loadProperties();
            this.maxMemory = Long.parseLong(props.getProperty("cache.max.memory", "104857600"));
            this.maxMemoryPolicy = parsePolicy(props, "cache.max.memory.policy", MaxMemoryPolicy.ALLKEYSLRU);
            this.canPolicyBeChanged = true;
            this.persistence = Boolean.parseBoolean(props.getProperty("cache.persistence", "false"));
        } catch (IOException e) {
            this.maxMemory = 100 * 1024 * 1024;
            this.maxMemoryPolicy = MaxMemoryPolicy.ALLKEYSLRU;
            this.canPolicyBeChanged = true;
            this.persistence = false;
        }

        cacheEntryDAO = new CacheEntryDAO();
        createCacheStorages();

        if (persistence) {
            loadFromDatabase();
        }

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupDatabase, 1, 1, TimeUnit.MINUTES);
    }

    public void loadFromDatabase() {
        try {
            cacheEntryDAO.createAllTables();
            cacheEntryDAO.loadEntriesIntoCacheStorage(this);
            System.out.println("Data loaded from database successfully");
        } catch (SQLException e) {
            System.err.println("Error while loading data from database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public CacheResult post(String key, Object value, DataType dataType, String user, Long ttlSeconds) {
        if (canPolicyBeChanged) {
            canPolicyBeChanged = false;
        }
        if (!database.containsKey(key)) {
            return database.set(key, value, dataType, user, ttlSeconds, HttpMethod.POST);
        }
        return CacheResult.error("The key already exists");
    }

    public CacheResult post(String key, Object value, DataType dataType,
                            String user, Long ttlSeconds, long sizeBytes) {
        return post(key, value, dataType, user, ttlSeconds);
    }

    public CacheResult put(String key, Object value, DataType dataType, String user, Long ttlSeconds) {
        if (database.containsKey(key)) {
            return database.set(key, value, dataType, user, ttlSeconds, HttpMethod.PUT);
        }
        return CacheResult.error("The key not exists");
    }

    public CacheResult put(String key, Object value, DataType dataType,
                           String user, Long ttlSeconds, long sizeBytes) {
        return put(key, value, dataType, user, ttlSeconds);
    }

    public CacheResult get(String key) {
        return database.get(key);
    }

    public CacheResult delete(String key) {
        return database.delete(key);
    }

    private void cleanupDatabase() {
        int removed = database.cleanupExpired();
        if (removed > 0) {
            System.out.println("Cleanup removed " + removed + " expired keys");
        }
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public CacheResult changePolicy(MaxMemoryPolicy maxMemoryPolicy, long totalMaxMemory, boolean persistence) {
        if (canPolicyBeChanged) {
            this.maxMemoryPolicy = maxMemoryPolicy;
            this.maxMemory = totalMaxMemory;
            this.persistence = persistence;
            createCacheStorages();
            if (persistence) {
                loadFromDatabase();
            }
            return CacheResult.success();
        }
        return CacheResult.error("Can't change configuration");
    }

    private Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            }
        }
        return props;
    }

    private MaxMemoryPolicy parsePolicy(Properties props, String key, MaxMemoryPolicy defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                return MaxMemoryPolicy.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return MaxMemoryPolicy.ALLKEYSLRU;
            }
        }
        return defaultValue;
    }

    private void createCacheStorages() {
        database = new CacheStorage(maxMemory, maxMemoryPolicy, persistence, cacheEntryDAO);
        hashCache = new HashCache();
        listCache = new ListCache();
    }

    public long getUsedMemoryBytes() {
        return database.getMemoryUsedBytes();
    }

    public long getConfiguredMaxMemoryBytes() {
        return maxMemory;
    }

    public boolean isPersistenceEnabled() {
        return persistence;
    }

    public MaxMemoryPolicy getMaxMemoryPolicy() {
        return maxMemoryPolicy;
    }

    // ============ HASH OPERATIONS ==========

    private HashCache getHashCache() {
        if (hashCache == null) {
            hashCache = new HashCache();
        }
        return hashCache;
    }

    public CacheResult hashSet(String key, String field, String value, String username) {
        try {
            HashCache hashCache = getHashCache();
            hashCache.hset(key, field, value);
            return CacheResult.success("Field set successfully");
        } catch (Exception e) {
            return CacheResult.error("HSET error: " + e.getMessage());
        }
    }

    public CacheResult hashGet(String key, String field) {
        try {
            HashCache hashCache = getHashCache();
            Object value = hashCache.hget(key, field);
            if (value == null) {
                return CacheResult.error("Field not found");
            }
            return CacheResult.success(value.toString());
        } catch (Exception e) {
            return CacheResult.error("HGET error: " + e.getMessage());
        }
    }

    public CacheResult hashDel(String key, String field) {
        try {
            HashCache hashCache = getHashCache();
            Object removed = hashCache.hdel(key, field);
            if (removed == null) {
                return CacheResult.error("Field not found");
            }
            return CacheResult.success("Field deleted successfully");
        } catch (Exception e) {
            return CacheResult.error("HDEL error: " + e.getMessage());
        }
    }

    public CacheResult hashGetAll(String key) {
        try {
            HashCache hashCache = getHashCache();
            Map<Object, Object> result = hashCache.hgetall(key);
            if (result.isEmpty()) {
                return CacheResult.error("Hash not found or empty");
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Object, Object> entry : result.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            return CacheResult.success(sb.toString());
        } catch (Exception e) {
            return CacheResult.error("HGETALL error: " + e.getMessage());
        }
    }

    public CacheResult hashKeys(String key) {
        try {
            HashCache hashCache = getHashCache();
            Set<Object> result = hashCache.hkeys(key);
            if (result.isEmpty()) {
                return CacheResult.error("Hash not found or empty");
            }
            StringBuilder sb = new StringBuilder();
            for (Object field : result) {
                sb.append(field).append("\n");
            }
            return CacheResult.success(sb.toString());
        } catch (Exception e) {
            return CacheResult.error("HKEYS error: " + e.getMessage());
        }
    }

    public CacheResult hashLen(String key) {
        try {
            HashCache hashCache = getHashCache();
            int size = hashCache.hlen(key);
            return CacheResult.success("Hash has " + size + " field(s)");
        } catch (Exception e) {
            return CacheResult.error("HLEN error: " + e.getMessage());
        }
    }

    // ============ LIST OPERATIONS ==========

    private ListCache getListCache() {
        if (listCache == null) {
            listCache = new ListCache();
        }
        return listCache;
    }

    public CacheResult listLPush(String key, String value, String username) {
        try {
            ListCache listCache = getListCache();
            listCache.lpush(key, value);
            int size = listCache.llen(key);
            return CacheResult.success("Pushed to left. List size: " + size);
        } catch (Exception e) {
            return CacheResult.error("LPUSH error: " + e.getMessage());
        }
    }

    public CacheResult listRPush(String key, String value, String username) {
        try {
            ListCache listCache = getListCache();
            listCache.rpush(key, value);
            int size = listCache.llen(key);
            return CacheResult.success("Pushed to right. List size: " + size);
        } catch (Exception e) {
            return CacheResult.error("RPUSH error: " + e.getMessage());
        }
    }

    public CacheResult listLPop(String key) {
        try {
            ListCache listCache = getListCache();
            Object value = listCache.lpop(key);
            if (value == null) {
                return CacheResult.error("List is empty or does not exist");
            }
            return CacheResult.success("Popped: " + value);
        } catch (Exception e) {
            return CacheResult.error("LPOP error: " + e.getMessage());
        }
    }

    public CacheResult listRPop(String key) {
        try {
            ListCache listCache = getListCache();
            Object value = listCache.rpop(key);
            if (value == null) {
                return CacheResult.error("List is empty or does not exist");
            }
            return CacheResult.success("Popped: " + value);
        } catch (Exception e) {
            return CacheResult.error("RPOP error: " + e.getMessage());
        }
    }

    public CacheResult listLRange(String key, int start, int end) {
        try {
            ListCache listCache = getListCache();
            List<Object> range = listCache.lrange(key, start, end);
            if (range.isEmpty()) {
                return CacheResult.error("List is empty or range invalid");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < range.size(); i++) {
                sb.append("[").append(start + i).append("] ").append(range.get(i)).append("\n");
            }
            return CacheResult.success(sb.toString());
        } catch (Exception e) {
            return CacheResult.error("LRANGE error: " + e.getMessage());
        }
    }

    public CacheResult listLLen(String key) {
        try {
            ListCache listCache = getListCache();
            int size = listCache.llen(key);
            return CacheResult.success("List size: " + size);
        } catch (Exception e) {
            return CacheResult.error("LLEN error: " + e.getMessage());
        }
    }

    public CacheResult listLIndex(String key, int index) {
        try {
            ListCache listCache = getListCache();
            Object value = listCache.lindex(key, index);
            if (value == null) {
                return CacheResult.error("Index out of range or list empty");
            }
            return CacheResult.success("Value at index " + index + ": " + value);
        } catch (Exception e) {
            return CacheResult.error("LINDEX error: " + e.getMessage());
        }
    }
}