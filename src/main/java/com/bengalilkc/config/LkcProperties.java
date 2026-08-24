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
        private String cookieName = "lkc_session";
        private String headerName = "Authorization";
        private String tokenPrefix = "Bearer ";
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
        private String from = "noreply@bengali-lkc.local";
        private boolean demoExposeSecrets = true;
    }
}
