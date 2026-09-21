package com.bengalilkc.service.mail;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.config.LkcProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Outbound transactional email via SMTP (Gmail App Password).
 * OTP and credential messages are sent as no-reply automated mail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final LkcProperties properties;

    public boolean isConfigured() {
        String user = properties.getMail().getUsername();
        String pass = properties.getMail().getPassword();
        return user != null && !user.isBlank() && pass != null && !pass.isBlank();
    }

    public void sendCredentialIssued(String toEmail, String name, String username, String oneTimePassword) {
        String subject = "Bengali LKC — Your login credentials";
        String text = credentialText(name, toEmail, username, oneTimePassword, "account has been created");
        String html = credentialHtml(name, toEmail, username, oneTimePassword, "account has been created");
        sendNoReply(toEmail, subject, text, html);
    }

    public void sendCredentialReset(String toEmail, String name, String username, String oneTimePassword) {
        String subject = "Bengali LKC — Password reset";
        String text = credentialText(name, toEmail, username, oneTimePassword, "password was reset");
        String html = credentialHtml(name, toEmail, username, oneTimePassword, "password was reset");
        sendNoReply(toEmail, subject, text, html);
    }

    public void sendPasswordChangeOtp(String toEmail, String name, String otp, int validMinutes) {
        String subject = "Bengali LKC — Verification code";
        String text = """
                Dear %s,

                Your password-change verification code is: %s

                This code is valid for %d minutes. Do not share it with anyone.

                This is an automated message. Please do not reply to this email.

                — Bengali LKC
                """.formatted(name, otp, validMinutes);
        String html = """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#0f172a">
                  <h2 style="color:#047857;margin:0 0 12px">Verification code</h2>
                  <p>Dear <strong>%s</strong>,</p>
                  <p>Your password-change verification code is:</p>
                  <p style="font-size:28px;letter-spacing:0.35em;font-weight:700;background:#ecfdf5;border:1px solid #a7f3d0;border-radius:12px;padding:16px;text-align:center;color:#065f46">%s</p>
                  <p style="color:#64748b;font-size:14px">Valid for <strong>%d</strong> minutes. If you did not request this, ignore this email.</p>
                  <p style="margin-top:20px;font-size:12px;color:#94a3b8">This is an automated message. Please do not reply.</p>
                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0"/>
                  <p style="font-size:12px;color:#94a3b8">Bengali Local Knowledge Core (Ben-LKC)</p>
                </div>
                """.formatted(escape(name), escape(otp), validMinutes);
        sendNoReply(toEmail, subject, text, html);
    }

    public void sendForgotPasswordOtp(String toEmail, String name, String otp, int validMinutes) {
        String subject = "Bengali LKC — Password reset verification code";
        String text = """
                Dear %s,

                You requested a password reset for your Ben-LKC account.

                Your verification code is: %s

                This code is valid for %d minutes. Do not share it with anyone.

                Enter this code on the forgot-password page together with your new password.
                You can then sign in with your username or email and the new password.

                If you did not request this, ignore this email — your password will not change.

                This is an automated message. Please do not reply to this email.

                — Bengali LKC
                """.formatted(name, otp, validMinutes);
        String html = """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#0f172a">
                  <h2 style="color:#047857;margin:0 0 12px">Password reset code</h2>
                  <p>Dear <strong>%s</strong>,</p>
                  <p>You requested a password reset for your Ben-LKC account. Your verification code is:</p>
                  <p style="font-size:28px;letter-spacing:0.35em;font-weight:700;background:#ecfdf5;border:1px solid #a7f3d0;border-radius:12px;padding:16px;text-align:center;color:#065f46">%s</p>
                  <p style="color:#64748b;font-size:14px">Valid for <strong>%d</strong> minutes. Enter it on the forgot-password page with your new password.</p>
                  <p style="font-size:14px;color:#334155;line-height:1.55;background:#ecfdf5;border:1px solid #a7f3d0;border-radius:10px;padding:12px 14px;margin:12px 0">
                    After resetting, you can sign in with either your <strong>username</strong> or your <strong>email address</strong>, together with the new password.
                  </p>
                  <p style="color:#64748b;font-size:13px">If you did not request this, ignore this email — your password will not change.</p>
                  <p style="margin-top:20px;font-size:12px;color:#94a3b8">This is an automated message. Please do not reply.</p>
                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0"/>
                  <p style="font-size:12px;color:#94a3b8">Bengali Local Knowledge Core (Ben-LKC)</p>
                </div>
                """.formatted(escape(name), escape(otp), validMinutes);
        sendNoReply(toEmail, subject, text, html);
    }

    public void sendForgotPassword(String toEmail, String name, String username, String oneTimePassword) {
        String subject = "Bengali LKC — Password reset requested";
        String text = credentialText(name, toEmail, username, oneTimePassword, "password reset was requested");
        String html = credentialHtml(name, toEmail, username, oneTimePassword, "password reset was requested");
        sendNoReply(toEmail, subject, text, html);
    }

    private String credentialText(
            String name, String email, String username, String password, String reason) {
        return """
                Dear %s,

                Your Bengali Local Knowledge Core (Ben-LKC) %s.

                Email: %s
                Username: %s
                Temporary password: %s

                How to sign in:
                You can log in with either your username OR your email address, together with your password, on the Ben-LKC login page.

                You must change this temporary password right after your first sign-in.

                This is an automated message. Please do not reply to this email.

                — Bengali LKC
                """.formatted(name, reason, email, username, password);
    }

    private String credentialHtml(
            String name, String email, String username, String password, String reason) {
        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#0f172a">
                  <h2 style="color:#047857;margin:0 0 12px">Ben-LKC login details</h2>
                  <p>Dear <strong>%s</strong>,</p>
                  <p>Your Ben-LKC %s. Use the details below to sign in:</p>
                  <table style="width:100%%;border-collapse:collapse;margin:16px 0;background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px">
                    <tr><td style="padding:12px 16px;color:#64748b;width:160px">Email</td><td style="padding:12px 16px;font-family:ui-monospace,monospace;font-weight:700">%s</td></tr>
                    <tr><td style="padding:12px 16px;color:#64748b;border-top:1px solid #e2e8f0">Username</td><td style="padding:12px 16px;font-family:ui-monospace,monospace;font-weight:700;border-top:1px solid #e2e8f0">%s</td></tr>
                    <tr><td style="padding:12px 16px;color:#64748b;border-top:1px solid #e2e8f0">Temporary password</td><td style="padding:12px 16px;font-family:ui-monospace,monospace;font-weight:700;border-top:1px solid #e2e8f0;color:#b45309">%s</td></tr>
                  </table>
                  <p style="font-size:14px;color:#334155;line-height:1.55;background:#ecfdf5;border:1px solid #a7f3d0;border-radius:10px;padding:12px 14px;margin:0 0 12px">
                    <strong>How to sign in:</strong> You can log in with either your <strong>username</strong> or your <strong>email address</strong>, together with your password.
                  </p>
                  <p style="font-size:14px;color:#334155">You <strong>must change</strong> this temporary password after first sign-in.</p>
                  <p style="margin-top:16px;font-size:12px;color:#94a3b8">This is an automated message. Please do not reply.</p>
                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0"/>
                  <p style="font-size:12px;color:#94a3b8">Bengali Local Knowledge Core — invitation-only platform.</p>
                </div>
                """.formatted(
                escape(name),
                escape(reason),
                escape(email),
                escape(username),
                escape(password));
    }

    /** Sends mail with no Reply-To so clients treat it as no-reply. */
    private void sendNoReply(String toEmail, String subject, String text, String html) {
        if (!isConfigured()) {
            log.warn("SMTP mail is not configured — email to {} skipped ({})", toEmail, subject);
            return;
        }
        try {
            String fromAddress = properties.getMail().getFrom();
            if (fromAddress == null || fromAddress.isBlank()) {
                fromAddress = properties.getMail().getUsername();
            }
            String fromName = properties.getMail().getFromName();
            if (fromName == null || fromName.isBlank()) {
                fromName = "Bengali LKC (No Reply)";
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text, html);
            // Intentionally no Reply-To — automated / no-reply mail
            mailSender.send(message);
            log.info("Email sent to {} ({})", toEmail, subject);
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", toEmail, ex.getMessage(), ex);
            throw ApiException.unprocessable(
                    "Could not send email to " + toEmail + ". Please verify SMTP settings and try again.");
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
