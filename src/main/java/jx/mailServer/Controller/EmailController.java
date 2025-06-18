package jx.mailServer.Controller;

import jx.mailServer.Service.MailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailController {
    private final MailService emailSender;

    public EmailController(MailService emailSender) {
        this.emailSender = emailSender;
    }

    @GetMapping("/sendEmail")
    public String sendEmail(
                @RequestParam("to") String to ,
//                @RequestParam(value = "cc" , required = false) String[] cc ,
                @RequestParam("subject") String subject ,
                @RequestParam("content") String content
//                @RequestParam(value = "file" , required = false) MultipartFile file
            ) throws Exception {
        String sendMailSuccess = emailSender.sendEmail( to ,subject , content );
        System.out.println(sendMailSuccess);
        return sendMailSuccess;
    }
}
