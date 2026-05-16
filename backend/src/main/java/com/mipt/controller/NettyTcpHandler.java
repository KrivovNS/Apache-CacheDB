package com.mipt.controller;

import com.mipt.cache.CacheResult;
import com.mipt.model.BatchCommand;
import com.mipt.model.BatchResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.service.BatchCommandService;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.service.RateLimitService;
import com.mipt.telemetry.TelemetryService;
import com.mipt.database.dao.UserDAO;
import com.mipt.model.User;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.net.SocketException;
import java.util.Locale;
import java.util.Optional;

/**
 * Обрабатывает TCP-команды по аналогии с NettyHandler для HTTP.
 *
 * Формат команд:
 *   AUTH <session_token>
 *   GET <key>
 *   SET <key> <type> <value>
 *   DELETE <key>
 */
public class NettyTcpHandler extends SimpleChannelInboundHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(NettyTcpHandler.class);

  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final TelemetryService telemetryService;

    private String sessionToken = null;
    private final List<String> batchBuffer = new ArrayList<>();
    private boolean batchMode = false;
    private long currentCommandStartNanos = -1L;

    public NettyTcpHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO,
            TelemetryService telemetryService,
            RateLimitService rateLimitService) {
        this.cacheService = cacheService;
        this.sessionService = sessionService;
        this.userDAO = userDAO;
        this.telemetryService = telemetryService;
        this.rateLimitService = rateLimitService;
    }

    public NettyTcpHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO) {
        this(cacheService, sessionService, userDAO,
                TelemetryService.disabled(cacheService),
                new RateLimitService(sessionService, false));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) {
        message = message.trim();
        if (message.isEmpty()) {
            return;
        }

        // ============ RATE LIMIT CHECK ============
        String rateLimitKey = getRateLimitKey(ctx);

        if ("EXEC".equalsIgnoreCase(message) || "END".equalsIgnoreCase(message)) {
            int batchSize = batchBuffer.size();
            if (rateLimitService.isEnabled() && !rateLimitService.allowBatchRequest(rateLimitKey,
                    batchSize)) {
                writeTcpResponse(ctx, "tcp.ratelimit", false,
                        "ERROR Rate limit exceeded. Please try again later.\n");
                batchMode = false;
                batchBuffer.clear();
                return;
            }
        } else if (!"BEGIN".equalsIgnoreCase(message) && !batchMode) {
            if (rateLimitService.isEnabled() && !rateLimitService.allowRequest(rateLimitKey,
                    sessionToken)) {
                writeTcpResponse(ctx, "tcp.ratelimit", false,
                        "ERROR Rate limit exceeded. Please try again later.\n");
                return;
            }
        }

        log.info("TCP received: {}", message);

    String[] parts = message.split("\\s+", 4);
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
      default:
        writeTcpResponse(ctx, "tcp.unknown", false, "ERROR Unknown command. Use: AUTH, GET, SET, DELETE\n");
    }
  }

  // AUTH <login> <password>
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

  // GET <key>
  private void handleGet(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: GET <key>\n");
      return;
    }
    CacheResult result = cacheService.get(parts[1]);
    if (result.isSuccess()) {
      writeTcpResponse(ctx, requestType, true, "OK " + result.getData() + "\n");
    } else {
      writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
    }
  }

  // SET <key> <type> <value>
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

  // DELETE <key>
  private void handleDelete(ChannelHandlerContext ctx, String[] parts, String requestType) {
    if (!isAuthenticated(ctx, requestType)) return;
    if (!hasWritePermission(ctx, requestType)) return;
    if (parts.length < 2) {
      writeTcpResponse(ctx, requestType, false, "ERROR Usage: DELETE <key>\n");
      return;
    }

    CacheResult result = cacheService.delete(parts[1]);
    if (result.isSuccess()) {
      writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
    } else {
      writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
    }
  }

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

    private boolean isSuperAdmin(ChannelHandlerContext ctx, String requestType) {
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        if (sessionOpt.isPresent()
                && sessionOpt.get().getPermissionType() != PermissionType.SUPERADMIN) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Only SUPERADMIN users can perform this operation\n");
            return false;
        }
        return true;
    }

    private Long parseTtl(String ttlString) {
        if (ttlString == null || ttlString.trim().isEmpty()) {
            return null;
        }
        ttlString = ttlString.trim().toLowerCase();
        try {
            if (ttlString.endsWith("ms")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 2)) / 1000;
            } else if (ttlString.endsWith("s")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1));
            } else if (ttlString.endsWith("m")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 60;
            } else if (ttlString.endsWith("h")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 3600;
            } else if (ttlString.endsWith("d")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 86400;
            } else {
                return Long.parseLong(ttlString) / 1000;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid TTL format: " + ttlString);
        }
    }

    private void writeTcpResponse(ChannelHandlerContext ctx, String requestType,
                                  boolean success, String message) {
        long latencyNanos = currentCommandStartNanos > 0
                ? System.nanoTime() - currentCommandStartNanos
                : -1L;
        telemetryService.recordTcpRequest(requestType, success, latencyNanos);
        currentCommandStartNanos = -1L;
        ctx.writeAndFlush(message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("TCP error", cause);
        ctx.close();
    }
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (isClientDisconnect(cause)) {
      log.debug("TCP client disconnected: {}", cause.getMessage());
      ctx.close();
      return;
    }

    log.error("TCP error", cause);
    ctx.close();
  }

  private boolean isClientDisconnect(Throwable cause) {
    Throwable current = cause;
    while (current != null) {
      if (current instanceof SocketException) {
        String message = current.getMessage();
        if (message != null) {
          String normalized = message.toLowerCase(Locale.ROOT);
          if (normalized.contains("connection reset")
              || normalized.contains("broken pipe")
              || normalized.contains("forcibly closed")) {
            return true;
          }
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
