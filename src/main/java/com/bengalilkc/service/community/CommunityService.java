package com.bengalilkc.service.community;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.community.CommunityPost;
import com.bengalilkc.domain.entity.community.PostReaction;
import com.bengalilkc.domain.entity.community.PostReply;
import com.bengalilkc.domain.entity.community.PostReport;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.Permission;
import com.bengalilkc.domain.enums.ReactionType;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.community.CommunityPostRepository;
import com.bengalilkc.repository.community.PostReactionRepository;
import com.bengalilkc.repository.community.PostReplyRepository;
import com.bengalilkc.repository.community.PostReportRepository;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.security.PermissionMatrix;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.auth.RefreshTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Author Community Board (SRS §14): text posts, reactions, single-level replies, reports and
 * System Moderator moderation. Feed reads are paged and batched (one query per page plus one for
 * its replies and, for moderators, one for its reports).
 */
@Service
public class CommunityService {

    public static final int POST_MAX = 500;
    public static final int REPLY_MAX = 200;
    public static final int REASON_MAX = 300;
    private static final int DEFAULT_PAGE_SIZE = 15;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<Role> NOT_SUSPENDABLE_FROM_BOARD = Set.of(Role.PLATFORM_ARCHITECT, Role.SYSTEM_MODERATOR);

    private final CommunityPostRepository postRepository;
    private final PostReactionRepository reactionRepository;
    private final PostReplyRepository replyRepository;
    private final PostReportRepository reportRepository;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final LeaderboardService leaderboardService;
    private final RefreshTokenService refreshTokenService;
    private final NamedParameterJdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public CommunityService(
            CommunityPostRepository postRepository,
            PostReactionRepository reactionRepository,
            PostReplyRepository replyRepository,
            PostReportRepository reportRepository,
            AppUserRepository userRepository,
            CredentialRepository credentialRepository,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            @Lazy LeaderboardService leaderboardService,
            RefreshTokenService refreshTokenService,
            NamedParameterJdbcTemplate jdbc) {
        this.postRepository = postRepository;
        this.reactionRepository = reactionRepository;
        this.replyRepository = replyRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.idGeneratorService = idGeneratorService;
        this.auditService = auditService;
        this.leaderboardService = leaderboardService;
        this.refreshTokenService = refreshTokenService;
        this.jdbc = jdbc;
    }

    @Transactional
    public void createAutoPost(String authorId, String text) {
        CommunityPost post = CommunityPost.builder()
                .id("post-" + idGeneratorService.nextValue("post"))
                .authorId(authorId)
                .autoPost(true)
                .textBody(text.length() > POST_MAX ? text.substring(0, POST_MAX) : text)
                .hidden(false)
                .createdAt(Instant.now())
                .build();
        postRepository.save(post);
        auditService.recordSystem("AUTO_ANNOUNCEMENT", AuditTargetType.COMMUNITY, post.getId(), clip(text, 80));
    }

    @Transactional
    public Map<String, Object> createPost(UserPrincipal principal, String textRaw) {
        requireCommunity(principal);
        String text = textRaw == null ? "" : textRaw.strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("Write something before publishing.");
        }
        if (text.length() > POST_MAX) {
            throw ApiException.unprocessable("Posts are limited to " + POST_MAX + " characters.");
        }
        String id = "post-" + idGeneratorService.nextValue("post");
        postRepository.save(CommunityPost.builder()
                .id(id)
                .authorId(principal.getId())
                .autoPost(false)
                .textBody(text)
                .hidden(false)
                .createdAt(Instant.now())
                .build());
        auditService.record(principal, "POST_CREATED", AuditTargetType.COMMUNITY, id, clip(text, 60));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("post", postView(principal, id));
        return body;
    }

    public record FeedQuery(String view, String authorId, Integer page, Integer size) {}

    @Transactional(readOnly = true)
    public Map<String, Object> feed(UserPrincipal principal, FeedQuery q) {
        requireCommunity(principal);
        boolean canModerate = canModerate(principal);
        boolean reportedOnly = canModerate && "reported".equals(q.view());
        int size = q.size() == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, q.size()));
        int page = q.page() == null ? 0 : Math.max(0, q.page());

        StringBuilder where = new StringBuilder(" where 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource("me", principal.getId());
        if (!canModerate) {
            where.append(" and not p.hidden");
        }
        if (q.authorId() != null && !q.authorId().isBlank()) {
            where.append(" and p.author_id = :author");
            p.addValue("author", q.authorId());
        }
        if (reportedOnly) {
            where.append(" and exists (select 1 from post_report r where r.post_id = p.id)");
        }

        long total = count("select count(*) from community_post p" + where, p);
        List<Map<String, Object>> items = total > (long) page * size
                ? posts(principal, canModerate, where.toString(), p, size, (long) page * size)
                : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", total);
        body.put("hasMore", (long) (page + 1) * size < total);
        body.put("canModerate", canModerate);
        if (canModerate) {
            body.put("reportedCount", count(
                    "select count(distinct post_id) from post_report", new MapSqlParameterSource()));
        }
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(UserPrincipal principal, String userId) {
        requireCommunity(principal);
        AppUser user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Author not found."));
        boolean self = principal.getId().equals(user.getId());
        boolean privileged = self || principal.getRole() == Role.PLATFORM_ARCHITECT
                || principal.getRole() == Role.SYSTEM_MODERATOR;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("name", user.getName());
        m.put("username", user.getUsername());
        m.put("role", user.getRole().name());
        m.put("joinedAt", user.getJoinedAt() != null ? user.getJoinedAt().toString() : null);
        m.put("institution", user.getInstitution());
        m.put("occupation", user.getOccupation());
        m.put("suspended", user.isSuspended());
        m.put("active", user.isActive());
        m.put("isSelf", self);
        if (privileged) {
            m.put("email", user.getEmail());
            m.put("dob", user.getDob() != null ? user.getDob().toString() : null);
        }
        if (self) {
            m.put("autoAnnounce", user.isAutoAnnounce());
        }
        MapSqlParameterSource p = new MapSqlParameterSource("id", user.getId());
        m.put("postCount", count(canModerate(principal)
                ? "select count(*) from community_post where author_id = :id"
                : "select count(*) from community_post where author_id = :id and not hidden", p));
        m.put("replyCount", count("select count(*) from post_reply where author_id = :id", p));
        Map<String, Object> standing = null;
        if (LeaderboardService.isRanked(user.getRole())) {
            standing = leaderboardService.leaderboardFor(user.getRole()).stream()
                    .filter(r -> user.getId().equals(r.get("userId")))
                    .findFirst()
                    .orElse(null);
        }
        m.put("standing", standing);
        return m;
    }

    @Transactional
    public Map<String, Object> action(UserPrincipal principal, String postId, Map<String, Object> p) {
        requireCommunity(principal);
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> ApiException.notFound("This post no longer exists."));
        String action = String.valueOf(p.getOrDefault("action", ""));
        boolean canModerate = canModerate(principal);
        if (post.isHidden() && !canModerate) {
            throw ApiException.notFound("This post no longer exists.");
        }

        switch (action) {
            case "react" -> {
                ReactionType type;
                try {
                    type = ReactionType.valueOf(String.valueOf(p.get("type")));
                } catch (IllegalArgumentException e) {
                    throw ApiException.unprocessable("Unknown reaction type.");
                }
                if (post.isHidden()) {
                    throw ApiException.conflict("Hidden posts cannot receive reactions.");
                }
                var existing = reactionRepository.findByPostIdAndUserId(postId, principal.getId());
                if (existing.isPresent() && existing.get().getReaction() == type) {
                    reactionRepository.delete(existing.get());
                } else if (existing.isPresent()) {
                    existing.get().setReaction(type);
                    reactionRepository.save(existing.get());
                } else {
                    reactionRepository.save(PostReaction.builder()
                            .postId(postId)
                            .userId(principal.getId())
                            .reaction(type)
                            .createdAt(Instant.now())
                            .build());
                }
            }
            case "reply" -> {
                if (post.isHidden()) {
                    throw ApiException.conflict("Hidden posts cannot receive replies.");
                }
                String text = String.valueOf(p.getOrDefault("text", "")).strip();
                if (text.isEmpty()) {
                    throw ApiException.unprocessable("Write a reply before sending.");
                }
                if (text.length() > REPLY_MAX) {
                    throw ApiException.unprocessable("Replies are limited to " + REPLY_MAX + " characters.");
                }
                replyRepository.save(PostReply.builder()
                        .id("reply-" + idGeneratorService.nextValue("reply"))
                        .postId(postId)
                        .authorId(principal.getId())
                        .textBody(text)
                        .createdAt(Instant.now())
                        .build());
            }
            case "report" -> {
                if (post.getAuthorId().equals(principal.getId())) {
                    throw ApiException.unprocessable("You cannot report your own post.");
                }
                if (reportRepository.existsByPostIdAndReporterId(postId, principal.getId())) {
                    throw ApiException.conflict("You have already reported this post.");
                }
                String reason = p.get("reason") == null ? "" : String.valueOf(p.get("reason")).strip();
                if (reason.length() > REASON_MAX) {
                    throw ApiException.unprocessable("Keep the reason under " + REASON_MAX + " characters.");
                }
                reportRepository.save(PostReport.builder()
                        .postId(postId)
                        .reporterId(principal.getId())
                        .reason(reason.isEmpty() ? null : reason)
                        .reportedAt(Instant.now())
                        .build());
                auditService.record(principal, "POST_REPORTED", AuditTargetType.COMMUNITY, postId,
                        reason.isEmpty() ? "No reason given" : clip(reason, 60));
            }
            case "hide", "unhide" -> {
                requireModerator(canModerate);
                boolean hide = "hide".equals(action);
                if (post.isHidden() == hide) {
                    throw ApiException.conflict(hide ? "This post is already hidden." : "This post is already visible.");
                }
                post.setHidden(hide);
                postRepository.save(post);
                auditService.record(principal, hide ? "POST_HIDDEN" : "POST_UNHIDDEN",
                        AuditTargetType.COMMUNITY, postId, clip(post.getTextBody(), 60));
            }
            case "dismiss_reports" -> {
                requireModerator(canModerate);
                int removed = jdbc.update("delete from post_report where post_id = :id",
                        new MapSqlParameterSource("id", postId));
                if (removed == 0) {
                    throw ApiException.conflict("This post has no open reports.");
                }
                auditService.record(principal, "POST_REPORTS_DISMISSED", AuditTargetType.COMMUNITY, postId,
                        removed + " report" + (removed == 1 ? "" : "s") + " dismissed");
            }
            case "delete" -> {
                requireModerator(canModerate);
                postRepository.delete(post);
                auditService.record(principal, "POST_DELETED", AuditTargetType.COMMUNITY, postId,
                        clip(post.getTextBody(), 60));
                return Map.of("ok", true, "deleted", true);
            }
            case "warn" -> {
                requireModerator(canModerate);
                AppUser author = author(post);
                if (author.getId().equals(principal.getId())) {
                    throw ApiException.unprocessable("You cannot warn yourself.");
                }
                auditService.record(principal, "AUTHOR_WARNED", AuditTargetType.USER, author.getId(),
                        "Warning issued to " + author.getName() + " over community post " + postId);
            }
            case "suspend" -> {
                requireModerator(canModerate);
                AppUser author = author(post);
                if (author.getId().equals(principal.getId())) {
                    throw ApiException.unprocessable("You cannot suspend your own account.");
                }
                if (NOT_SUSPENDABLE_FROM_BOARD.contains(author.getRole())) {
                    throw ApiException.forbidden("Platform Architect and System Moderator accounts cannot be suspended from the community board.");
                }
                if (author.isSuspended()) {
                    throw ApiException.conflict(author.getName() + " is already suspended.");
                }
                author.setSuspended(true);
                userRepository.save(author);
                credentialRepository.findByUserId(author.getId()).ifPresent(c -> {
                    c.setDeactivated(true);
                    credentialRepository.save(c);
                });
                refreshTokenService.revokeAllForUser(author.getId());
                auditService.record(principal, "ACCOUNT_SUSPENDED", AuditTargetType.USER, author.getId(),
                        author.getName() + " suspended over community post " + postId
                                + "; awaiting Platform Architect review");
            }
            default -> throw ApiException.unprocessable("Unknown action.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("post", postView(principal, postId));
        return body;
    }

    // ---------------------------------------------------------------- reads

    private Map<String, Object> postView(UserPrincipal principal, String postId) {
        // JdbcTemplate reads bypass Hibernate's auto-flush; pending JPA writes must be visible here.
        entityManager.flush();
        boolean canModerate = canModerate(principal);
        MapSqlParameterSource p = new MapSqlParameterSource("me", principal.getId()).addValue("postId", postId);
        List<Map<String, Object>> rows = posts(principal, canModerate, " where p.id = :postId", p, 1, 0);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> posts(UserPrincipal principal, boolean canModerate, String where,
            MapSqlParameterSource p, int limit, long offset) {
        p.addValue("limit", limit).addValue("offset", offset);
        Map<String, String> tiers = authorTiers();
        List<Map<String, Object>> items = jdbc.query("""
                select p.id, p.author_id, p.auto_post, p.text_body, p.hidden, p.created_at,
                       u.name as author_name, u.role as author_role,
                       coalesce(rx.likes, 0) as likes, coalesce(rx.unlikes, 0) as unlikes,
                       coalesce(rx.celebrates, 0) as celebrates, rx.mine as my_reaction,
                       coalesce(rp.n, 0) as report_count, coalesce(rp.mine, false) as reported_by_me,
                       coalesce(rc.n, 0) as reply_count
                from community_post p
                join app_user u on u.id = p.author_id
                left join lateral (
                  select count(*) filter (where reaction = 'LIKE') as likes,
                         count(*) filter (where reaction = 'UNLIKE') as unlikes,
                         count(*) filter (where reaction = 'CELEBRATE') as celebrates,
                         max(reaction) filter (where user_id = :me) as mine
                  from post_reaction r where r.post_id = p.id) rx on true
                left join lateral (
                  select count(*) as n, bool_or(reporter_id = :me) as mine
                  from post_report r where r.post_id = p.id) rp on true
                left join lateral (select count(*) as n from post_reply r where r.post_id = p.id) rc on true
                """ + where + " order by p.created_at desc, p.id desc limit :limit offset :offset",
                p, (rs, i) -> postRow(rs, principal, tiers));
        if (items.isEmpty()) {
            return items;
        }
        List<String> ids = items.stream().map(m -> (String) m.get("id")).toList();
        Map<String, List<Map<String, Object>>> replies = new HashMap<>();
        jdbc.query("""
                select r.id, r.post_id, r.author_id, u.name as author_name, u.role as author_role,
                       r.text_body, r.created_at
                from post_reply r join app_user u on u.id = r.author_id
                where r.post_id in (:ids) order by r.created_at, r.id
                """, new MapSqlParameterSource("ids", ids), rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getString("id"));
            r.put("authorId", rs.getString("author_id"));
            r.put("authorName", rs.getString("author_name"));
            r.put("authorRole", rs.getString("author_role"));
            r.put("text", rs.getString("text_body"));
            r.put("createdAt", iso(rs.getTimestamp("created_at")));
            r.put("isMine", principal.getId().equals(rs.getString("author_id")));
            replies.computeIfAbsent(rs.getString("post_id"), k -> new ArrayList<>()).add(r);
        });
        Map<String, List<Map<String, Object>>> reports = new HashMap<>();
        if (canModerate) {
            jdbc.query("""
                    select r.post_id, r.reporter_id, u.name as reporter_name, r.reason, r.reported_at
                    from post_report r join app_user u on u.id = r.reporter_id
                    where r.post_id in (:ids) order by r.reported_at
                    """, new MapSqlParameterSource("ids", ids), rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("reporterId", rs.getString("reporter_id"));
                r.put("reporterName", rs.getString("reporter_name"));
                r.put("reason", rs.getString("reason"));
                r.put("reportedAt", iso(rs.getTimestamp("reported_at")));
                reports.computeIfAbsent(rs.getString("post_id"), k -> new ArrayList<>()).add(r);
            });
        }
        for (Map<String, Object> item : items) {
            String id = (String) item.get("id");
            item.put("replies", replies.getOrDefault(id, List.of()));
            if (canModerate) {
                item.put("reports", reports.getOrDefault(id, List.of()));
            }
        }
        return items;
    }

    private Map<String, Object> postRow(ResultSet rs, UserPrincipal viewer, Map<String, String> tiers)
            throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        String authorId = rs.getString("author_id");
        m.put("id", rs.getString("id"));
        m.put("text", rs.getString("text_body"));
        m.put("auto", rs.getBoolean("auto_post"));
        m.put("createdAt", iso(rs.getTimestamp("created_at")));
        m.put("hidden", rs.getBoolean("hidden"));
        m.put("authorId", authorId);
        m.put("authorName", rs.getString("author_name"));
        m.put("authorRole", rs.getString("author_role"));
        m.put("authorTier", tiers.get(authorId));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("LIKE", rs.getInt("likes"));
        counts.put("UNLIKE", rs.getInt("unlikes"));
        counts.put("CELEBRATE", rs.getInt("celebrates"));
        m.put("reactionCounts", counts);
        m.put("myReaction", rs.getString("my_reaction"));
        m.put("replyCount", rs.getInt("reply_count"));
        m.put("reportCount", rs.getInt("report_count"));
        m.put("reportedByMe", rs.getBoolean("reported_by_me"));
        m.put("isMine", viewer.getId().equals(authorId));
        return m;
    }

    /** Leaderboard tier per ranked author (one aggregate query). */
    private Map<String, String> authorTiers() {
        Map<String, String> tiers = new HashMap<>();
        leaderboardService.rewardPointsByRole().forEach((role, points) -> {
            points.forEach((userId, pts) -> tiers.put(userId, leaderboardService.tierForPoints(pts).name()));
        });
        jdbc.query("select id from app_user where role in ('CONTRIBUTOR', 'LEXICAL_EDITOR', 'CHIEF_LEXICAL_EDITOR')",
                new MapSqlParameterSource(), rs -> {
                    tiers.putIfAbsent(rs.getString("id"), leaderboardService.tierForPoints(0).name());
                });
        return tiers;
    }

    // -------------------------------------------------------------- helpers

    private AppUser author(CommunityPost post) {
        return userRepository.findById(post.getAuthorId())
                .orElseThrow(() -> ApiException.notFound("The author of this post no longer exists."));
    }

    private static boolean canModerate(UserPrincipal principal) {
        return PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY_MODERATE);
    }

    private static void requireCommunity(UserPrincipal principal) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY)) {
            throw ApiException.forbidden("You do not have access to the community board.");
        }
    }

    private static void requireModerator(boolean canModerate) {
        if (!canModerate) {
            throw ApiException.forbidden("Only the System Moderator can moderate posts.");
        }
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String iso(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }
}
