package com.mipt.controller;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlParser {

    private static final Pattern SELECT_PATTERN =
            Pattern.compile("SELECT\\s+(.+?)\\s+FROM\\s+\\S+\\s+WHERE\\s+key\\s*=\\s*'([^']*)'",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern INSERT_PATTERN =
            Pattern.compile("INSERT\\s+INTO\\s+\\S+\\s+\\(key,\\s*value\\)\\s+VALUES\\s+\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_PATTERN =
            Pattern.compile("UPDATE\\s+\\S+\\s+SET\\s+value\\s*=\\s*'([^']*)'\\s+WHERE\\s+key\\s*=\\s*'([^']*)'",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern DELETE_PATTERN =
            Pattern.compile("DELETE\\s+FROM\\s+\\S+\\s+WHERE\\s+key\\s*=\\s*'([^']*)'",
                    Pattern.CASE_INSENSITIVE);

    public enum QueryType {
        SELECT, INSERT, UPDATE, DELETE, UNKNOWN
    }

    public static class ParsedQuery {
        private final QueryType type;
        private final String key;
        private final String value;
        private final String columns;

        public ParsedQuery(QueryType type, String key, String value, String columns) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.columns = columns;
        }

        public QueryType getType() { return type; }
        public String getKey() { return key; }
        public String getValue() { return value; }
        public String getColumns() { return columns; }
        public boolean isSuccess() { return type != QueryType.UNKNOWN; }
    }

    public static ParsedQuery parse(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new ParsedQuery(QueryType.UNKNOWN, null, null, null);
        }

        String trimmed = sql.trim();

        // SELECT
        Matcher selectMatcher = SELECT_PATTERN.matcher(trimmed);
        if (selectMatcher.matches()) {
            String columns = selectMatcher.group(1).trim();
            String key = selectMatcher.group(2);
            return new ParsedQuery(QueryType.SELECT, key, null, columns);
        }

        // INSERT
        Matcher insertMatcher = INSERT_PATTERN.matcher(trimmed);
        if (insertMatcher.matches()) {
            String key = insertMatcher.group(1);
            String value = insertMatcher.group(2);
            return new ParsedQuery(QueryType.INSERT, key, value, null);
        }

        // UPDATE
        Matcher updateMatcher = UPDATE_PATTERN.matcher(trimmed);
        if (updateMatcher.matches()) {
            String value = updateMatcher.group(1);
            String key = updateMatcher.group(2);
            return new ParsedQuery(QueryType.UPDATE, key, value, null);
        }

        // DELETE
        Matcher deleteMatcher = DELETE_PATTERN.matcher(trimmed);
        if (deleteMatcher.matches()) {
            String key = deleteMatcher.group(1);
            return new ParsedQuery(QueryType.DELETE, key, null, null);
        }

        return new ParsedQuery(QueryType.UNKNOWN, null, null, null);
    }

    public static String inferDataType(String value) {
        if (value == null) return "string";
        String trimmed = value.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return "json";
        }
        return "string";
    }

    public static String formatResult(String key, String value, String columns) {
        if (columns == null) return value;
        String trimmedColumns = columns.trim().toLowerCase();
        if (trimmedColumns.equals("*") ||
                (trimmedColumns.contains("key") && trimmedColumns.contains("value"))) {
            return key + "\t" + value;
        } else if (trimmedColumns.equals("key")) {
            return key;
        } else if (trimmedColumns.equals("value")) {
            return value;
        }
        return value;
    }
}