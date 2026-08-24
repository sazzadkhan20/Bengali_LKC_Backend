package com.bengalilkc.bootstrap;

import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.entity.lexicon.WordnetIli;
import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import com.bengalilkc.repository.lexicon.LexicalEntryRepository;
import com.bengalilkc.repository.lexicon.SenseRepository;
import com.bengalilkc.repository.lexicon.SynsetRepository;
import com.bengalilkc.repository.lexicon.WordnetIliRepository;
import com.bengalilkc.service.lexicon.LexiconImportService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final LkcProperties properties;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final LexiconImportService lexiconImportService;
    private final LexicalEntryRepository entryRepository;
    private final SynsetRepository synsetRepository;
    private final SenseRepository senseRepository;
    private final WordnetIliRepository wordnetIliRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (properties.getBootstrap().isSeedDemoUsers() && userRepository.count() == 0) {
            seedUsers();
            log.info("Demo users and credentials seeded");
        }
        if (properties.getBootstrap().isSeedDemoUsers()
                && entryRepository.count() == 0
                && !properties.getBootstrap().isImportLexiconXml()) {
            seedSampleLexicon();
            log.info("Sample lexicon rows seeded (enable IMPORT_LEXICON_XML for full corpus)");
        }
        if (properties.getBootstrap().isImportLexiconXml()) {
            Path path = Path.of(properties.getBootstrap().getLexiconXmlPath());
            if (!path.isAbsolute()) {
                path = Path.of(System.getProperty("user.dir")).resolve(path);
            }
            var stats = lexiconImportService.importFromXml(path);
            log.info("Lexicon XML import finished: {}", stats);
        }
    }

    private void seedSampleLexicon() {
        SynsetEntity syn = SynsetEntity.builder()
                .id("ben-syn1")
                .lexiconId("livelanguage-ben")
                .ili("00017222-n")
                .partOfSpeech(PartOfSpeech.N)
                .lexicalized(true)
                .definitionLanguage(DefinitionLanguage.BEN)
                .definitionText("পঠনযোগ্য মুদ্রিত বা লিখিত বিষয়সম্বলিত বস্তু")
                .crowdsourced(false)
                .build();
        synsetRepository.save(syn);

        LexicalEntryEntity entry = LexicalEntryEntity.builder()
                .id("ben-word-n1")
                .lexiconId("livelanguage-ben")
                .writtenForm("বই")
                .partOfSpeech(PartOfSpeech.N)
                .crowdsourced(false)
                .build();
        entryRepository.save(entry);

        SenseEntity sense = SenseEntity.builder()
                .id("ben-sen1")
                .lexicalEntry(entry)
                .synset(syn)
                .build();
        senseRepository.save(sense);

        if (wordnetIliRepository.count() == 0) {
            wordnetIliRepository.save(WordnetIli.builder()
                    .ili("00017222-n")
                    .pos(PartOfSpeech.N)
                    .wordsCsv("book,volume")
                    .gloss("a written work or composition that has been published")
                    .build());
            wordnetIliRepository.save(WordnetIli.builder()
                    .ili("01234567-n")
                    .pos(PartOfSpeech.N)
                    .wordsCsv("demo,sample")
                    .gloss("demo ILI unused in Bengali lexicon yet")
                    .build());
        }
    }

    private void seedUsers() {
        record Seed(
                String id,
                String username,
                String name,
                Role role,
                String email,
                String dob,
                String institution,
                String occupation,
                String password,
                boolean oneTime,
                boolean suspended,
                boolean deactivated) {}

        List<Seed> seeds = List.of(
                new Seed("u-arch", "architect", "Dr. Anisur Rahman", Role.PLATFORM_ARCHITECT, "architect@lkc.ac.bd",
                        "1975-03-14", "University of Dhaka", "University Teacher", "architect2026", false, false, false),
                new Seed("u-mod", "moderator", "Farhana Akter", Role.SYSTEM_MODERATOR, "moderator@lkc.ac.bd",
                        "1984-11-02", "Bangla Academy", "Research Officer", "moderator2026", false, false, false),
                new Seed("u-con1", "contributor", "Saiful Islam", Role.CONTRIBUTOR, "saiful@lkc.ac.bd",
                        "1996-07-21", "Jahangirnagar University", "Graduate Student", "contributor2026", false, false, false),
                new Seed("u-con2", "mita.c", "Mita Chakraborty", Role.CONTRIBUTOR, "mita@lkc.ac.bd",
                        "1992-01-30", "Rajshahi College", "College Teacher", "welcome-mita-2026", true, false, false),
                new Seed("u-con3", "rafi.c", "Rafiul Hasan", Role.CONTRIBUTOR, "rafi@lkc.ac.bd",
                        "1999-09-05", "BUET", "Software Engineer", "rafi2026", false, false, false),
                new Seed("u-con4", "nusrat.c", "Nusrat Jahan", Role.CONTRIBUTOR, "nusrat@lkc.ac.bd",
                        "1998-05-17", "Chittagong University", "Student", "nusrat2026", false, true, true),
                new Seed("u-le1", "editor", "Prof. Kamal Uddin", Role.LEXICAL_EDITOR, "editor@lkc.ac.bd",
                        "1968-12-09", "University of Dhaka", "University Teacher", "editor2026", false, false, false),
                new Seed("u-le2", "shilpi.e", "Shilpi Banerjee", Role.LEXICAL_EDITOR, "shilpi@lkc.ac.bd",
                        "1980-04-25", "Jadavpur University", "Linguist", "shilpi2026", false, false, false),
                new Seed("u-cle", "chief", "Prof. Rokeya Sultana", Role.CHIEF_LEXICAL_EDITOR, "chief@lkc.ac.bd",
                        "1963-08-19", "Bangla Academy", "Lexicographer", "chief2026", false, false, false),
                new Seed("u-ac1", "curator", "Dr. Tanvir Ahmed", Role.ARBITRATION_CURATOR, "tanvir@lkc.ac.bd",
                        "1972-02-11", "Rajshahi University", "University Teacher", "curator2026", false, false, false),
                new Seed("u-ac2", "lipi.a", "Dr. Lipi Ghosh", Role.ARBITRATION_CURATOR, "lipi@lkc.ac.bd",
                        "1976-10-03", "University of Calcutta", "Researcher", "lipi2026", false, false, false),
                new Seed("u-ac3", "joy.a", "Dr. Joydeep Sen", Role.ARBITRATION_CURATOR, "joy@lkc.ac.bd",
                        "1979-06-28", "Visva-Bharati University", "University Teacher", "joy2026", false, false, false)
        );

        int credSeq = 1;
        for (Seed s : seeds) {
            AppUser user = AppUser.builder()
                    .id(s.id())
                    .username(s.username())
                    .name(s.name())
                    .role(s.role())
                    .email(s.email())
                    .active(true)
                    .suspended(s.suspended())
                    .autoAnnounce(true)
                    .dob(LocalDate.parse(s.dob()))
                    .institution(s.institution())
                    .occupation(s.occupation())
                    .joinedAt(Instant.parse("2026-01-05T08:00:00Z"))
                    .build();
            userRepository.save(user);
            Credential cred = Credential.builder()
                    .id("cred-" + credSeq++)
                    .userId(s.id())
                    .username(s.username())
                    .passwordHash(passwordEncoder.encode(s.password()))
                    .oneTime(s.oneTime())
                    .issuedBy("u-arch")
                    .issuedAt(Instant.parse("2026-01-05T08:00:00Z"))
                    .lastChangedAt(s.oneTime() ? null : Instant.parse("2026-01-05T09:00:00Z"))
                    .deactivated(s.deactivated())
                    .build();
            credentialRepository.save(cred);
        }
    }
}
