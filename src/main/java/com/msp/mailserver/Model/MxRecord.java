package com.msp.mailserver.Model;

/**
 * 代表一條 DNS MX 記錄，包含優先級與目標主機名稱。
 */
public class MxRecord implements Comparable<MxRecord> {
    private final int priority;
    private final String host;

    public MxRecord(int priority, String host) {
        this.priority = priority;
        this.host = host;
    }

    public int getPriority() {
        return priority;
    }

    public String getHost() {
        return host;
    }

    @Override
    public int compareTo(MxRecord other) {
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public String toString() {
        return host + " (Priority: " + priority + ")";
    }
}
