package com.bengalilkc.service.admin;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.mail.EmailService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Apply to contribute" requests from the public site. The Platform Architect reviews them; approval
 * happens by issuing a real account in Credential Management, which links the new user back here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    public static final Set<String> INTEREST_ROLES =
            Set.of("CONTRIBUTOR", "LEXICAL_EDITOR", "CHIEF_LEXICAL_EDITOR", "ARBITRATION_CURATOR", "OTHER");
    private static final Set<String> STATUSES = Set.of("NEW", "APPROVED", "REJECTED");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9()\\-.\\s]{6,30}$");
    private static final int MAX_PER_EMAIL_PER_DAY = 3;
    private static final int MAX_PER_IP_PER_DAY = 10;
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final String REGISTERED_EMAIL_MESSAGE =
            "This email is already registered with a Ben-LKC account. Sign in instead, or use “Forgot password” if you cannot access it.";
    private static final String PENDING_EMAIL_MESSAGE =
            "An application from this email is already awaiting review. The Platform Architect will contact you by email.";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final EmailService emailService;

    // ------------------------------------------------------------------ public submit

    @Transactional
    public Map<String, Object> submit(Map<String, Object> body, String clientIp) {
        if (!str(body.get("honeypot")).isBlank()) {
            // Bots get the normal response so they do not learn about the trap.
            log.warn("Discarded application with a filled honeypot from {}", clientIp);
            return Map.of("ok", true, "message", acknowledgement());
        }
        String name = collapse(str(body.get("name")));
        String email = str(body.get("email")).trim().toLowerCase();
        String phone = collapse(str(body.get("phone")));
        String institution = collapse(str(body.get("institution")));
        String occupation = collapse(str(body.get("occupation")));
        String dobRaw = str(body.get("dob")).trim();
        String interestRole = str(body.get("interestRole")).trim().toUpperCase();
        String message = str(body.get("message")).trim();

        Map<String, String> errors = new LinkedHashMap<>();
        if (name.length() < 2) {
            errors.put("name", "Please enter your full name.");
        } else if (name.length() > 80) {
            errors.put("name", "Name must be at most 80 characters.");
        }
        if (email.isEmpty()) {
            errors.put("email", "Email address is required.");
        } else if (email.length() > 120 || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Enter a valid email address.");
        }
        if (phone.isEmpty()) {
            errors.put("phone", "Phone number is required.");
        } else if (!PHONE.matcher(phone).matches() || phone.chars().filter(Character::isDigit).count() < 6) {
            errors.put("phone", "Enter a reachable phone number (digits, spaces, + - ( ) only).");
        }
        LocalDate dob = null;
        if (dobRaw.isEmpty()) {
            errors.put("dob", "Date of birth is required.");
        } else {
            try {
                dob = LocalDate.parse(dobRaw);
            } catch (DateTimeParseException e) {
                errors.put("dob", "Enter a valid date of birth.");
            }
            LocalDate today = LocalDate.now(DHAKA);
            if (dob != null && !dob.isBefore(today)) {
                errors.put("dob", "Date of birth must be in the past.");
            } else if (dob != null && dob.isBefore(today.minusYears(120))) {
                errors.put("dob", "Enter a valid date of birth.");
            }
        }
        if (institution.isEmpty()) {
            errors.put("institution", "Institution or affiliation is required.");
        } else if (institution.length() > 120) {
            errors.put("institution", "Institution must be at most 120 characters.");
        }
        if (occupation.isEmpty()) {
            errors.put("occupation", "Occupation is required.");
        } else if (occupation.length() > 80) {
            errors.put("occupation", "Occupation must be at most 80 characters.");
        }
        if (!interestRole.isEmpty() && !INTEREST_ROLES.contains(interestRole)) {
            errors.put("interestRole", "Select one of the listed roles.");
        }
        if (message.length() < 20) {
            errors.put("message", "Please explain why you want to join (at least 20 characters).");
        } else if (message.length() > 800) {
            errors.put("message", "Message must be at most 800 characters.");
        }
        if (!errors.containsKey("email")) {
            String conflict = emailConflict(email);
            if (conflict != null) {
                errors.put("email", conflict);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable(errors);
        }

        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
        Integer byEmail = jdbc.queryForObject(
                "select count(*) from contribute_application where lower(email) = :email and created_at >= :since",
                new MapSqlParameterSource("email", email).addValue("since", since), Integer.class);
        if (byEmail != null && byEmail >= MAX_PER_EMAIL_PER_DAY) {
            throw ApiException.tooManyRequests(
                    "You have already sent " + MAX_PER_EMAIL_PER_DAY
                            + " requests from this email in the last 24 hours. Please wait before submitting again.");
        }
        String ip = clientIp == null ? null : clientIp.trim();
        if (ip != null && !ip.isEmpty()) {
            Integer byIp = jdbc.queryForObject(
                    "select count(*) from contribute_application where client_ip = :ip and created_at >= :since",
                    new MapSqlParameterSource("ip", ip).addValue("since", since), Integer.class);
            if (byIp != null && byIp >= MAX_PER_IP_PER_DAY) {
                throw ApiException.tooManyRequests(
                        "Too many requests from your network in the last 24 hours. Please try again tomorrow.");
            }
        }

        String id = "app-" + idGeneratorService.nextValue("application");
        try {
            jdbc.update("""
                    insert into contribute_application
                        (id, name, email, phone, institution, occupation, dob, interest_role, message, client_ip)
                    values (:id, :name, :email, :phone, :institution, :occupation, :dob, :role, :message, :ip)
                    """, new MapSqlParameterSource("id", id)
                    .addValue("name", name)
                    .addValue("email", email)
                    .addValue("phone", phone)
                    .addValue("institution", institution)
                    .addValue("occupation", occupation)
                    .addValue("dob", dob)
                    .addValue("role", interestRole.isEmpty() ? null : interestRole)
                    .addValue("message", message)
                    .addValue("ip", ip == null || ip.isEmpty() ? null : truncate(ip, 64)));
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable(Map.of("email", PENDING_EMAIL_MESSAGE));
        }
        auditService.recordSystem("APPLICATION_RECEIVED", AuditTargetType.APPLICATION, id,
                name + " <" + email + ">" + (interestRole.isEmpty() ? "" : " — interested in " + interestRole));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("message", acknowledgement());
        return out;
    }

    /** Why this email cannot apply, or null when it is free to use. */
    private String emailConflict(String email) {
        MapSqlParameterSource args = new MapSqlParameterSource("email", email);
        Boolean registered = jdbc.queryForObject(
                "select exists (select 1 from app_user where lower(email) = :email)", args, Boolean.class);
        if (Boolean.TRUE.equals(registered)) {
            return REGISTERED_EMAIL_MESSAGE;
        }
        Boolean pending = jdbc.queryForObject(
                "select exists (select 1 from contribute_application where lower(email) = :email and status = 'NEW')",
                args, Boolean.class);
        return Boolean.TRUE.equals(pending) ? PENDING_EMAIL_MESSAGE : null;
    }

    private static String acknowledgement() {
        return "Your request was received. The Platform Architect reviews every application and will contact you by email.";
    }

    // ------------------------------------------------------------------ architect review

    @Transactional(readOnly = true)
    public Map<String, Object> list(String statusRaw, String q, int page, int size) {
        String status = statusRaw == null || statusRaw.isBlank() ? "NEW" : statusRaw.trim().toUpperCase();
        if (!"ALL".equals(status) && !STATUSES.contains(status)) {
            throw ApiException.unprocessable(Map.of("status", "Select a valid status."));
        }
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        String query = q == null ? "" : q.trim().toLowerCase();

        MapSqlParameterSource args = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (!"ALL".equals(status)) {
            where.append(" and a.status = :status");
            args.addValue("status", status);
        }
        if (!query.isEmpty()) {
            where.append(" and (lower(a.name) like :q or lower(a.email) like :q or lower(coalesce(a.institution, '')) like :q"
                    + " or lower(a.id) = :exact)");
            args.addValue("q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            args.addValue("exact", query);
        }
        long total = jdbc.queryForObject("select count(*) from contribute_application a" + where, args, Long.class);
        args.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.query(SELECT + where
                + " order by a.created_at desc, a.id desc limit :limit offset :offset", args, (rs, i) -> row(rs));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("NEW", 0L);
        counts.put("APPROVED", 0L);
        counts.put("REJECTED", 0L);
        jdbc.query("select status, count(*) from contribute_application group by status", Map.of(),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        counts.put("ALL", counts.values().stream().mapToLong(v -> (Long) v).sum());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("status", status);
        body.put("q", query);
        body.put("counts", counts);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", total);
        body.put("totalPages", Math.max(1, (int) Math.ceil(total / (double) safeSize)));
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return find(id);
    }

    @Transactional(readOnly = true)
    public long countNew() {
        Long n = jdbc.queryForObject("select count(*) from contribute_application where status = 'NEW'", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public Map<String, Object> reject(UserPrincipal principal, String id, Map<String, Object> body) {
        Map<String, Object> app = find(id);
        requireNew(app);
        String note = str(body.get("note")).trim();
        if (note.length() < 5) {
            throw ApiException.unprocessable(Map.of("note", "Give the applicant a short reason (at least 5 characters)."));
        }
        if (note.length() > 500) {
            throw ApiException.unprocessable(Map.of("note", "The note must be at most 500 characters."));
        }
        boolean notify = !Boolean.FALSE.equals(body.get("notify"));
        int updated = jdbc.update("""
                update contribute_application
                set status = 'REJECTED', review_note = :note, reviewed_by = :by, reviewed_at = now()
                where id = :id and status = 'NEW'
                """, new MapSqlParameterSource("id", app.get("id")).addValue("note", note).addValue("by", principal.getId()));
        if (updated == 0) {
            throw ApiException.conflict("This application was already reviewed. Refresh the page.");
        }
        auditService.record(principal, "APPLICATION_REJECTED", AuditTargetType.APPLICATION, (String) app.get("id"), note);

        boolean emailed = false;
        if (notify && emailService.isConfigured()) {
            try {
                emailService.sendApplicationDeclined((String) app.get("email"), (String) app.get("name"), note);
                emailed = true;
            } catch (RuntimeException e) {
                log.warn("Could not email decision for application {}: {}", app.get("id"), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(find((String) app.get("id")));
        out.put("emailed", emailed);
        return out;
    }

    /**
     * Called inside the user-creation transaction when an account is issued for an application.
     * Locks the row so two architects cannot approve the same request.
     */
    @Transactional
    public Map<String, Object> lockForApproval(String id) {
        List<String> locked = jdbc.queryForList(
                "select id from contribute_application where id = :id for update",
                new MapSqlParameterSource("id", id == null ? "" : id.trim()), String.class);
        if (locked.isEmpty()) {
            throw ApiException.notFound("Application not found.");
        }
        Map<String, Object> app = find(locked.get(0));
        requireNew(app);
        return app;
    }

    @Transactional
    public void markApproved(UserPrincipal principal, String id, String issuedUserId, String username, String role) {
        jdbc.update("""
                update contribute_application
                set status = 'APPROVED', issued_user_id = :user, reviewed_by = :by, reviewed_at = now(),
                    review_note = :note
                where id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("user", issuedUserId)
                .addValue("by", principal.getId())
                .addValue("note", "Account " + username + " issued as " + role + "."));
        auditService.record(principal, "APPLICATION_APPROVED", AuditTargetType.APPLICATION, id,
                "Issued account " + username + " (" + issuedUserId + ", " + role + ")");
    }

    // ------------------------------------------------------------------ helpers

    private static final String SELECT = """
            select a.id, a.name, a.email, a.phone, a.institution, a.occupation, a.dob, a.interest_role, a.message,
                   a.status, a.review_note, a.reviewed_at, a.created_at, a.issued_user_id,
                   r.name as reviewer_name, u.username as issued_username, u.role as issued_role,
                   e.id as existing_user_id, e.username as existing_username
            from contribute_application a
            left join app_user r on r.id = a.reviewed_by
            left join app_user u on u.id = a.issued_user_id
            left join lateral (
                select x.id, x.username from app_user x where lower(x.email) = lower(a.email) limit 1
            ) e on true
            """;

    private Map<String, Object> find(String rawId) {
        String id = rawId == null ? "" : rawId.trim();
        List<Map<String, Object>> rows = jdbc.query(SELECT + " where a.id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> row(rs));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Application not found.");
        }
        return rows.get(0);
    }

    private static void requireNew(Map<String, Object> app) {
        if (!"NEW".equals(app.get("status"))) {
            throw ApiException.conflict("This application was already "
                    + String.valueOf(app.get("status")).toLowerCase() + ".");
        }
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("name", rs.getString("name"));
        m.put("email", rs.getString("email"));
        m.put("phone", rs.getString("phone"));
        m.put("institution", rs.getString("institution"));
        m.put("occupation", rs.getString("occupation"));
        java.sql.Date dob = rs.getDate("dob");
        m.put("dob", dob == null ? null : dob.toLocalDate().toString());
        m.put("interestRole", rs.getString("interest_role"));
        m.put("message", rs.getString("message"));
        m.put("status", rs.getString("status"));
        m.put("reviewNote", rs.getString("review_note"));
        m.put("reviewedAt", iso(rs.getTimestamp("reviewed_at")));
        m.put("reviewerName", rs.getString("reviewer_name"));
        m.put("createdAt", iso(rs.getTimestamp("created_at")));
        m.put("issuedUserId", rs.getString("issued_user_id"));
        m.put("issuedUsername", rs.getString("issued_username"));
        m.put("issuedRole", rs.getString("issued_role"));
        String existingUserId = rs.getString("existing_user_id");
        boolean issuedToThatUser = existingUserId != null && existingUserId.equals(rs.getString("issued_user_id"));
        m.put("existingAccount", existingUserId == null || issuedToThatUser
                ? null
                : Map.of("id", existingUserId, "username", rs.getString("existing_username")));
        return m;
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String collapse(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
