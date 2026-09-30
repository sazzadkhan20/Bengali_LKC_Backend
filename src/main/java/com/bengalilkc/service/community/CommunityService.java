package com.bengalilkc.service.community;

import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.community.CommunityPost;
import com.bengalilkc.domain.entity.community.PostReaction;
import com.bengalilkc.domain.entity.community.PostReply;
import com.bengalilkc.domain.entity.community.PostReport;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {

    private final CommunityPostRepository postRepository;
    private final PostReactionRepository reactionRepository;
    private final PostReplyRepository replyRepository;
    private final PostReportRepository reportRepository;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final IdGeneratorService idGeneratorService;
    private final AuditService auditService;
    private final LeaderboardService leaderboardService;

    public CommunityService(
            CommunityPostRepository postRepository,
            PostReactionRepository reactionRepository,
            PostReplyRepository replyRepository,
            PostReportRepository reportRepository,
            AppUserRepository userRepository,
            CredentialRepository credentialRepository,
            IdGeneratorService idGeneratorService,
            AuditService auditService,
            @Lazy LeaderboardService leaderboardService) {
        this.postRepository = postRepository;
        this.reactionRepository = reactionRepository;
        this.replyRepository = replyRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.idGeneratorService = idGeneratorService;
        this.auditService = auditService;
        this.leaderboardService = leaderboardService;
    }

    @Transactional
    public void createAutoPost(String authorId, String text) {
        CommunityPost post = CommunityPost.builder()
                .id("post-" + idGeneratorService.nextValue("post"))
                .authorId(authorId)
                .autoPost(true)
                .textBody(text.length() > 500 ? text.substring(0, 500) : text)
                .hidden(false)
                .createdAt(Instant.now())
                .build();
        postRepository.save(post);
        auditService.recordSystem("AUTO_ANNOUNCEMENT", AuditTargetType.COMMUNITY, post.getId(), text.substring(0, Math.min(80, text.length())));
    }

    @Transactional
    public Map<String, Object> createPost(UserPrincipal principal, String textRaw) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY)) {
            throw ApiException.forbidden("Not authorised.");
        }
        String text = textRaw == null ? "" : textRaw.trim();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("Post text is required.");
        }
        if (text.length() > 500) {
            throw ApiException.unprocessable("Posts are limited to 500 characters.");
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
        auditService.record(principal, "POST_CREATED", AuditTargetType.COMMUNITY, id, text.substring(0, Math.min(60, text.length())));
        return Map.of("ok", true, "id", id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> feed(UserPrincipal principal) {
        boolean canModerate = PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY_MODERATE);
        List<CommunityPost> posts = canModerate
                ? postRepository.findAllByOrderByCreatedAtDesc()
                : postRepository.findByHiddenFalseOrderByCreatedAtDesc();
        return posts.stream().map(p -> toView(p, principal, canModerate)).toList();
    }

    @Transactional
    public Map<String, Object> action(UserPrincipal principal, String postId, Map<String, Object> p) {
        if (!PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY)) {
            throw ApiException.forbidden("Not authorised.");
        }
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> ApiException.notFound("Post not found."));
        String action = String.valueOf(p.getOrDefault("action", ""));
        boolean canModerate = PermissionMatrix.has(principal.getRole(), Permission.COMMUNITY_MODERATE);

        return switch (action) {
            case "react" -> {
                ReactionType type;
                try {
                    type = ReactionType.valueOf(String.valueOf(p.get("type")));
                } catch (Exception e) {
                    throw ApiException.unprocessable("Unknown reaction type.");
                }
                if (post.isHidden()) {
                    throw ApiException.unprocessable("This post is hidden.");
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
                yield Map.of("ok", true);
            }
            case "reply" -> {
                if (post.isHidden()) {
                    throw ApiException.unprocessable("This post is hidden.");
                }
                String text = String.valueOf(p.getOrDefault("text", "")).trim();
                if (text.isEmpty()) {
                    throw ApiException.unprocessable("Reply text is required.");
                }
                if (text.length() > 200) {
                    throw ApiException.unprocessable("Replies are limited to 200 characters.");
                }
                replyRepository.save(PostReply.builder()
                        .id("reply-" + idGeneratorService.nextValue("reply"))
                        .postId(postId)
                        .authorId(principal.getId())
                        .textBody(text)
                        .createdAt(Instant.now())
                        .build());
                yield Map.of("ok", true);
            }
            case "report" -> {
                if (post.getAuthorId().equals(principal.getId())) {
                    throw ApiException.unprocessable("You cannot report your own post.");
                }
                if (reportRepository.existsByPostIdAndReporterId(postId, principal.getId())) {
                    throw ApiException.unprocessable("You have already reported this post.");
                }
                String reason = String.valueOf(p.getOrDefault("reason", "")).trim();
                reportRepository.save(PostReport.builder()
                        .postId(postId)
                        .reporterId(principal.getId())
                        .reason(reason.isEmpty() ? null : reason)
                        .reportedAt(Instant.now())
                        .build());
                auditService.record(principal, "POST_REPORTED", AuditTargetType.COMMUNITY, postId,
                        reason.isEmpty() ? "No reason given" : reason.substring(0, Math.min(60, reason.length())));
                yield Map.of("ok", true);
            }
            case "hide", "unhide" -> {
                if (!canModerate) {
                    throw ApiException.forbidden("Only the System Moderator may moderate posts.");
                }
                post.setHidden("hide".equals(action));
                postRepository.save(post);
                auditService.record(principal, "hide".equals(action) ? "POST_HIDDEN" : "POST_UNHIDDEN",
                        AuditTargetType.COMMUNITY, postId, post.getTextBody().substring(0, Math.min(60, post.getTextBody().length())));
                yield Map.of("ok", true);
            }
            case "delete" -> {
                if (!canModerate) {
                    throw ApiException.forbidden("Only the System Moderator may moderate posts.");
                }
                reactionRepository.findByPostId(postId).forEach(reactionRepository::delete);
                replyRepository.findByPostIdOrderByCreatedAtAsc(postId).forEach(replyRepository::delete);
                reportRepository.findByPostId(postId).forEach(reportRepository::delete);
                postRepository.delete(post);
                auditService.record(principal, "POST_DELETED", AuditTargetType.COMMUNITY, postId,
                        post.getTextBody().substring(0, Math.min(60, post.getTextBody().length())));
                yield Map.of("ok", true);
            }
            case "warn" -> {
                if (!canModerate) {
                    throw ApiException.forbidden("Only the System Moderator may moderate posts.");
                }
                AppUser author = userRepository.findById(post.getAuthorId()).orElse(null);
                auditService.record(principal, "AUTHOR_WARNED", AuditTargetType.COMMUNITY, postId,
                        "Warning issued to " + (author != null ? author.getName() : post.getAuthorId())
                                + " over post " + postId);
                yield Map.of("ok", true);
            }
            case "suspend" -> {
                if (!canModerate) {
                    throw ApiException.forbidden("Only the System Moderator may moderate posts.");
                }
                AppUser author = userRepository.findById(post.getAuthorId())
                        .orElseThrow(() -> ApiException.notFound("Author not found."));
                author.setSuspended(true);
                userRepository.save(author);
                credentialRepository.findByUserId(author.getId()).ifPresent(c -> {
                    c.setDeactivated(true);
                    credentialRepository.save(c);
                });
                auditService.record(principal, "ACCOUNT_SUSPENDED", AuditTargetType.USER, author.getId(),
                        author.getName() + " suspended over community post " + postId);
                yield Map.of("ok", true);
            }
            default -> throw ApiException.unprocessable("Unknown action.");
        };
    }

    private Map<String, Object> toView(CommunityPost post, UserPrincipal viewer, boolean canModerate) {
        AppUser author = userRepository.findById(post.getAuthorId()).orElse(null);
        Map<ReactionType, Integer> counts = new EnumMap<>(ReactionType.class);
        for (ReactionType t : ReactionType.values()) {
            counts.put(t, 0);
        }
        ReactionType my = null;
        for (PostReaction r : reactionRepository.findByPostId(post.getId())) {
            counts.merge(r.getReaction(), 1, Integer::sum);
            if (r.getUserId().equals(viewer.getId())) {
                my = r.getReaction();
            }
        }
        List<PostReport> reports = reportRepository.findByPostId(post.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", post.getId());
        m.put("text", post.getTextBody());
        m.put("auto", post.isAutoPost());
        m.put("createdAt", post.getCreatedAt() != null ? post.getCreatedAt().toString() : null);
        m.put("hidden", post.isHidden());
        m.put("authorId", post.getAuthorId());
        m.put("authorName", author != null ? author.getName() : post.getAuthorId());
        m.put("authorRoleLabel", author != null ? author.getRole().name() : "Unknown");
        m.put("reactionCounts", Map.of(
                "LIKE", counts.get(ReactionType.LIKE),
                "UNLIKE", counts.get(ReactionType.UNLIKE),
                "CELEBRATE", counts.get(ReactionType.CELEBRATE)));
        m.put("myReaction", my != null ? my.name() : null);
        List<Map<String, Object>> replies = new ArrayList<>();
        for (PostReply r : replyRepository.findByPostIdOrderByCreatedAtAsc(post.getId())) {
            AppUser ra = userRepository.findById(r.getAuthorId()).orElse(null);
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.getId());
            rm.put("authorId", r.getAuthorId());
            rm.put("authorName", ra != null ? ra.getName() : r.getAuthorId());
            rm.put("text", r.getTextBody());
            rm.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            replies.add(rm);
        }
        m.put("replies", replies);
        m.put("reportCount", reports.size());
        m.put("reportedByMe", reports.stream().anyMatch(r -> r.getReporterId().equals(viewer.getId())));
        m.put("isMine", post.getAuthorId().equals(viewer.getId()));
        if (canModerate) {
            m.put("reports", reports.stream().map(r -> {
                AppUser reporter = userRepository.findById(r.getReporterId()).orElse(null);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("reporterName", reporter != null ? reporter.getName() : r.getReporterId());
                rm.put("reason", r.getReason());
                rm.put("reportedAt", r.getReportedAt() != null ? r.getReportedAt().toString() : null);
                return rm;
            }).toList());
        }
        return m;
    }
}
