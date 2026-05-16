package com.mipt.model;

import java.util.ArrayList;
import java.util.List;

public class BatchResult {
    private boolean success;
    private String message;
    private List<CommandResult> results;

    public BatchResult() {
        this.results = new ArrayList<>();
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<CommandResult> getResults() { return results; }
    public void addResult(CommandResult result) { this.results.add(result); }

    public static class CommandResult {
        private int index;
        private String command;
        private boolean success;
        private String result;

        public CommandResult(int index, String command, boolean success, String result) {
            this.index = index;
            this.command = command;
            this.success = success;
            this.result = result;
        }

        public int getIndex() { return index; }
        public String getCommand() { return command; }
        public boolean isSuccess() { return success; }
        public String getResult() { return result; }
    }
}