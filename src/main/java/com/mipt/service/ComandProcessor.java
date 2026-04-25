package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Standalone command-style request processor for currently implemented data operations.
 *
 * <p>Supported commands:
 * PING [message], ECHO message, GET key, SET key value [EX seconds|PX ms] [NX|XX] [TYPE type],
 * DEL key [key ...], EXISTS key [key ...].
 *
 * <p>Input format:
 * inline command: {@code SET key value}
 */
public class ComandProcessor {

  private final CacheStorageService cacheStorageService;

  public ComandProcessor(CacheStorageService cacheStorageService) {
    this.cacheStorageService = Objects.requireNonNull(cacheStorageService, "cacheStorageService");
  }

  /**
   * Processes a single command and returns plain text response.
   *
   * @param request input command
   * @param permissionType caller role
   */
  public String process(String request, PermissionType permissionType) {
    try {
      PermissionType role = Objects.requireNonNull(permissionType, "permissionType");
      List<String> args = parseCommand(request);
      if (args.isEmpty()) {
        return error("ERR empty command");
      }
      return execute(args, role);
    } catch (CommandProcessorException exception) {
      return error(exception.getMessage());
    } catch (Exception exception) {
      return error("ERR internal error: " + exception.getMessage());
    }
  }

  private List<String> parseCommand(String request) {
    if (request == null || request.isBlank()) {
      throw new CommandProcessorException("ERR empty request");
    }

    String trimmed = request.stripLeading();
    return parseInline(trimmed.strip());
  }

  private List<String> parseInline(String request) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    char quoteChar = '\0';
    boolean escaped = false;

    for (int i = 0; i < request.length(); i++) {
      char ch = request.charAt(i);
      if (escaped) {
        current.append(ch);
        escaped = false;
        continue;
      }

      if (inQuotes) {
        if (ch == '\\') {
          escaped = true;
        } else if (ch == quoteChar) {
          inQuotes = false;
        } else {
          current.append(ch);
        }
        continue;
      }

      if (Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }

      if (ch == '\'' || ch == '"') {
        inQuotes = true;
        quoteChar = ch;
      } else {
        current.append(ch);
      }
    }

    if (escaped || inQuotes) {
      throw new CommandProcessorException("ERR Protocol error: unterminated quoted string");
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }

    return tokens;
  }

  private String execute(List<String> args, PermissionType permissionType) {
    String command = args.getFirst().toUpperCase(Locale.ROOT);

    return switch (command) {
      case "PING" -> handlePing(args);
      case "ECHO" -> handleEcho(args);
      case "GET" -> handleGet(args);
      case "SET" -> {
        if (!canWrite(permissionType)) {
          yield error("ERR READER role can only perform read operations");
        }
        yield handleSet(args, permissionType);
      }
      case "DEL" -> {
        if (!canWrite(permissionType)) {
          yield error("ERR READER role can only perform read operations");
        }
        yield handleDel(args);
      }
      case "EXISTS" -> handleExists(args);
      default -> error("ERR unknown command '" + args.getFirst() + "'");
    };
  }

  private String handlePing(List<String> args) {
    if (args.size() == 1) {
      return "PONG";
    }
    if (args.size() == 2) {
      return args.get(1);
    }
    return error("ERR wrong number of arguments for 'PING' command");
  }

  private String handleEcho(List<String> args) {
    if (args.size() != 2) {
      return error("ERR wrong number of arguments for 'ECHO' command");
    }
    return args.get(1);
  }

  private String handleGet(List<String> args) {
    if (args.size() != 2) {
      return error("ERR wrong number of arguments for 'GET' command");
    }

    CacheResult result = cacheStorageService.get(args.get(1));
    if (result.isSuccess()) {
      return String.valueOf(result.getData());
    }

    if (isMissingKeyMessage(result.getMessage())) {
      return "nil";
    }
    return error("ERR " + result.getMessage());
  }

  private String handleSet(List<String> args, PermissionType permissionType) {
    if (args.size() < 3) {
      return error("ERR wrong number of arguments for 'SET' command");
    }

    String key = args.get(1);
    String value = args.get(2);

    SetOptions options;
    try {
      options = parseSetOptions(args.subList(3, args.size()));
    } catch (CommandProcessorException exception) {
      return error(exception.getMessage());
    }

    long sizeBytes = calculateSizeBytes(value);

    CacheResult result = switch (options.mode) {
      case UPSERT -> upsert(
          key, value, options.dataType, options.ttlSeconds, permissionType, sizeBytes
      );
      case NX -> cacheStorageService.post(
          key, value, options.dataType, resolveUser(permissionType), options.ttlSeconds, sizeBytes
      );
      case XX -> cacheStorageService.put(
          key, value, options.dataType, resolveUser(permissionType), options.ttlSeconds, sizeBytes
      );
    };

    if (result.isSuccess()) {
      return "OK";
    }
    if (options.mode == SetMode.NX && isAlreadyExistsMessage(result.getMessage())) {
      return "nil";
    }
    if (options.mode == SetMode.XX && isNotExistsMessage(result.getMessage())) {
      return "nil";
    }
    return error("ERR " + result.getMessage());
  }

  private CacheResult upsert(
      String key, String value, DataType type, Long ttlSeconds,
      PermissionType permissionType, long sizeBytes) {
    CacheResult updateResult = cacheStorageService.put(
        key, value, type, resolveUser(permissionType), ttlSeconds, sizeBytes
    );
    if (updateResult.isSuccess()) {
      return updateResult;
    }
    if (isNotExistsMessage(updateResult.getMessage())) {
      return cacheStorageService.post(
          key, value, type, resolveUser(permissionType), ttlSeconds, sizeBytes
      );
    }
    return updateResult;
  }

  private long calculateSizeBytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private String handleDel(List<String> args) {
    if (args.size() < 2) {
      return error("ERR wrong number of arguments for 'DEL' command");
    }

    int deletedCount = 0;
    for (int i = 1; i < args.size(); i++) {
      CacheResult result = cacheStorageService.delete(args.get(i));
      if (result.isSuccess()) {
        deletedCount++;
      } else if (!isMissingKeyMessage(result.getMessage())) {
        return error("ERR " + result.getMessage());
      }
    }

    return String.valueOf(deletedCount);
  }

  private String handleExists(List<String> args) {
    if (args.size() < 2) {
      return error("ERR wrong number of arguments for 'EXISTS' command");
    }

    int existsCount = 0;
    for (int i = 1; i < args.size(); i++) {
      CacheResult result = cacheStorageService.get(args.get(i));
      if (result.isSuccess()) {
        existsCount++;
      } else if (!isMissingKeyMessage(result.getMessage())) {
        return error("ERR " + result.getMessage());
      }
    }

    return String.valueOf(existsCount);
  }

  private SetOptions parseSetOptions(List<String> options) {
    DataType dataType = DataType.STRING;
    Long ttlSeconds = null;
    SetMode mode = SetMode.UPSERT;
    boolean ttlSpecified = false;

    for (int i = 0; i < options.size(); i++) {
      String option = options.get(i).toUpperCase(Locale.ROOT);
      switch (option) {
        case "EX" -> {
          if (ttlSpecified) {
            throw new CommandProcessorException("ERR syntax error: EX/PX specified more than once");
          }
          if (i + 1 >= options.size()) {
            throw new CommandProcessorException("ERR syntax error: EX requires value");
          }
          long seconds = parsePositiveLong(options.get(++i), "EX");
          ttlSeconds = seconds;
          ttlSpecified = true;
        }
        case "PX" -> {
          if (ttlSpecified) {
            throw new CommandProcessorException("ERR syntax error: EX/PX specified more than once");
          }
          if (i + 1 >= options.size()) {
            throw new CommandProcessorException("ERR syntax error: PX requires value");
          }
          long milliseconds = parsePositiveLong(options.get(++i), "PX");
          ttlSeconds = Math.ceilDiv(milliseconds, 1000L);
          ttlSpecified = true;
        }
        case "TYPE" -> {
          if (i + 1 >= options.size()) {
            throw new CommandProcessorException("ERR syntax error: TYPE requires value");
          }
          DataType parsed = DataType.fromString(options.get(++i));
          if (parsed == null) {
            throw new CommandProcessorException("ERR unsupported type '" + options.get(i) + "'");
          }
          dataType = parsed;
        }
        case "NX" -> mode = mergeMode(mode, SetMode.NX);
        case "XX" -> mode = mergeMode(mode, SetMode.XX);
        default -> throw new CommandProcessorException("ERR syntax error near '" + options.get(i) + "'");
      }
    }

    return new SetOptions(dataType, ttlSeconds, mode);
  }

  private SetMode mergeMode(SetMode current, SetMode requested) {
    if (current == SetMode.UPSERT) {
      return requested;
    }
    if (current == requested) {
      return current;
    }
    throw new CommandProcessorException("ERR syntax error: NX and XX options are mutually exclusive");
  }

  private long parsePositiveLong(String value, String optionName) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new CommandProcessorException("ERR " + optionName + " value must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new CommandProcessorException("ERR " + optionName + " value is not an integer");
    }
  }

  private boolean isMissingKeyMessage(String message) {
    return isNotExistsMessage(message)
        || containsIgnoreCase(message, "not found")
        || containsIgnoreCase(message, "expired");
  }

  private boolean isAlreadyExistsMessage(String message) {
    return containsIgnoreCase(message, "already exists");
  }

  private boolean isNotExistsMessage(String message) {
    return containsIgnoreCase(message, "not exists");
  }

  private boolean containsIgnoreCase(String source, String pattern) {
    return source != null && source.toLowerCase(Locale.ROOT).contains(pattern);
  }

  private boolean canWrite(PermissionType permissionType) {
    return permissionType == PermissionType.ADMIN || permissionType == PermissionType.SUPERADMIN;
  }

  private String resolveUser(PermissionType permissionType) {
    return "role:" + permissionType.getValue();
  }

  private String error(String message) {
    if (message == null || message.isBlank()) {
      return "ERR";
    }
    if (message.startsWith("ERR")) {
      return message;
    }
    return "ERR " + message;
  }

  private enum SetMode {
    UPSERT,
    NX,
    XX
  }

  private static final class SetOptions {
    private final DataType dataType;
    private final Long ttlSeconds;
    private final SetMode mode;

    private SetOptions(DataType dataType, Long ttlSeconds, SetMode mode) {
      this.dataType = dataType;
      this.ttlSeconds = ttlSeconds;
      this.mode = mode;
    }
  }

  public static class CommandProcessorException extends RuntimeException {
    public CommandProcessorException(String message) {
      super(message);
    }
  }
}
