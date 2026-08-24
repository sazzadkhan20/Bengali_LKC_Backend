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
import com.bengalilkc.service.admin.ConfigService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
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

    public LeaderboardService(
            SubmissionRepository submissionRepository,
            StateTransitionRepository transitionRepository,
            AppUserRepository userRepository,
            ConfigService configService,
            @Lazy CommunityService communityService) {
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.userRepository = userRepository;
        this.configService = configService;
        this.communityService = communityService;
    }

    @Transactional(readOnly = true)
    public Map<Role, Map<String, Integer>> rewardPointsByRole() {
        SystemConfig config = configService.getConfig();
        int pv = config.getRewardPointPerEntry();
        Map<Role, Map<String, Integer>> board = new EnumMap<>(Role.class);
        for (Role r : RANKED) {
            board.put(r, new HashMap<>());
        }
        for (Submission sub : submissionRepository.findByState(EntryState.VALIDATED)) {
            if (!sub.isRewardEligible()) {
                continue;
            }
            List<StateTransition> history = transitionRepository.findBySubmissionIdOrderByCreatedAtAsc(sub.getId());
            StateTransition validation = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).getToState() == EntryState.VALIDATED) {
                    validation = history.get(i);
                    break;
                }
            }
            if (validation == null) {
                continue;
            }
            boolean viaCle = Role.CHIEF_LEXICAL_EDITOR.name().equals(validation.getActorRole());
            boolean viaArbitration = "SYSTEM".equals(validation.getActorRole());
            if (!viaCle && !viaArbitration) {
                continue;
            }
            credit(board, Role.CONTRIBUTOR, sub.getContributorId(), pv);
            if (viaCle) {
                credit(board, Role.CHIEF_LEXICAL_EDITOR, validation.getActorId(), pv);
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (history.get(i).getToState() == EntryState.EDITOR_APPROVED) {
                        credit(board, Role.LEXICAL_EDITOR, history.get(i).getActorId(), pv);
                        break;
                    }
                }
            }
        }
        return board;
    }

    private void credit(Map<Role, Map<String, Integer>> board, Role role, String userId, int pv) {
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRole() != role) {
            return;
        }
        board.get(role).merge(userId, pv, Integer::sum);
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
