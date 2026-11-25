package com.bengalilkc.service.workflow;

import com.bengalilkc.common.BengaliText;
import com.bengalilkc.common.SenseRelationPayload;
import com.bengalilkc.common.SynsetRelationPayload;
import com.bengalilkc.common.exception.ApiException;
import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.AuditTargetType;
import com.bengalilkc.domain.enums.SynsetAction;
import com.bengalilkc.service.admin.AuditService;
import com.bengalilkc.service.admin.IdGeneratorService;
import com.bengalilkc.service.lexicon.LexiconStatsService;
import com.bengalilkc.service.lexicon.SynsetDefinitionService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Copies a validated submission into the lexicon tables (origin {@code USER}, linked back to the
 * submission) so it can be linked, related, searched and exported like imported XML data, and
 * removes it again if the submission leaves VALIDATED.
 */
@Service
@RequiredArgsConstructor
public class LexiconPromotionService {

    private static final String DEFAULT_LEXICON = "livelanguage-ben";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdGeneratorService ids;
    private final EntryValidator validator;
    private final AuditService auditService;
    private final LexiconStatsService statsService;
    private final SynsetDefinitionService definitionService;

    public void promote(Submission sub, String actorId, String actorRole) {
        if (isPromoted(sub.getId())) {
            return;
        }
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);
        String key = BengaliText.key(sub.getWrittenForm());
        String pos = sub.getPartOfSpeech().getCode();
        String label = "“" + sub.getWrittenForm() + "”";
        String lexiconId = jdbc.queryForList("select id from lexicon order by id limit 1", Map.of(), String.class)
                .stream().findFirst().orElse(DEFAULT_LEXICON);

        String synsetId;
        boolean synsetCreated = false;
        if (sub.getSynsetAction() == SynsetAction.LINK) {
            synsetId = sub.getSynsetId();
            List<Map<String, Object>> syn = jdbc.queryForList(
                    "select id from synset where id = :id", Map.of("id", synsetId == null ? "" : synsetId));
            if (syn.isEmpty()) {
                throw ApiException.conflict("Cannot validate " + label + ": its linked synset " + synsetId
                        + " is no longer in the lexicon. Flag the entry so the contributor can relink it.");
            }
            // New definitions only for languages the synset does not have yet; existing ones stay as they are.
            if (definitionService.insert(synsetId, definitionService.read(sub.getDefinitionsJson()), sub.getId()) > 0) {
                jdbc.update("update synset set updated_at = :ts where id = :id",
                        new MapSqlParameterSource("ts", ts).addValue("id", synsetId));
            }
        } else {
            String ili = sub.getIliCode() == null || EntryValidator.isNa(sub.getIliCode()) ? "n/a" : sub.getIliCode().toLowerCase();
            if (!"n/a".equals(ili)) {
                List<String> holder = jdbc.queryForList(
                        "select id from synset where lower(ili) = :ili", Map.of("ili", ili), String.class);
                if (!holder.isEmpty()) {
                    throw ApiException.conflict("Cannot validate " + label + ": ILI " + ili
                            + " is already registered for synset " + holder.get(0)
                            + ". Flag the entry so the contributor links to that synset instead.");
                }
            }
            synsetId = nextId("synset", "ben-syn", "synset");
            jdbc.update("""
                    insert into synset (id, lexicon_id, ili, part_of_speech, lexicalized, crowdsourced,
                                        created_at, updated_at, origin, source_submission_id, promoted_at)
                    values (:id, :lex, :ili, :pos, true, true, :ts, :ts, 'USER', :sub, :ts)
                    """, new MapSqlParameterSource("id", synsetId).addValue("lex", lexiconId).addValue("ili", ili)
                    .addValue("pos", pos)
                    .addValue("ts", ts).addValue("sub", sub.getId()));
            definitionService.insert(synsetId, definitionService.read(sub.getDefinitionsJson()), sub.getId());
            synsetCreated = true;
        }

        String entryId = jdbc.queryForList("""
                select id from lexical_entry where form_key = :key and part_of_speech = :pos order by id limit 1
                """, new MapSqlParameterSource("key", key).addValue("pos", pos), String.class)
                .stream().findFirst().orElse(null);
        if (entryId == null) {
            entryId = nextId("word", "ben-word-" + pos, "lexical_entry");
            jdbc.update("""
                    insert into lexical_entry (id, lexicon_id, written_form, part_of_speech, crowdsourced,
                                               created_at, updated_at, origin, source_submission_id, promoted_at)
                    values (:id, :lex, :form, :pos, true, :ts, :ts, 'USER', :sub, :ts)
                    """, new MapSqlParameterSource("id", entryId).addValue("lex", lexiconId)
                    .addValue("form", sub.getWrittenForm()).addValue("pos", pos)
                    .addValue("ts", ts).addValue("sub", sub.getId()));
        } else {
            Integer existing = jdbc.queryForObject(
                    "select count(*) from sense where lexical_entry_id = :e and synset_id = :s",
                    new MapSqlParameterSource("e", entryId).addValue("s", synsetId), Integer.class);
            if (existing != null && existing > 0) {
                throw ApiException.conflict("Cannot validate " + label + ": the lexicon already has this word in synset "
                        + synsetId + ".");
            }
        }

        String senseId = nextId("sense", "ben-sen", "sense");
        jdbc.update("""
                insert into sense (id, lexical_entry_id, synset_id, created_at, origin, source_submission_id, promoted_at)
                values (:id, :entry, :synset, :ts, 'USER', :sub, :ts)
                """, new MapSqlParameterSource("id", senseId).addValue("entry", entryId).addValue("synset", synsetId)
                .addValue("ts", ts).addValue("sub", sub.getId()));

        for (SenseRelationPayload rel : validator.readSense(sub.getSenseRelationsJson())) {
            jdbc.update("""
                    insert into sense_relation (sense_id_1, sense_id_2, relation_type_id, source_submission_id)
                    select :src, t.id, :type, :sub from sense t where t.id = :target
                    on conflict do nothing
                    """, new MapSqlParameterSource("src", senseId).addValue("target", rel.target())
                    .addValue("type", relationTypeId("sense_relation_type", rel.relType()))
                    .addValue("sub", sub.getId()));
        }
        for (SynsetRelationPayload rel : validator.readSynset(sub.getSynsetRelationsJson())) {
            jdbc.update("""
                    insert into synset_relation (source_synset_id, target_synset_id, relation_type_id, source_submission_id)
                    select :src, t.id, :type, :sub from synset t where t.id = :target and t.id <> :src
                    on conflict do nothing
                    """, new MapSqlParameterSource("src", synsetId).addValue("target", rel.target())
                    .addValue("type", relationTypeId("synset_relation_type", rel.relType()))
                    .addValue("sub", sub.getId()));
        }

        jdbc.update("""
                update submission set promoted_entry_id = :entry, promoted_sense_id = :sense,
                       promoted_synset_id = :synset, promoted_at = :ts
                where id = :id
                """, new MapSqlParameterSource("entry", entryId).addValue("sense", senseId)
                .addValue("synset", synsetCreated ? synsetId : null).addValue("ts", ts).addValue("id", sub.getId()));
        auditService.record(actorId, actorRole, "LEXICON_PROMOTED", AuditTargetType.SUBMISSION, sub.getId(),
                label + " added to the lexicon as sense " + senseId + " in synset " + synsetId
                        + (synsetCreated ? " (new synset)" : ""));
        refreshStatsAfterCommit();
    }

    /** Removes the promoted rows; refuses when other lexicon data already depends on them. */
    public void demote(Submission sub, String actorId, String actorRole) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select promoted_entry_id, promoted_sense_id, promoted_synset_id from submission where id = :id
                """, Map.of("id", sub.getId()));
        if (rows.isEmpty() || rows.get(0).get("promoted_sense_id") == null) {
            return;
        }
        String senseId = (String) rows.get(0).get("promoted_sense_id");
        String synsetId = (String) rows.get(0).get("promoted_synset_id");
        String entryId = (String) rows.get(0).get("promoted_entry_id");
        MapSqlParameterSource p = new MapSqlParameterSource("sub", sub.getId()).addValue("sense", senseId)
                .addValue("synset", synsetId == null ? "" : synsetId);

        Integer senseUsers = jdbc.queryForObject("""
                select count(*) from sense_relation
                where sense_id_2 = :sense and coalesce(source_submission_id, '') <> :sub
                """, p, Integer.class);
        Integer synsetUsers = synsetId == null ? 0 : jdbc.queryForObject("""
                select (select count(*) from sense where synset_id = :synset and id <> :sense)
                     + (select count(*) from synset_relation
                        where target_synset_id = :synset and coalesce(source_submission_id, '') <> :sub)
                """, p, Integer.class);
        if ((senseUsers != null && senseUsers > 0) || (synsetUsers != null && synsetUsers > 0)) {
            throw ApiException.conflict("This entry is already used by other lexicon data (relations or synonyms), "
                    + "so it cannot leave Validated.");
        }

        jdbc.update("delete from synset_relation where source_submission_id = :sub", p);
        definitionService.deleteAddedBy(sub.getId());
        jdbc.update("delete from sense_relation where source_submission_id = :sub", p);
        jdbc.update("delete from sense where id = :sense", p);
        if (synsetId != null) {
            jdbc.update("delete from synset where id = :synset and source_submission_id = :sub", p);
        }
        if (entryId != null) {
            jdbc.update("""
                    delete from lexical_entry e where e.id = :entry and e.source_submission_id = :sub
                      and not exists (select 1 from sense s where s.lexical_entry_id = e.id)
                    """, p.addValue("entry", entryId));
        }
        jdbc.update("""
                update submission set promoted_entry_id = null, promoted_sense_id = null,
                       promoted_synset_id = null, promoted_at = null
                where id = :sub
                """, p);
        auditService.record(actorId, actorRole, "LEXICON_DEMOTED", AuditTargetType.SUBMISSION, sub.getId(),
                "“" + sub.getWrittenForm() + "” removed from the lexicon (sense " + senseId + ")");
        refreshStatsAfterCommit();
    }

    /** Public counts are cached; refresh them once the lexicon change is committed. */
    private void refreshStatsAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    statsService.invalidate();
                }
            });
        } else {
            statsService.invalidate();
        }
    }

    private boolean isPromoted(String submissionId) {
        return !jdbc.queryForList("select 1 from submission where id = :id and promoted_sense_id is not null",
                Map.of("id", submissionId)).isEmpty();
    }

    private String nextId(String sequence, String prefix, String table) {
        while (true) {
            String id = prefix + ids.nextValue(sequence);
            Integer taken = jdbc.queryForObject("select count(*) from " + table + " where id = :id",
                    Map.of("id", id), Integer.class);
            if (taken == null || taken == 0) {
                return id;
            }
        }
    }

    private long relationTypeId(String table, String name) {
        List<Long> found = jdbc.queryForList("select id from " + table + " where relation_name = :n",
                Map.of("n", name), Long.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("insert into " + table + " (relation_name) values (:n) returning id",
                Map.of("n", name), Long.class);
    }
}
