package com.mipt.controller;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.telemetry.TelemetryService;
import com.mipt.database.dao.UserDAO;
import com.mipt.model.User;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обрабатывает TCP-команды.
 *
 * Формат команд:
 *   AUTH <login> <password>
 *   GET <key>
 *   SET <key> <type> <value>
 *   DELETE <key>
 *   LPUSH <key> <value>
 *   RPUSH <key> <value>
 *   LRANGE <key> <start> <stop>
 *   LLEN <key>
 *   LPOP <key>
 *   RPOP <key>
 *   HSET <key> <field> <value>
 *   HGET <key> <field>
 *   HDEL <key> <field>
 *   HGETALL <key>
 *   HINCRBY <key> <field> <increment>
 */
public class NettyTcpHandler extends SimpleChannelInboundHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(NettyTcpHandler.class);

  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final TelemetryService telemetryService;

  // Текущая сессия клиента
  private String sessionToken = null;

  // Хранилища для структур данных (аналогично NettyHandler)
  private final Map<String, List<String>> listStorage = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> hashStorage = new ConcurrentHashMap<>();

  public NettyTcpHandler(CacheStorageService cacheService,
                         SessionService sessionService,
                         UserDAO userDAO,
                         TelemetryService telemetryService) {
    this.cacheService = cacheService;
    this.sessionService = sessionService;
    this.userDAO = userDAO;
    this.telemetryService = telemetryService;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, String message) {
    message = message.trim();
    log.info("TCP received: {}", message);

    String[] parts = message.split("\\s+", 5);
    String command = parts[0].toUpperCase();
    String requestType = "tcp." + command.toLowerCase();

    switch (command) {
      case "AUTH":
        handleAuth(ctx, parts, requestType);
        break;
      case "GET":
        handleGet(ctx, parts, requestType);
        break;
      case "SET":
        handleSet(ctx, parts, requestType);
        break;
      case "DELETE":
        handleDelete(ctx, parts, requestType);
        break;
      // LIST commands
      case "LPUSH":
        handleListPush(ctx, parts, requestType, true);
        break;
      case "RPUSH":
        handleListPush(ctx, parts, requestType, false);
        break;
      case "LPOP":
        handleListPop(ctx, parts, requestType, true);
        break;
      case "RPOP":
        handleListPop(ctx, parts, requestType, false);
        break;
      case "LRANGE":
        handleListRange(ctx, parts, requestType);
        break;
      case "LLEN":
        handleListLen(ctx, parts, requestType);
        break;
      // HASH commands
      case "HSET":
        handleHashSet(ctx, parts, requestType);
        break;
      case "HGET":
        handleHashGet(ctx, parts, requestType);
        break;
      case "HDEL":
        handleHashDel(ctx, parts, requestType);
        break;
      case "HGETALL":
        handleHashGetAll(ctx, parts, requestType);
        break;
      case "HINCRBY":
        handleHashIncrBy(ctx, parts, requestType);
        break;
      default:
        writeTcpResponse(ctx, "tcp.unknown", false, "ERROR Unknown command. Use: AUTH, GET, SET, DELETE, LPUSH, RPUSH, LRANGE, LLEN, LPOP, RPOP, HSET, HGET, HDEL, HGETALL, HINCRBY\n");
    }
  }

  // ==================== AUTH ====================

  private void handleAuth(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (parts.length < 3) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: AUTH <login> <password>\n");
      return;
    }
    String login = parts[1];
    String password = parts[2];

    try {
      User user = userDAO.findByUsername(login);
      if (user == null || !user.getPassword().equals(password)) {
        writeTcpResponse(ctx, requestType, false, "ERROR Invalid login or password\n");
        return;
      }
      sessionToken = sessionService.createSessionForUser(user);
      writeTcpResponse(ctx, requestType, true, "OK session_token=" + sessionToken + "\n");
    } catch (Exception e) {
      log.error("TCP auth error", e);
      writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
    }
  }

  // ==================== Basic Cache Operations ====================

  private void handleGet(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: GET <key>\n");
      return;
    }

    String key = parts[1];

    // Сначала проверяем в LIST хранилище
    if (listStorage.containsKey(key)) {
      List<String> list = listStorage.get(key);
      writeTcpResponse(ctx, requestType, true, "OK " + list + "\n");
      return;
    }

    // Проверяем в HASH хранилище
    if (hashStorage.containsKey(key)) {
      Map<String, String> hash = hashStorage.get(key);
      writeTcpResponse(ctx, requestType, true, "OK " + hash + "\n");
      return;
    }

    CacheResult result = cacheService.get(key);
    if (result.isSuccess()) {
      writeTcpResponse(ctx, requestType, true, "OK " + result.getData() + "\n");
    } else {
      writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
    }
  }

  private void handleSet(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 4) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: SET <key> <type> <value>\n");
      return;
    }

    String key = parts[1];
    String type = parts[2];
    String value = parts[3];

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      writeTcpResponse(ctx, requestType, false,
          "ERROR Invalid type. Valid: " + String.join(", ", DataType.getAllValues()) + "\n");
      return;
    }

    try {
      Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
      String username = sessionOpt.get().getCreator().getUsername();
      CacheResult result = cacheService.post(key, value, dataType, username, null);
      if (result.isSuccess()) {
        writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
      } else {
        writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
      }
    } catch (Exception e) {
      log.error("TCP set error", e);
      writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
    }
  }

  private void handleDelete(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: DELETE <key>\n");
      return;
    }

    String key = parts[1];

    // Удаляем из LIST хранилища
    if (listStorage.containsKey(key)) {
      listStorage.remove(key);
      writeTcpResponse(ctx, requestType, true, "OK Deleted\n");
      return;
    }

    // Удаляем из HASH хранилища
    if (hashStorage.containsKey(key)) {
      hashStorage.remove(key);
      writeTcpResponse(ctx, requestType, true, "OK Deleted\n");
      return;
    }

    CacheResult result = cacheService.delete(key);
    if (result.isSuccess()) {
      writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
    } else {
      writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
    }
  }

  // ==================== LIST Operations ====================

  private void handleListPush(ChannelHandlerContext ctx, String[] parts, String requestType, boolean left) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 3) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: " + (left ? "LPUSH" : "RPUSH") + " <key> <value>\n");
      return;
    }

    String key = parts[1];
    String value = parts[2];

    try {
      List<String> list = listStorage.computeIfAbsent(key, k -> new ArrayList<>());
      if (left) {
        list.add(0, value);
      } else {
        list.add(value);
      }
      writeTcpResponse(ctx, requestType, true, "OK\n");
    } catch (Exception e) {
      log.error("TCP list push error", e);
      writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
    }
  }

  private void handleListPop(ChannelHandlerContext ctx, String[] parts, String requestType, boolean left) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: " + (left ? "LPOP" : "RPOP") + " <key>\n");
      return;
    }

    String key = parts[1];
    List<String> list = listStorage.get(key);

    if (list == null || list.isEmpty()) {
      writeTcpResponse(ctx, requestType, false, "ERROR Key not found or not a list\n");
      return;
    }

    String value = left ? list.remove(0) : list.remove(list.size() - 1);

    if (list.isEmpty()) {
      listStorage.remove(key);
    }

    writeTcpResponse(ctx, requestType, true, "OK " + value + "\n");
  }

  private void handleListRange(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 4) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: LRANGE <key> <start> <stop>\n");
      return;
    }

    String key = parts[1];
    List<String> list = listStorage.get(key);

    if (list == null || list.isEmpty()) {
      writeTcpResponse(ctx, requestType, true, "OK []\n");
      return;
    }

    try {
      int start = Integer.parseInt(parts[2]);
      int stop = Integer.parseInt(parts[3]);
      int size = list.size();

      int from = start >= 0 ? start : Math.max(0, size + start);
      int to = stop >= 0 ? Math.min(size - 1, stop) : size + stop;

      if (from > to || from >= size) {
        writeTcpResponse(ctx, requestType, true, "OK []\n");
        return;
      }

      List<String> result = list.subList(from, to + 1);
      writeTcpResponse(ctx, requestType, true, "OK " + result + "\n");
    } catch (NumberFormatException e) {
      writeTcpResponse(ctx, requestType, false, "ERROR start and stop must be integers\n");
    }
  }

  private void handleListLen(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: LLEN <key>\n");
      return;
    }

    String key = parts[1];
    List<String> list = listStorage.get(key);

    int size = (list == null) ? 0 : list.size();
    writeTcpResponse(ctx, requestType, true, "OK " + size + "\n");
  }

  // ==================== HASH Operations ====================

  private void handleHashSet(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 4) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: HSET <key> <field> <value>\n");
      return;
    }

    String key = parts[1];
    String field = parts[2];
    String value = parts[3];

    try {
      Map<String, String> hash = hashStorage.computeIfAbsent(key, k -> new HashMap<>());
      hash.put(field, value);
      writeTcpResponse(ctx, requestType, true, "OK\n");
    } catch (Exception e) {
      log.error("TCP hash set error", e);
      writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
    }
  }

  private void handleHashGet(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 3) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: HGET <key> <field>\n");
      return;
    }

    String key = parts[1];
    String field = parts[2];
    Map<String, String> hash = hashStorage.get(key);

    if (hash == null) {
      writeTcpResponse(ctx, requestType, false, "ERROR Key not found\n");
      return;
    }

    String value = hash.get(field);
    if (value == null) {
      writeTcpResponse(ctx, requestType, false, "ERROR Field not found\n");
      return;
    }

    writeTcpResponse(ctx, requestType, true, "OK " + value + "\n");
  }

  private void handleHashDel(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 3) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: HDEL <key> <field>\n");
      return;
    }

    String key = parts[1];
    String field = parts[2];
    Map<String, String> hash = hashStorage.get(key);

    if (hash == null) {
      writeTcpResponse(ctx, requestType, false, "ERROR Key not found\n");
      return;
    }

    String removed = hash.remove(field);
    if (removed == null) {
      writeTcpResponse(ctx, requestType, false, "ERROR Field not found\n");
      return;
    }

    if (hash.isEmpty()) {
      hashStorage.remove(key);
    }

    writeTcpResponse(ctx, requestType, true, "OK\n");
  }

  private void handleHashGetAll(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: HGETALL <key>\n");
      return;
    }

    String key = parts[1];
    Map<String, String> hash = hashStorage.get(key);

    if (hash == null || hash.isEmpty()) {
      writeTcpResponse(ctx, requestType, true, "OK {}\n");
      return;
    }

    writeTcpResponse(ctx, requestType, true, "OK " + hash + "\n");
  }

  private void handleHashIncrBy(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 4) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: HINCRBY <key> <field> <increment>\n");
      return;
    }

    String key = parts[1];
    String field = parts[2];

    try {
      long increment = Long.parseLong(parts[3]);
      Map<String, String> hash = hashStorage.computeIfAbsent(key, k -> new HashMap<>());

      String currentStr = hash.get(field);
      long current = 0;
      if (currentStr != null) {
        try {
          current = Long.parseLong(currentStr);
        } catch (NumberFormatException e) {
          writeTcpResponse(ctx, requestType, false, "ERROR Hash value is not an integer\n");
          return;
        }
      }

      long newValue = current + increment;
      hash.put(field, String.valueOf(newValue));
      writeTcpResponse(ctx, requestType, true, "OK " + newValue + "\n");
    } catch (NumberFormatException e) {
      writeTcpResponse(ctx, requestType, false, "ERROR increment must be a number\n");
    }
  }

  // ==================== Helper Methods ====================

  private boolean isAuthenticated(ChannelHandlerContext ctx, String requestType) {
    if (sessionToken == null || sessionService.getValidSession(sessionToken).isEmpty()) {
      writeTcpResponse(ctx, requestType, false, "ERROR Not authenticated. Use: AUTH <login> <password>\n");
      return false;
    }
    return true;
  }

  private boolean hasWritePermission(ChannelHandlerContext ctx, String requestType) {
    Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
    if (sessionOpt.isPresent() && sessionOpt.get().getPermissionType() == PermissionType.READER) {
      writeTcpResponse(ctx, requestType, false, "ERROR READER users can only GET\n");
      return false;
    }
    return true;
  }

  private void writeTcpResponse(ChannelHandlerContext ctx, String requestType,
                                boolean success, String message) {
    telemetryService.recordTcpRequest(requestType, success);
    ctx.writeAndFlush(message);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("TCP error", cause);
    ctx.close();
  }
}