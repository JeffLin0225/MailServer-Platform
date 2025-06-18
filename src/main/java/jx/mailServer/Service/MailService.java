package jx.mailServer.Service;

import org.simplejavamail.utils.mail.dkim.DkimMessage;
import org.simplejavamail.utils.mail.dkim.DkimSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Properties;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Session;
import org.springframework.stereotype.Service;

@Service
public class MailService {
   private static final Logger logger = LoggerFactory.getLogger(MailService.class);
   private final DkimSigner dkimSigner;

   public MailService() throws Exception {
       try {
           RSAPrivateKey privateKey = loadPrivateKey("dkim_private_pkcs8.pem");
           this.dkimSigner = new DkimSigner("mail.jxwebs.com", "mail", privateKey);
           logger.info("DKIMSigner initialized with domain: mail.jxwebs.com, selector: mail");
       } catch (Exception e) {
           logger.error("Failed to initialize DKIMSigner", e);
           throw new RuntimeException("DKIMSigner initialization failed", e);
       }
   }

   private RSAPrivateKey loadPrivateKey(String filePath) throws Exception {
       try {
           String pemContent = new String(new FileInputStream(filePath).readAllBytes());
           pemContent = pemContent.replace("-----BEGIN PRIVATE KEY-----", "")
                   .replace("-----END PRIVATE KEY-----", "")
                   .replaceAll("\\s", "");
           byte[] keyBytes = java.util.Base64.getDecoder().decode(pemContent);
           PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
           KeyFactory kf = KeyFactory.getInstance("RSA");
           return (RSAPrivateKey) kf.generatePrivate(spec);
       } catch (Exception e) {
           logger.error("Failed to load private key from {}", filePath, e);
           throw new RuntimeException("Private key loading failed", e);
       }
   }

   public String sendEmail(String to, String subject, String body) throws Exception {
       try {
           // 創建 MimeMessage
           Properties props = new Properties();
           Session session = Session.getDefaultInstance(props);
           MimeMessage message = new MimeMessage(session);
           message.setFrom(new InternetAddress("jaxi@mail.jxwebs.com"));
           message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
           message.setSubject(subject);
           message.setText(body);

           // 添加 DKIM 簽署
           DkimMessage signedMessage = new DkimMessage(message, dkimSigner);
           logger.info("DKIM signature added to email for recipient: {}", to);

           // 查詢收件人域名的 MX 記錄
           String recipientDomain = to.substring(to.indexOf("@") + 1);
           String mxHost = getMxRecord(recipientDomain);
           if (mxHost == null) {
               throw new RuntimeException("No MX record found for domain: " + recipientDomain);
           }
           logger.info("MX host for {}: {}", recipientDomain, mxHost);

           // 連線到 MX 伺服器並發送郵件
           sendSmtpEmail(mxHost, signedMessage);
           logger.info("Email sent successfully to {}", to);
           return "Email sent successfully";
       } catch (Exception e) {
           logger.error("Failed to send email to {}", to, e);
           throw new RuntimeException("Email sending failed", e);
       }
   }

   private String getMxRecord(String domain) throws Exception {
       InitialDirContext idc = new InitialDirContext();
       Attributes attrs = idc.getAttributes("dns:/" + domain, new String[]{"MX"});
       String mx = attrs.get("MX") != null ? attrs.get("MX").get(0).toString() : null;
       if (mx != null) {
           // 提取 MX 主機名（格式：優先級 主机名）
           String[] parts = mx.split("\\s+");
           return parts[parts.length - 1].endsWith(".") ? parts[parts.length - 1].substring(0, parts[parts.length - 1].length() - 1) : parts[parts.length - 1];
       }
       return null;
   }

   private void sendSmtpEmail(String mxHost, DkimMessage message) throws Exception {
       Socket socket = null;
       SSLSocket sslSocket = null;
       BufferedReader reader = null;
       PrintWriter writer = null;

       try {
           // 初始連線（TCP）
           socket = new Socket(mxHost, 25);
           reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
           writer = new PrintWriter(socket.getOutputStream(), true);

           // 讀取伺服器問候語
           String response = reader.readLine();
           if (!response.startsWith("220")) {
               throw new IOException("Invalid SMTP greeting: " + response);
           }
           logger.info("SMTP greeting: {}", response);

           // 發送 EHLO
           writer.println("EHLO mail.jxwebs.com");
           response = readMultiLineResponse(reader);
           if (!response.startsWith("250")) {
               throw new IOException("EHLO failed: " + response);
           }
           logger.info("EHLO response: {}", response);

           // 檢查是否支持 STARTTLS
           if (response.contains("STARTTLS")) {
               writer.println("STARTTLS");
               response = reader.readLine();
               if (!response.startsWith("220")) {
                   throw new IOException("STARTTLS failed: " + response);
               }
               logger.info("STARTTLS response: {}", response);

               // 升級到 TLS
               SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
               sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, mxHost, 25, true);

               // 關閉原始 reader 和 writer
               reader.close();
               writer.close();

               // 為 SSLSocket 創建新的 reader 和 writer
               reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
               writer = new PrintWriter(sslSocket.getOutputStream(), true);

               // 再次發送 EHLO
               writer.println("EHLO mail.jxwebs.com");
               response = readMultiLineResponse(reader);
               if (!response.startsWith("250")) {
                   throw new IOException("EHLO after STARTTLS failed: " + response);
               }
               logger.info("EHLO after STARTTLS: {}", response);
           }

           // 發送 MAIL FROM
           writer.println("MAIL FROM:<jaxi@mail.jxwebs.com>");
           response = reader.readLine();
           if (!response.startsWith("250")) {
               throw new IOException("MAIL FROM failed: " + response);
           }
           logger.info("MAIL FROM response: {}", response);

           // 發送 RCPT TO
           writer.println("RCPT TO:<" + message.getRecipients(Message.RecipientType.TO)[0].toString() + ">");
           response = reader.readLine();
           if (!response.startsWith("250")) {
               throw new IOException("RCPT TO failed: " + response);
           }
           logger.info("RCPT TO response: {}", response);

           // 發送 DATA
           writer.println("DATA");
           response = reader.readLine();
           if (!response.startsWith("354")) {
               throw new IOException("DATA failed: " + response);
           }
           logger.info("DATA response: {}", response);

           // 發送郵件內容
           ByteArrayOutputStream baos = new ByteArrayOutputStream();
           message.writeTo(baos);
           String emailContent = baos.toString();
           writer.println(emailContent);
           writer.println(".");
           response = reader.readLine();
           if (!response.startsWith("250")) {
               throw new IOException("DATA content failed: " + response);
           }
           logger.info("DATA content response: {}", response);

           // 發送 QUIT
           writer.println("QUIT");
           response = reader.readLine();
           if (!response.startsWith("221")) {
               throw new IOException("QUIT failed: " + response);
           }
           logger.info("QUIT response: {}", response);
       } finally {
           // 手動關閉資源
           if (reader != null) try { reader.close(); } catch (IOException ignored) {}
           if (writer != null) try { writer.close(); } catch (Exception ignored) {}
           if (sslSocket != null) try { sslSocket.close(); } catch (IOException ignored) {}
           if (socket != null) try { socket.close(); } catch (IOException ignored) {}
       }
   }

   private String readMultiLineResponse(BufferedReader reader) throws IOException {
       StringBuilder response = new StringBuilder();
       String line;
       while ((line = reader.readLine()) != null) {
           response.append(line).append("\n");
           if (line.matches("\\d{3}\\s.*")) {
               break;
           }
       }
       return response.toString();
   }
}