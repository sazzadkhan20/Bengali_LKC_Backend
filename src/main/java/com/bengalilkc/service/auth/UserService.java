package com.bengalilkc.service.auth;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.ApplicationService;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.mail.EmailService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,30}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final EmailService emailService;
    private final ApplicationService applicationService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAllByOrderByJoinedAtAsc().stream().map(this::toDto).toList();
    }

    /**
     * Paginated User Management list — search (name / username / email), role, status.
     * All filtering runs in the database for speed.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listPaged(int page, int size, String q, String roleRaw, String statusRaw) {
        int safeSize = size == 15 || size == 30 || size == 50 || size == 100 ? size : 15;
        int safePage = Math.max(page, 0);
        String query = q == null ? "" : q.trim();

        Role role = null;
        if (roleRaw != null && !roleRaw.isBlank() && !"ALL".equalsIgnoreCase(roleRaw.trim())) {
            try {
                role = Role.valueOf(roleRaw.trim());
            } catch (Exception e) {
                throw ApiException.unprocessable("Select a valid role filter.");
            }
        }

        String status = "ALL";
        if (statusRaw != null && !statusRaw.isBlank()) {
            status = statusRaw.trim().toUpperCase();
            if (!List.of("ALL", "ACTIVE", "SUSPENDED", "INACTIVE").contains(status)) {
                throw ApiException.unprocessable("Select a valid status filter.");
            }
        }

        Page<AppUser> result = userRepository.search(
                query.isEmpty() ? null : query,
                role,
                "ALL".equals(status) ? null : status,
                PageRequest.of(safePage, safeSize));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent().stream().map(this::toDto).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("q", query);
        body.put("role", role != null ? role.name() : "ALL");
        body.put("status", status);
        return body;
    }

    /** Lightweight payload for frontend duplicate checks before create. */
    @Transactional(readOnly = true)
    public Map<String, Object> listIdentifiers() {
        List<AppUser> users = userRepository.findAllByOrderByJoinedAtAsc();
        List<String> usernames = users.stream().map(u -> u.getUsername().toLowerCase()).toList();
        List<String> emails = users.stream().map(u -> u.getEmail().toLowerCase()).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usernames", usernames);
        body.put("emails", emails);
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUser(String id) {
        return toDto(require(id));
    }

    @Transactional
    public Map<String, Object> createUser(UserPrincipal principal, Map<String, Object> payload) {
        String name = str(payload.get("name")).trim();
        String username = str(payload.get("username")).trim().toLowerCase();
        String email = str(payload.get("email")).trim().toLowerCase();
        String dob = str(payload.get("dob")).trim();
        String institution = str(payload.get("institution")).trim();
        String occupation = str(payload.get("occupation")).trim();
        String roleRaw = str(payload.get("role")).trim();

        if (name.isEmpty()) {
            throw ApiException.unprocessable("Full name is required.");
        }
        LocalDate dobDate;
        try {
            dobDate = LocalDate.parse(dob);
        } catch (Exception e) {
            throw ApiException.unprocessable("A valid date of birth is required (author information).");
        }
        if (!dobDate.isBefore(LocalDate.now())) {
            throw ApiException.unprocessable("Date of birth must be in the past.");
        }
        if (institution.isEmpty()) {
            throw ApiException.unprocessable("Institution is required (author information).");
        }
        if (occupation.isEmpty()) {
            throw ApiException.unprocessable("Occupation is required (author information).");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.unprocessable("Username must be 3–30 characters (letters, digits, . _ -).");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw ApiException.unprocessable("A valid registered email address is required.");
        }
        Role role;
        try {
            role = Role.valueOf(roleRaw);
        } catch (Exception e) {
            throw ApiException.unprocessable("Select a valid role.");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("This username is already taken.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists.");
        }
        String applicationId = str(payload.get("applicationId")).trim();
        if (!applicationId.isEmpty()) {
            applicationService.lockForApproval(applicationId);
        }

        String userId = "u-" + idGeneratorService.nextValue("user");
        AppUser user = AppUser.builder()
                .id(userId)
                .username(username)
                .name(name)
                .email(email)
                .role(role)
                .active(true)
                .suspended(false)
                .autoAnnounce(true)
                .dob(dobDate)
                .institution(institution)
                .occupation(occupation)
                .joinedAt(Instant.now())
                .build();
        userRepository.save(user);

        String oneTimePassword = "lkc-" + UUID.randomUUID().toString().substring(0, 10);
        String credId = "cred-" + idGeneratorService.nextValue("credential");
        Credential credential = Credential.builder()
                .id(credId)
                .userId(userId)
                .username(username)
                .passwordHash(passwordEncoder.encode(oneTimePassword))
                .oneTime(true)
                .issuedBy(principal.getId())
                .issuedAt(Instant.now())
                .deactivated(false)
                .build();
        credentialRepository.save(credential);

        auditService.record(principal, "USER_CREATED", AuditTargetType.USER, userId, name + " (" + role + ")");
        auditService.record(principal, "CREDENTIAL_ISSUED", AuditTargetType.CREDENTIAL, credId,
                "One-time password emailed to " + email + "; change required at first login");
        if (!applicationId.isEmpty()) {
            userRepository.flush();
            applicationService.markApproved(principal, applicationId, userId, username, role.name());
        }

        emailService.sendCredentialIssued(email, name, username, oneTimePassword);

        Map<String, Object> credBody = new LinkedHashMap<>();
        credBody.put("username", username);
        credBody.put("email", email);
        // Architect UI always receives the OTP once so issuance can be confirmed.
        credBody.put("demoOneTimePassword", oneTimePassword);
        credBody.put("emailSent", emailService.isConfigured());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("user", Map.of("id", userId, "name", name, "role", role.name()));
        body.put("credential", credBody);
        if (!applicationId.isEmpty()) {
            body.put("applicationId", applicationId);
        }
        return body;
    }

    @Transactional
    public Map<String, Object> patchUser(UserPrincipal principal, String id, Map<String, Object> payload) {
        AppUser user = require(id);
        if (payload.containsKey("autoAnnounce") && payload.get("autoAnnounce") instanceof Boolean auto) {
            if (!principal.getId().equals(user.getId())) {
                throw ApiException.forbidden("You can only change your own announcement preference.");
            }
            user.setAutoAnnounce(auto);
            userRepository.save(user);
            return Map.of("ok", true);
        }
        boolean changesStatus = payload.get("suspended") instanceof Boolean || payload.get("active") instanceof Boolean;
        if (changesStatus && !PermissionMatrix.has(principal.getRole(), Permission.MANAGE_USERS)) {
            throw ApiException.forbidden("Only the Platform Architect can change account status.");
        }
        if (changesStatus && principal.getId().equals(user.getId())) {
            throw ApiException.conflict("You cannot change the status of your own account.");
        }
        if (payload.get("suspended") instanceof Boolean suspended) {
            user.setSuspended(suspended);
            credentialRepository.findByUserId(user.getId()).ifPresent(c -> {
                c.setDeactivated(suspended);
                credentialRepository.save(c);
            });
            auditService.record(
                    principal,
                    suspended ? "ACCOUNT_SUSPENDED" : "ACCOUNT_REINSTATED",
                    AuditTargetType.USER,
                    user.getId(),
                    user.getName() + " (" + user.getRole() + ") — manual review by Platform Architect");
        }
        if (payload.get("active") instanceof Boolean active) {
            user.setActive(active);
            auditService.record(
                    principal,
                    active ? "ACCOUNT_ACTIVATED" : "ACCOUNT_DEACTIVATED",
                    AuditTargetType.USER,
                    user.getId(),
                    user.getName());
        }
        userRepository.save(user);
        return Map.of("ok", true);
    }

    private AppUser require(String id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
    }

    public Map<String, Object> toDto(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("name", u.getName());
        m.put("role", u.getRole().name());
        m.put("email", u.getEmail());
        m.put("active", u.isActive());
        m.put("suspended", u.isSuspended());
        m.put("joinedAt", u.getJoinedAt() != null ? u.getJoinedAt().toString().substring(0, Math.min(10, u.getJoinedAt().toString().length())) : null);
        if (u.getJoinedAt() != null) {
            m.put("joinedAt", LocalDate.ofInstant(u.getJoinedAt(), java.time.ZoneOffset.UTC).toString());
        }
        m.put("autoAnnounce", u.isAutoAnnounce());
        m.put("dob", u.getDob() != null ? u.getDob().toString() : null);
        m.put("institution", u.getInstitution());
        m.put("occupation", u.getOccupation());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
