package com.bengalilkc.bootstrap;

import com.bengalilkc.service.lexicon.WordnetRegistryService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the Princeton WordNet 3.0 ILI registry shipped in {@code classpath:wordnet/} into
 * {@code wordnet_ili} / {@code wordnet_lemma}. Runs on every start but only writes when the database
 * does not already hold this exact registry; the reload is one transaction, so readers keep seeing the
 * previous rows until it commits. Lexicon data, users and workflow tables are never touched.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WordnetRegistryLoader implements ApplicationRunner {

    private static final String BASE = "wordnet/";
    private static final int CHUNK = 64 * 1024;

    private final DataSource dataSource;
    private final WordnetRegistryService registryService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            load();
        } catch (Exception e) {
            // The app still starts: ILI Finder and ILI checks report the registry as unavailable.
            log.error("WordNet 3.0 registry could not be loaded: {}", e.getMessage(), e);
        } finally {
            registryService.invalidate();
        }
    }

    private void load() throws Exception {
        ClassPathResource metaFile = new ClassPathResource(BASE + "wn30-registry.properties");
        if (!metaFile.exists()) {
            log.error("WordNet registry resources are missing from the classpath ({}).", BASE);
            return;
        }
        Properties meta = new Properties();
        try (InputStream in = metaFile.getInputStream()) {
            meta.load(in);
        }
        String version = meta.getProperty("version");
        int synsetCount = Integer.parseInt(meta.getProperty("synsetCount"));
        int lemmaRowCount = Integer.parseInt(meta.getProperty("lemmaRowCount"));
        int lemmaCount = Integer.parseInt(meta.getProperty("lemmaCount"));

        try (Connection conn = dataSource.getConnection()) {
            if (isCurrent(conn, version, synsetCount, lemmaRowCount)) {
                log.info("WordNet {} ILI registry ready ({} synsets, {} lemmas)", version, synsetCount, lemmaCount);
                return;
            }
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    // Only one instance loads; others wait here and then find the registry current.
                    st.execute("select pg_advisory_xact_lock(hashtext('lkc-wordnet-registry'))");
                }
                if (isCurrent(conn, version, synsetCount, lemmaRowCount)) {
                    conn.commit();
                    return;
                }
                long started = System.currentTimeMillis();
                log.info("Loading Princeton WordNet {} ILI registry ({} synsets)…", version, synsetCount);
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("delete from wordnet_lemma");
                    st.executeUpdate("delete from wordnet_ili");
                    st.executeUpdate("delete from wordnet_registry");
                }
                CopyManager copy = conn.unwrap(PGConnection.class).getCopyAPI();
                long synsets = copyRows(copy, "COPY wordnet_ili (ili, pos, words_csv, gloss) FROM STDIN",
                        BASE + "wn30-synsets.tsv.gz", 4, true);
                long lemmas = copyRows(copy, "COPY wordnet_lemma (lemma, pos, ili, sense_rank) FROM STDIN",
                        BASE + "wn30-lemmas.tsv.gz", 4, false);
                if (synsets != synsetCount || lemmas != lemmaRowCount) {
                    throw new IllegalStateException("WordNet registry files are inconsistent: " + synsets
                            + " synsets / " + lemmas + " lemma rows, expected " + synsetCount + " / " + lemmaRowCount);
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        insert into wordnet_registry (id, version, synset_count, lemma_row_count, lemma_count)
                        values (1, ?, ?, ?, ?)
                        """)) {
                    ps.setString(1, version);
                    ps.setInt(2, synsetCount);
                    ps.setInt(3, lemmaRowCount);
                    ps.setInt(4, lemmaCount);
                    ps.executeUpdate();
                }
                conn.commit();
                log.info("WordNet {} ILI registry loaded: {} synsets, {} lemma rows in {} ms",
                        version, synsets, lemmas, System.currentTimeMillis() - started);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private static boolean isCurrent(Connection conn, String version, int synsetCount, int lemmaRowCount)
            throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("""
                        select r.version, r.synset_count, r.lemma_row_count,
                               (select count(*) from wordnet_ili) as ili_rows
                        from wordnet_registry r where r.id = 1
                        """)) {
            return rs.next()
                    && version.equals(rs.getString("version"))
                    && rs.getInt("synset_count") == synsetCount
                    && rs.getInt("lemma_row_count") == lemmaRowCount
                    && rs.getLong("ili_rows") == synsetCount;
        }
    }

    /**
     * Streams a gzip TSV resource into COPY (text format). {@code synsetFile}: the words column uses
     * " | " in the file and is stored comma-separated.
     */
    private static long copyRows(CopyManager copy, String sql, String resource, int columns, boolean synsetFile)
            throws SQLException, IOException {
        CopyIn in = copy.copyIn(sql);
        long rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ClassPathResource(resource).getInputStream(), CHUNK), StandardCharsets.UTF_8))) {
            StringBuilder buf = new StringBuilder(CHUNK + 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length != columns) {
                    throw new IllegalStateException("Malformed line " + (rows + 1) + " in " + resource);
                }
                for (int i = 0; i < columns; i++) {
                    if (i > 0) {
                        buf.append('\t');
                    }
                    String value = synsetFile && i == 2 ? f[i].replace(" | ", ", ") : f[i];
                    appendEscaped(buf, value);
                }
                buf.append('\n');
                rows++;
                if (buf.length() >= CHUNK) {
                    flush(in, buf);
                }
            }
            flush(in, buf);
            in.endCopy();
        } catch (SQLException | IOException | RuntimeException e) {
            if (in.isActive()) {
                in.cancelCopy();
            }
            throw e;
        }
        return rows;
    }

    private static void flush(CopyIn in, StringBuilder buf) throws SQLException {
        if (buf.isEmpty()) {
            return;
        }
        byte[] bytes = buf.toString().getBytes(StandardCharsets.UTF_8);
        in.writeToCopy(bytes, 0, bytes.length);
        buf.setLength(0);
    }

    /** COPY text-format escaping. */
    private static void appendEscaped(StringBuilder buf, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> buf.append("\\\\");
                case '\t' -> buf.append("\\t");
                case '\n' -> buf.append("\\n");
                case '\r' -> buf.append("\\r");
                default -> buf.append(c);
            }
        }
    }
}
