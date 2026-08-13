package com.rag.eval.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PIIRedactionServiceTest {

    private PIIRedactionService service;

    @BeforeEach
    void setUp() {
        service = new PIIRedactionService();
        service.setEnabled(true);

        var phone = new PIIRedactionService.PatternConfig();
        phone.setName("cn_phone");
        phone.setRegex("1[3-9]\\d{9}");
        phone.setReplacement("[PHONE_REDACTED]");

        var email = new PIIRedactionService.PatternConfig();
        email.setName("email");
        email.setRegex("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
        email.setReplacement("[EMAIL_REDACTED]");

        var idCard = new PIIRedactionService.PatternConfig();
        idCard.setName("cn_id");
        idCard.setRegex("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]");
        idCard.setReplacement("[ID_REDACTED]");

        // Apply ID card first to avoid phone regex matching within ID numbers
        service.setPatterns(List.of(idCard, phone, email));
    }

    @Test
    void redact_phoneNumber_masks() {
        String result = service.redact("请联系我：13812345678");
        assertTrue(result.contains("[PHONE_REDACTED]"));
        assertFalse(result.contains("13812345678"));
    }

    @Test
    void redact_email_masks() {
        String result = service.redact("邮箱: test@example.com");
        assertTrue(result.contains("[EMAIL_REDACTED]"));
        assertFalse(result.contains("test@example.com"));
    }

    @Test
    void redact_idCard_masks() {
        // 6 digits + 19900101 + 1234 = 18-digit valid-format CN ID
        String testId = "110101199001011234";
        String result = service.redact("身份证: " + testId);
        assertTrue(result.contains("[ID_REDACTED]"), "Expected ID to be redacted in: " + result);
        assertFalse(result.contains(testId));
    }

    @Test
    void redact_noPII_unchanged() {
        String text = "这是一段没有任何个人信息的普通文本。";
        assertEquals(text, service.redact(text));
    }

    @Test
    void redact_disabled_unchanged() {
        service.setEnabled(false);
        String text = "phone: 13812345678";
        assertEquals(text, service.redact(text));
    }

    @Test
    void redactCount_countsCorrectly() {
        String text = "电话13812345678，邮箱test@example.com，电话13987654321";
        assertEquals(3, service.redactCount(text));
    }
}
