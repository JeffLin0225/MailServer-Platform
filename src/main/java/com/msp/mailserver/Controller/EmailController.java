package com.msp.mailserver.Controller;

import com.msp.mailserver.Model.MxRecord;
import com.msp.mailserver.Model.SendEmailRequest;
import com.msp.mailserver.Service.MailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class EmailController {

    private final MailService mailService;

    public EmailController(MailService mailService) {
        this.mailService = mailService;
    }

    /**
     * 向下相容 GET /sendEmail 測試端點
     */
    @GetMapping("/sendEmail")
    public ResponseEntity<Map<String, Object>> sendEmailLegacy(
            @RequestParam("to") String to,
            @RequestParam("subject") String subject,
            @RequestParam("content") String content,
            @RequestParam(value = "from", required = false) String from) {
        return sendEmailSync(new SendEmailRequest(to, from, subject, content));
    }

    /**
     * 同步發送郵件端點 (POST JSON)
     */
    @PostMapping("/api/mail/send")
    public ResponseEntity<Map<String, Object>> sendEmailSync(@RequestBody SendEmailRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (request.getTo() == null || !request.getTo().contains("@")) {
                response.put("success", false);
                response.put("message", "Invalid recipient email address.");
                return ResponseEntity.badRequest().body(response);
            }

            String result = mailService.sendEmail(request.getTo(), request.getFrom(), request.getSubject(), request.getContent());
            response.put("success", true);
            response.put("message", result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 高可用非同步發送端點 (POST JSON)
     */
    @PostMapping("/api/mail/send-async")
    public ResponseEntity<Map<String, Object>> sendEmailAsync(@RequestBody SendEmailRequest request) {
        Map<String, Object> response = new HashMap<>();
        if (request.getTo() == null || !request.getTo().contains("@")) {
            response.put("success", false);
            response.put("message", "Invalid recipient email address.");
            return ResponseEntity.badRequest().body(response);
        }

        // 派發至 ThreadPool 非同步外發
        mailService.sendEmailAsync(request.getTo(), request.getFrom(), request.getSubject(), request.getContent());
        response.put("success", true);
        response.put("status", "QUEUED");
        response.put("message", "Email task submitted to background executor.");
        return ResponseEntity.accepted().body(response);
    }

    /**
     * DNS MX 路由診斷端點：查詢目標網域的 MX 伺服器清單與優先級
     */
    @GetMapping("/api/mail/mx-lookup")
    public ResponseEntity<Map<String, Object>> lookupMx(@RequestParam("domain") String domain) {
        Map<String, Object> response = new HashMap<>();
        List<MxRecord> records = mailService.getMxRecordsSorted(domain);
        response.put("domain", domain);
        response.put("count", records.size());
        response.put("mxRecords", records);
        return ResponseEntity.ok(response);
    }
}
