package com.msp.mailserver.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mail.server")
public class MailProperties {

    /**
     * 發信時 EHLO 使用的寄件伺服器網域名稱
     */
    private String domain = "mail.yourdomain.com";

    /**
     * 預設的寄件者信箱 (MAIL FROM)
     */
    private String defaultFrom = "admin@yourdomain.com";

    /**
     * 目標 SMTP 連接埠 (預設外網直發為 25)
     */
    private int port = 25;

    /**
     * 連線逾時設定 (毫秒)
     */
    private int connectTimeoutMs = 10000;

    /**
     * Socket 讀取逾時設定 (毫秒)
     */
    private int readTimeoutMs = 15000;

    /**
     * DKIM 相關設定
     */
    private Dkim dkim = new Dkim();

    public static class Dkim {
        private boolean enabled = false;
        private String selector = "mail";
        private String privateKeyPath = "dkim_private_pkcs8.pem";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSelector() {
            return selector;
        }

        public void setSelector(String selector) {
            this.selector = selector;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDefaultFrom() {
        return defaultFrom;
    }

    public void setDefaultFrom(String defaultFrom) {
        this.defaultFrom = defaultFrom;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public Dkim getDkim() {
        return dkim;
    }

    public void setDkim(Dkim dkim) {
        this.dkim = dkim;
    }
}
