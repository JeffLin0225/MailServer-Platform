package com.msp.mailserver.Service;

import com.msp.mailserver.Config.MailProperties;
import com.msp.mailserver.Model.MxRecord;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import org.simplejavamail.utils.mail.dkim.DkimSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class MailService {
    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    private final MailProperties mailProperties;
    private DkimSigner dkimSigner;

    public MailService(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
        initDkim();
    }

    private void initDkim() {
        MailProperties.Dkim dkimConfig = mailProperties.getDkim();
        if (!dkimConfig.isEnabled()) {
            logger.info("DKIM signing is disabled in configuration.");
            return;
        }

        try {
            File keyFile = new File(dkimConfig.getPrivateKeyPath());
            if (!keyFile.exists()) {
                logger.warn("DKIM private key file not found at [{}]. DKIM signing will be disabled.", keyFile.getAbsolutePath());
                return;
            }

            RSAPrivateKey privateKey = loadPrivateKey(keyFile.getAbsolutePath());
            this.dkimSigner = new DkimSigner(mailProperties.getDomain(), dkimConfig.getSelector(), privateKey);
            logger.info("DKIMSigner initialized successfully with domain: [{}], selector: [{}]",
                    mailProperties.getDomain(), dkimConfig.getSelector());
        } catch (Exception e) {
            logger.error("Failed to initialize DKIMSigner. Falling back to non-DKIM mode.", e);
        }
    }

    private RSAPrivateKey loadPrivateKey(String filePath) throws Exception {
        String pemContent = new String(new FileInputStream(filePath).readAllBytes());
        pemContent = pemContent.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(pemContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(spec);
    }

    /**
     * 同步發送郵件 (帶有 MX 自動容錯轉移與逾時控制)
     */
    public String sendEmail(String to, String from, String subject, String body) throws Exception {
        String sender = (from != null && !from.trim().isEmpty()) ? from.trim() : mailProperties.getDefaultFrom();

        // 1. 建立 MimeMessage
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(sender));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");

        // 2. 條件性啟用 DKIM 簽名
        MimeMessage finalMessage = message;
        if (dkimSigner != null) {
            finalMessage = new DkimMessage(message, dkimSigner);
            logger.info("DKIM signature successfully appended for [{}]", to);
        }

        // 3. 查詢收件人網域之 MX 記錄並排序 (優先級由小到大)
        String recipientDomain = to.substring(to.indexOf("@") + 1).trim();
        List<MxRecord> mxRecords = getMxRecordsSorted(recipientDomain);
        if (mxRecords.isEmpty()) {
            throw new IllegalStateException("No MX records found for domain: " + recipientDomain);
        }

        logger.info("Discovered {} MX host(s) for [{}]: {}", mxRecords.size(), recipientDomain, mxRecords);

        // 4. 容錯循環：依優先級依序嘗試 MX 主機
        Exception lastException = null;
        for (MxRecord mx : mxRecords) {
            try {
                logger.info("Attempting delivery to MX: [{}] for recipient [{}]", mx.getHost(), to);
                sendSmtpEmail(mx.getHost(), sender, to, finalMessage);
                logger.info("Email delivered successfully to [{}] via MX [{}]", to, mx.getHost());
                return "Email delivered successfully via " + mx.getHost();
            } catch (Exception ex) {
                lastException = ex;
                logger.warn("Delivery failed to MX [{}]: {}. Trying next priority MX...", mx.getHost(), ex.getMessage());
            }
        }

        throw new IOException("Failed to deliver email to all MX hosts for domain [" + recipientDomain + "]. Last error: "
                + (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    /**
     * 非同步發送郵件
     */
    @Async("mailTaskExecutor")
    public CompletableFuture<String> sendEmailAsync(String to, String from, String subject, String body) {
        try {
            String result = sendEmail(to, from, subject, body);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Async email delivery failed for [{}]", to, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 查詢指定網域的 MX 記錄，並依優先級由小到大排序
     */
    public List<MxRecord> getMxRecordsSorted(String domain) {
        List<MxRecord> records = new ArrayList<>();
        try {
            InitialDirContext idc = new InitialDirContext();
            Attributes attrs = idc.getAttributes("dns:/" + domain, new String[]{"MX"});
            Attribute mxAttr = attrs.get("MX");
            if (mxAttr != null) {
                NamingEnumeration<?> enumeration = mxAttr.getAll();
                while (enumeration.hasMore()) {
                    String raw = enumeration.next().toString();
                    String[] parts = raw.split("\\s+");
                    if (parts.length >= 2) {
                        int priority = Integer.parseInt(parts[0]);
                        String host = parts[1];
                        if (host.endsWith(".")) {
                            host = host.substring(0, host.length() - 1);
                        }
                        records.add(new MxRecord(priority, host));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("DNS MX lookup failed for domain [{}]", domain, e);
        }
        Collections.sort(records);
        return records;
    }

    /**
     * 連線至目標 MX 伺服器並進行 SMTP 協定會話 (含 Connect/Read Timeout 與 STARTTLS)
     */
    private void sendSmtpEmail(String mxHost, String mailFrom, String rcptTo, MimeMessage message) throws Exception {
        Socket socket = null;
        SSLSocket sslSocket = null;
        BufferedReader reader = null;
        PrintWriter writer = null;

        int connectTimeout = mailProperties.getConnectTimeoutMs();
        int readTimeout = mailProperties.getReadTimeoutMs();
        int targetPort = mailProperties.getPort();
        String ehloDomain = mailProperties.getDomain();

        try {
            // 建立 Socket 連線並配置逾時機制
            socket = new Socket();
            socket.connect(new InetSocketAddress(mxHost, targetPort), connectTimeout);
            socket.setSoTimeout(readTimeout);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // 1. 讀取伺服器問候語 (220)
            String greeting = reader.readLine();
            logger.debug("[{}] Greeting: {}", mxHost, greeting);
            if (greeting == null || !greeting.startsWith("220")) {
                throw new IOException("Invalid greeting from [" + mxHost + "]: " + greeting);
            }

            // 2. 發送 EHLO
            writer.println("EHLO " + ehloDomain);
            String ehloResp = readMultiLineResponse(reader);
            logger.debug("[{}] EHLO response: {}", mxHost, ehloResp);
            if (!ehloResp.startsWith("250")) {
                throw new IOException("EHLO rejected by [" + mxHost + "]: " + ehloResp);
            }

            // 3. 檢查 STARTTLS
            if (ehloResp.contains("STARTTLS")) {
                writer.println("STARTTLS");
                String starttlsResp = reader.readLine();
                logger.debug("[{}] STARTTLS response: {}", mxHost, starttlsResp);
                if (starttlsResp != null && starttlsResp.startsWith("220")) {
                    SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, mxHost, targetPort, true);
                    sslSocket.setSoTimeout(readTimeout);

                    // 重新包裝 TLS I/O 串流
                    reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
                    writer = new PrintWriter(new OutputStreamWriter(sslSocket.getOutputStream(), "UTF-8"), true);

                    // STARTTLS 成功後必須重新發送 EHLO
                    writer.println("EHLO " + ehloDomain);
                    String ehloTlsResp = readMultiLineResponse(reader);
                    logger.debug("[{}] EHLO after STARTTLS: {}", mxHost, ehloTlsResp);
                    if (!ehloTlsResp.startsWith("250")) {
                        throw new IOException("EHLO after STARTTLS failed: " + ehloTlsResp);
                    }
                }
            }

            // 4. 發送 MAIL FROM
            writer.println("MAIL FROM:<" + mailFrom + ">");
            String mailFromResp = reader.readLine();
            logger.debug("[{}] MAIL FROM response: {}", mxHost, mailFromResp);
            if (mailFromResp == null || !mailFromResp.startsWith("250")) {
                throw new IOException("MAIL FROM rejected by [" + mxHost + "]: " + mailFromResp);
            }

            // 5. 發送 RCPT TO
            writer.println("RCPT TO:<" + rcptTo + ">");
            String rcptToResp = reader.readLine();
            logger.debug("[{}] RCPT TO response: {}", mxHost, rcptToResp);
            if (rcptToResp == null || !rcptToResp.startsWith("250")) {
                throw new IOException("RCPT TO rejected by [" + mxHost + "]: " + rcptToResp);
            }

            // 6. 發送 DATA 指令
            writer.println("DATA");
            String dataResp = reader.readLine();
            logger.debug("[{}] DATA response: {}", mxHost, dataResp);
            if (dataResp == null || !dataResp.startsWith("354")) {
                throw new IOException("DATA rejected by [" + mxHost + "]: " + dataResp);
            }

            // 7. 寫入郵件本文並以單獨一行點號 (CRLF.CRLF) 終止
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            message.writeTo(baos);
            writer.print(baos.toString("UTF-8"));
            if (!baos.toString("UTF-8").endsWith("\r\n") && !baos.toString("UTF-8").endsWith("\n")) {
                writer.println();
            }
            writer.println(".");
            writer.flush();

            String contentResp = reader.readLine();
            logger.debug("[{}] Content submission response: {}", mxHost, contentResp);
            if (contentResp == null || !contentResp.startsWith("250")) {
                throw new IOException("Message body rejected by [" + mxHost + "]: " + contentResp);
            }

            // 8. 發送 QUIT
            writer.println("QUIT");
            String quitResp = reader.readLine();
            logger.debug("[{}] QUIT response: {}", mxHost, quitResp);

        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            if (writer != null) try { writer.close(); } catch (Exception ignored) {}
            if (sslSocket != null) try { sslSocket.close(); } catch (Exception ignored) {}
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private String readMultiLineResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
            // RFC 5321: 多行回應中間以 "250-..." 開頭，最後一行以 "250 ..." (數字後接空格) 結尾
            if (line.matches("^\\d{3}\\s.*")) {
                break;
            }
        }
        return response.toString();
    }
}
