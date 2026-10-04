package com.bengalilkc.service.community;

import com.bengalilkc.domain.entity.admin.SystemConfig;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.workflow.StateTransition;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.LeaderboardTier;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.workflow.StateTransitionRepository;
import com.bengalilkc.repository.workflow.SubmissionRepository;
import com.bengalilkc.security.UserPrincipal;
import com.bengalilkc.service.admin.ConfigService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaderboardService {

    private static final List<Integer> VALIDATED_MILESTONES = List.of(5, 10, 25, 50, 100, 250, 500);
    private static final List<Role> RANKED = List.of(Role.CONTRIBUTOR, Role.LEXICAL_EDITOR, Role.CHIEF_LEXICAL_EDITOR);
    private static final Map<LeaderboardTier, String> TIER_NAME = Map.of(
            LeaderboardTier.GOLD, "Gold",
            LeaderboardTier.SILVER, "Silver",
            LeaderboardTier.BRONZE, "Bronze",
            LeaderboardTier.PROVISIONAL, "Provisional");

    private final SubmissionRepository submissionRepository;
    private final StateTransitionRepository transitionRepository;
    private final AppUserRepository userRepository;
    private final ConfigService configService;
    private final CommunityService communityService;
    private final NamedParameterJdbcTemplate jdbc;

    public LeaderboardService(
            SubmissionRepository submissionRepository,
            StateTransitionRepository transitionRepository,
            AppUserRepository userRepository,
            ConfigService configService,
            @Lazy CommunityService communityService,
            NamedParameterJdbcTemplate jdbc) {
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.userRepository = userRepository;
        this.configService = configService;
        this.communityService = communityService;
        this.jdbc = jdbc;
    }

    /**
     * Validated, reward-eligible entries credited per ranked author. Credit follows the final
     * validation: a CLE validation credits the contributor, that CLE and the Lexical Editor whose
     * approval preceded it; a binding panel verdict (actor SYSTEM) credits the contributor only.
     * Credits only count while the author still holds the credited role.
     */
    private static final String CREDITS_SQL = """
            with last_validation as (
              select distinct on (t.submission_id) t.submission_id, t.actor_id, t.actor_role, s.contributor_id
              from state_transition t join submission s on s.id = t.submission_id
              where s.state = 'VALIDATED' and s.reward_eligible and t.to_state = 'VALIDATED'
              order by t.submission_id, t.created_at desc, t.id desc
            ), last_approval as (
              select distinct on (t.submission_id) t.submission_id, t.actor_id
              from state_transition t join last_validation v on v.submission_id = t.submission_id
              where t.to_state = 'EDITOR_APPROVED' and v.actor_role = 'CHIEF_LEXICAL_EDITOR'
              order by t.submission_id, t.created_at desc, t.id desc
            ), credits as (
              select 'CONTRIBUTOR' as role, contributor_id as user_id from last_validation
                where actor_role in ('CHIEF_LEXICAL_EDITOR', 'SYSTEM')
              union all
              select 'CHIEF_LEXICAL_EDITOR', actor_id from last_validation where actor_role = 'CHIEF_LEXICAL_EDITOR'
              union all
              select 'LEXICAL_EDITOR', actor_id from last_approval
            )
            select c.role, c.user_id, count(*) as entries
            from credits c join app_user u on u.id = c.user_id and u.role = c.role
            group by c.role, c.user_id
            """;

    @Transactional(readOnly = true)
    public Map<Role, Map<String, Integer>> rewardPointsByRole() {
        int pv = configService.getConfig().getRewardPointPerEntry();
        Map<Role, Map<String, Integer>> board = new EnumMap<>(Role.class);
        for (Role r : RANKED) {
            board.put(r, new HashMap<>());
        }
        jdbc.query(CREDITS_SQL, new MapSqlParameterSource(), rs -> {
            board.get(Role.valueOf(rs.getString("role"))).put(rs.getString("user_id"), rs.getInt("entries") * pv);
        });
        return board;
    }

    public static boolean isRanked(Role role) {
        return RANKED.contains(role);
    }

    /**
     * Leaderboard view for the caller: ranked authors only ever see their own role's board;
     * managers and curators may browse all three.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> boardFor(UserPrincipal principal, String requestedRole) {
        Role role;
        if (isRanked(principal.getRole())) {
            role = principal.getRole();
        } else {
            try {
                role = requestedRole == null ? Role.CONTRIBUTOR : Role.valueOf(requestedRole);
            } catch (IllegalArgumentException e) {
                role = Role.CONTRIBUTOR;
            }
            if (!isRanked(role)) {
                role = Role.CONTRIBUTOR;
            }
        }
        List<Map<String, Object>> rows = leaderboardFor(role);
        SystemConfig c = configService.getConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", role.name());
        body.put("rows", rows);
        body.put("me", rows.stream().filter(r -> principal.getId().equals(r.get("userId"))).findFirst().orElse(null));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("rewardPointPerEntry", c.getRewardPointPerEntry());
        config.put("tierGoldMin", c.getTierGoldMin());
        config.put("tierSilverMin", c.getTierSilverMin());
        config.put("tierBronzeMin", c.getTierBronzeMin());
        body.put("config", config);
        return body;
    }

    public LeaderboardTier tierForPoints(int points) {
        SystemConfig c = configService.getConfig();
        if (points >= c.getTierGoldMin()) {
            return LeaderboardTier.GOLD;
        }
        if (points >= c.getTierSilverMin()) {
            return LeaderboardTier.SILVER;
        }
        if (points >= c.getTierBronzeMin()) {
            return LeaderboardTier.BRONZE;
        }
        return LeaderboardTier.PROVISIONAL;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboardFor(Role role) {
        Map<String, Integer> points = rewardPointsByRole().getOrDefault(role, Map.of());
        SystemConfig c = configService.getConfig();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppUser u : userRepository.findByRoleAndActiveTrue(role)) {
            int p = points.getOrDefault(u.getId(), 0);
            Map<String, Object> next = nextTierOf(p, c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", 0);
            row.put("userId", u.getId());
            row.put("name", u.getName());
            row.put("username", u.getUsername());
            row.put("points", p);
            row.put("validatedEntries", c.getRewardPointPerEntry() > 0 ? p / c.getRewardPointPerEntry() : 0);
            row.put("tier", tierForPoints(p).name());
            row.put("toNextTier", next != null ? next.get("min") instanceof Integer min ? min - p : null : null);
            row.put("nextTier", next != null ? next.get("tier") : null);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int cmp = Integer.compare((int) b.get("points"), (int) a.get("points"));
            if (cmp != 0) {
                return cmp;
            }
            return String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });
        int lastPoints = Integer.MIN_VALUE;
        int lastRank = 0;
        for (int i = 0; i < rows.size(); i++) {
            int p = (int) rows.get(i).get("points");
            if (p != lastPoints) {
                lastRank = i + 1;
                lastPoints = p;
            }
            rows.get(i).put("rank", lastRank);
        }
        return rows;
    }

    private Map<String, Object> nextTierOf(int points, SystemConfig c) {
        if (points < c.getTierBronzeMin()) {
            return Map.of("tier", LeaderboardTier.BRONZE.name(), "min", c.getTierBronzeMin());
        }
        if (points < c.getTierSilverMin()) {
            return Map.of("tier", LeaderboardTier.SILVER.name(), "min", c.getTierSilverMin());
        }
        if (points < c.getTierGoldMin()) {
            return Map.of("tier", LeaderboardTier.GOLD.name(), "min", c.getTierGoldMin());
        }
        return null;
    }

    @Transactional
    public void announceValidationMilestones(Submission sub) {
        List<StateTransition> history = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId());
        StateTransition validation = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getToState() == EntryState.VALIDATED) {
                validation = history.get(i);
                break;
            }
        }
        if (validation == null) {
            return;
        }
        boolean viaCle = Role.CHIEF_LEXICAL_EDITOR.name().equals(validation.getActorRole());
        boolean viaArbitration = "SYSTEM".equals(validation.getActorRole());
        if (!viaCle && !viaArbitration) {
            return;
        }

        Map<Role, Map<String, Integer>> board = rewardPointsByRole();
        int pv = configService.getConfig().getRewardPointPerEntry();

        AppUser contributor = userRepository.findById(sub.getContributorId()).orElse(null);
        if (contributor != null && contributor.getRole() == Role.CONTRIBUTOR && contributor.isAutoAnnounce()) {
            long validatedCount = submissionRepository.countByContributorIdAndState(contributor.getId(), EntryState.VALIDATED);
            if (VALIDATED_MILESTONES.contains((int) validatedCount)) {
                communityService.createAutoPost(
                        contributor.getId(),
                        "🏅 Milestone: " + contributor.getName() + " has reached " + validatedCount
                                + " validated entries in the Bengali LKC. Congratulations!");
            }
        }
        announceTierChange(board, Role.CONTRIBUTOR, sub.getContributorId(), pv);
        if (viaCle) {
            announceTierChange(board, Role.CHIEF_LEXICAL_EDITOR, validation.getActorId(), pv);
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).getToState() == EntryState.EDITOR_APPROVED) {
                    announceTierChange(board, Role.LEXICAL_EDITOR, history.get(i).getActorId(), pv);
                    break;
                }
            }
        }
    }

    private void announceTierChange(Map<Role, Map<String, Integer>> board, Role role, String userId, int pv) {
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRole() != role || !user.isAutoAnnounce()) {
            return;
        }
        int points = board.getOrDefault(role, Map.of()).getOrDefault(userId, 0);
        if (points <= 0) {
            return;
        }
        LeaderboardTier before = tierForPoints(Math.max(points - pv, 0));
        LeaderboardTier after = tierForPoints(points);
        if (after != before) {
            String roleLabel = role == Role.CONTRIBUTOR
                    ? "Contributor"
                    : role == Role.LEXICAL_EDITOR ? "Lexical Editor" : "Chief Lexical Editor";
            communityService.createAutoPost(
                    userId,
                    "🎖 Tier change: " + user.getName() + " has advanced to the " + TIER_NAME.get(after)
                            + " tier on the " + roleLabel + " leaderboard. Congratulations!");
        }
    }
}
