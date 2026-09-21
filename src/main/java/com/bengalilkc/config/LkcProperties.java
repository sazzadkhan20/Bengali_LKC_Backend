package com.bengalilkc.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lkc")
public class LkcProperties {

    private Security security = new Security();
    private Bootstrap bootstrap = new Bootstrap();
    private Mail mail = new Mail();

    @Data
    public static class Security {
        private Jwt jwt = new Jwt();
        private Cors cors = new Cors();
    }

    @Data
    public static class Jwt {
        private String secret;
        private String issuer = "bengali-lkc";
        /** HttpOnly cookie holding the access JWT (1 hour session by default). */
        private String cookieName = "lkc_session";
        /** HttpOnly cookie holding the opaque refresh token. */
        private String refreshCookieName = "lkc_refresh";
        private String headerName = "Authorization";
        private String tokenPrefix = "Bearer ";
        /** Access-token / session lifetime in minutes (overridden by system_config when present). */
        private long accessTtlMinutes = 60;
        /** Refresh-token lifetime in days. */
        private long refreshTtlDays = 7;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000"));
        private String allowedMethods = "GET,POST,PUT,PATCH,DELETE,OPTIONS";
        private String allowedHeaders = "*";
        private boolean allowCredentials = true;
    }

    @Data
    public static class Bootstrap {
        private boolean seedDemoUsers = true;
        private boolean importLexiconXml = false;
        private String lexiconXmlPath = "Documents/Bangla Mono Lingual Lexicons.xml";
    }

    @Data
    public static class Mail {
        /** SMTP username (often same as From address). */
        private String username = "";
        /** SMTP password / app password. */
        private String password = "";
        private String from = "noreply@bengali-lkc.local";
        /** Display name shown in the From field (e.g. No Reply). */
        private String fromName = "Bengali LKC (No Reply)";
        private boolean demoExposeSecrets = true;
    }
}
