package it.uniroma3.idd.hw3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.simpletext.SimpleTextCodec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test JUnit 5 per la verifica dell'indicizzazione sui 600 documenti del corpus,
 * delle 12 query di test, dei tempi di indicizzazione e delle metriche di valutazione
 * (Precision, Recall, F1, AP, MAP).
 */
class LuceneSearchTest {

    @TempDir
    static Path tempIndexDir;

    private static Directory directory;
    private static IndexReader reader;
    private static IndexSearcher searcher;
    private static Analyzer perFieldAnalyzer;

    private record QueryCase(String id, String queryStr, Set<String> groundTruth) {}

    @BeforeAll
    static void setUp() throws Exception {
        int count = FileIndexer.indexDocs("corpus", tempIndexDir.toString());
        assertEquals(600, count);

        directory = FSDirectory.open(tempIndexDir);
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        perFieldAnalyzer = FileIndexer.getAnalyzer();
    }

    @AfterAll
    static void tearDown() throws Exception {
        reader.close();
        directory.close();
    }

    private List<String> executeAndGetNames(String queryString) throws Exception {
        System.out.println("\nInput: " + queryString);
        Query query = ConsoleSearcher.parseQuery(queryString, perFieldAnalyzer);
        TopDocs hits = ConsoleSearcher.runQuery(searcher, query);
        StoredFields storedFields = searcher.storedFields();

        List<String> results = new ArrayList<>();
        for (int i = 0; i < hits.scoreDocs.length; i++) {
            ScoreDoc scoreDoc = hits.scoreDocs[i];
            Document doc = storedFields.document(scoreDoc.doc);
            results.add(doc.get(FileIndexer.CAMPO_NOME));
        }
        return results;
    }

    @Test
    void testQuery01_NomeTermineSingolo() throws Exception {
        List<String> results = executeAndGetNames("nome marte");
        assertEquals(3, results.size());
        assertTrue(results.containsAll(List.of(
                "esplorazione-spaziale-marte",
                "missione_rover_marte",
                "atmosfera-marziana-marte")));
    }

    @Test
    void testQuery02_NomePhraseQuerySpazi() throws Exception {
        List<String> results = executeAndGetNames("nome \"letteratura italiana\"");
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of(
                "letteratura italiana novecento",
                "letteratura-italiana-ottocento")));
    }

    @Test
    void testQuery03_NomePhraseQueryTrattini() throws Exception {
        List<String> results = executeAndGetNames("nome \"basi di dati\"");
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of(
                "basi-di-dati-relazionali",
                "basi_di_dati_distribuite")));
    }

    @Test
    void testQuery04_NomeCamelCaseEUnderscore() throws Exception {
        List<String> results = executeAndGetNames("nome neurali deep");
        assertEquals(3, results.size());
        assertEquals("RetiNeurali_DeepLearning", results.get(0));
        assertTrue(results.containsAll(List.of(
                "RetiNeurali_DeepLearning",
                "ModelliNeurali_VisioneArtificiale",
                "DeepLearning_Trasformatori")));
    }

    @Test
    void testQuery05_ContenutoPhraseQuery() throws Exception {
        List<String> results = executeAndGetNames("contenuto \"indice invertito\"");
        assertEquals(2, results.size());
        assertEquals("indice_invertito_lucene", results.get(0));
        assertTrue(results.contains("compressione_liste_invertite"));
    }

    @Test
    void testQuery06_ContenutoPhraseQueryStemming() throws Exception {
        List<String> results = executeAndGetNames("contenuto \"sistema operativo\"");
        assertEquals(3, results.size());
        assertTrue(results.containsAll(List.of(
                "sistemi_operativi_kernel",
                "gestione_processi_scheduler",
                "memoria_virtuale_paginazione")));
    }

    @Test
    void testQuery07_ContenutoSingoloTermine() throws Exception {
        List<String> results = executeAndGetNames("contenuto crittografia");
        assertEquals(2, results.size());
        assertEquals("CrittografiaModerna", results.get(0));
        assertTrue(results.containsAll(List.of(
                "CrittografiaModerna",
                "cifrari_simmetrici_aes")));
    }

    @Test
    void testQuery08_ContenutoStemmingPluraleSingolare() throws Exception {
        List<String> results = executeAndGetNames("contenuto energie rinnovabili");
        assertEquals(5, results.size());
        assertEquals("energia_rinnovabile_italia", results.get(0));
        assertTrue(results.containsAll(List.of(
                "energia_rinnovabile_italia",
                "impianti_fotovoltaici_solari",
                "parchi_eolici_offshore",
                "rientro_atmosferico_scudo_termico_ablativo",
                "propulsione_ionica_sonde")));
    }

    @Test
    void testQuery09_ContenutoTerminiObbligatori() throws Exception {
        List<String> results = executeAndGetNames("contenuto +apprendimento +modello");
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of(
                "machine-learning-supervisionato",
                "regressione_lineare_logistica")));
    }

    @Test
    void testQuery10_ContenutoTerminiObbligatoriArchitettura() throws Exception {
        List<String> results = executeAndGetNames("contenuto +architettura +memoria");
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of(
                "architettura_calcolatori",
                "gerarchia_cache_processore")));
    }

    @Test
    void testQuery11_ContenutoRankingMultiDocumento() throws Exception {
        List<String> results = executeAndGetNames("contenuto architettura dati");
        assertEquals(10, results.size());
        assertTrue(results.contains("data_lake_cloud_storage"));
        assertTrue(results.contains("data_warehouse_olap"));
        assertTrue(results.contains("IngegneriaDeiDati_Pipeline"));
    }

    @Test
    void testQuery12_ContenutoSoloStopword() throws Exception {
        assertTrue(executeAndGetNames("contenuto il dello nella").isEmpty());
    }

    /**
     * Valutazione dei tempi di indicizzazione al crescere di N (DefaultCodec vs SimpleTextCodec)
     * e confronto delle metriche di Information Retrieval (Precision, Recall, F1, AP, MAP).
     */
    @Test
    void testIndexingTimesAndConfigurationsComparison(@TempDir Path benchDir) throws Exception {
        File[] allFiles = new File("corpus").listFiles((d, name) -> name.toLowerCase().endsWith(".txt"));
        assertNotNull(allFiles);
        Arrays.sort(allFiles, Comparator.comparing(File::getName));
        assertEquals(600, allFiles.length);

        System.out.println("\n=== TEMPI DI INDICIZZAZIONE (DefaultCodec vs SimpleTextCodec) ===");
        // Warm-up JVM JIT e classloading dei Codec
        measureIndexingTime(allFiles, 50, benchDir.resolve("warmup_def"), false);
        measureIndexingTime(allFiles, 50, benchDir.resolve("warmup_st"), true);

        int[] sizes = {50, 100, 200, 400, 600};
        for (int n : sizes) {
            long timeDefault = measureIndexingTime(allFiles, n, benchDir.resolve("idx_def_" + n), false);
            long timeSimpleText = measureIndexingTime(allFiles, n, benchDir.resolve("idx_st_" + n), true);
            System.out.printf(Locale.US, "N=%3d docs -> DefaultCodec: %3d ms | SimpleTextCodec: %3d ms%n",
                    n, timeDefault, timeSimpleText);
        }

        List<QueryCase> benchmark = List.of(
                new QueryCase("Q1", "nome marte", Set.of(
                        "atmosfera-marziana-marte", "esplorazione-spaziale-marte",
                        "missione_rover_marte", "pianeta_rosso_esobiologia")),
                new QueryCase("Q2", "nome \"letteratura italiana\"", Set.of(
                        "letteratura italiana novecento", "letteratura-italiana-ottocento",
                        "romanzo_storico_italiano")),
                new QueryCase("Q3", "nome \"basi di dati\"", Set.of(
                        "basi-di-dati-relazionali", "basi_di_dati_distribuite",
                        "database_relazionali_sql")),
                new QueryCase("Q4", "nome neurali deep", Set.of(
                        "RetiNeurali_DeepLearning", "DeepLearning_Trasformatori",
                        "ModelliNeurali_VisioneArtificiale")),
                new QueryCase("Q5", "contenuto \"indice invertito\"", Set.of(
                        "indice_invertito_lucene", "compressione_liste_invertite",
                        "struttura_dizionario_termini")),
                new QueryCase("Q6", "contenuto \"sistema operativo\"", Set.of(
                        "sistemi_operativi_kernel", "gestione_processi_scheduler",
                        "memoria_virtuale_paginazione", "file_system_journaling")),
                new QueryCase("Q7", "contenuto crittografia", Set.of(
                        "CrittografiaModerna", "cifrari_simmetrici_aes",
                        "firma_digitale_rsa", "scambio_chiavi_diffie_hellman")),
                new QueryCase("Q8", "contenuto energie rinnovabili", Set.of(
                        "energia_rinnovabile_italia", "parchi_eolici_offshore",
                        "impianti_fotovoltaici_solari", "centrali_idroelettriche_alpine")),
                new QueryCase("Q9", "contenuto +apprendimento +modello", Set.of(
                        "regressione_lineare_logistica", "machine-learning-supervisionato",
                        "alberi_decisione_random_forest", "clustering_non_supervisionato")),
                new QueryCase("Q10", "contenuto +architettura +memoria", Set.of(
                        "architettura_calcolatori", "gerarchia_cache_processore",
                        "pipeline_istruzioni_risc", "coerenza_cache_protocollo_mesi_multiprocessore")),
                new QueryCase("Q11", "contenuto architettura dati", Set.of(
                        "data_lake_cloud_storage", "data_warehouse_olap",
                        "IngegneriaDeiDati_Pipeline", "basi-di-dati-relazionali",
                        "basi_di_dati_distribuite", "federazione_sorgenti_wrapper_mediatori",
                        "indice_invertito_lucene", "catalogo_metadati_data_governance"))
        );

        System.out.println("\n=== CONFRONTO CONFIGURAZIONI (ANALYZER E SIMILARITY) ===");
        evaluateConfig("A. PerField(Custom+Italian) + BM25Similarity",
                FileIndexer.getAnalyzer(), new BM25Similarity(), allFiles, benchDir.resolve("ablation_A"), benchmark, true);
        evaluateConfig("B. PerField(Custom+Italian) + ClassicSimilarity (TF-IDF)",
                FileIndexer.getAnalyzer(), new ClassicSimilarity(), allFiles, benchDir.resolve("ablation_B"), benchmark, true);
        evaluateConfig("C. StandardAnalyzer (entrambi i campi) + BM25Similarity",
                new StandardAnalyzer(), new BM25Similarity(), allFiles, benchDir.resolve("ablation_C"), benchmark, false);
        evaluateConfig("D. WhitespaceAnalyzer (entrambi i campi) + BM25Similarity",
                new WhitespaceAnalyzer(), new BM25Similarity(), allFiles, benchDir.resolve("ablation_D"), benchmark, false);
    }

    private static long measureIndexingTime(File[] allFiles, int limit, Path idxPath, boolean useSimpleText) throws IOException {
        long start = System.currentTimeMillis();
        Directory dir = FSDirectory.open(idxPath);
        IndexWriterConfig config = new IndexWriterConfig(FileIndexer.getAnalyzer());
        if (useSimpleText) {
            config.setCodec(new SimpleTextCodec());
        }
        IndexWriter writer = new IndexWriter(dir, config);
        writer.deleteAll();
        for (int i = 0; i < limit; i++) {
            File file = allFiles[i];
            String nomeFile = file.getName().substring(0, file.getName().length() - 4);
            String contenutoFile = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Document doc = new Document();
            doc.add(new TextField(FileIndexer.CAMPO_NOME, nomeFile, Field.Store.YES));
            doc.add(new TextField(FileIndexer.CAMPO_CONTENUTO, contenutoFile, Field.Store.NO));
            writer.addDocument(doc);
        }
        writer.commit();
        writer.close();
        dir.close();
        return System.currentTimeMillis() - start;
    }

    private static void evaluateConfig(String label, Analyzer analyzer, Similarity similarity,
            File[] allFiles, Path idxPath, List<QueryCase> cases, boolean printPerQuery) throws Exception {
        Directory dir = FSDirectory.open(idxPath);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(similarity);
        IndexWriter writer = new IndexWriter(dir, config);
        writer.deleteAll();
        for (File file : allFiles) {
            String nomeFile = file.getName().substring(0, file.getName().length() - 4);
            String contenutoFile = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Document doc = new Document();
            doc.add(new TextField(FileIndexer.CAMPO_NOME, nomeFile, Field.Store.YES));
            doc.add(new TextField(FileIndexer.CAMPO_CONTENUTO, contenutoFile, Field.Store.NO));
            writer.addDocument(doc);
        }
        writer.commit();
        writer.close();

        IndexReader r = DirectoryReader.open(dir);
        IndexSearcher s = new IndexSearcher(r);
        s.setSimilarity(similarity);
        StoredFields sf = s.storedFields();

        double sumP = 0, sumR10 = 0, sumRTot = 0, sumF1 = 0, sumAP = 0;
        int count = cases.size();

        for (QueryCase qc : cases) {
            String id = qc.id();
            String qStr = qc.queryStr();
            Set<String> rel = qc.groundTruth();

            Query q = ConsoleSearcher.parseQuery(qStr, analyzer);
            TopDocs top10 = s.search(q, 10);
            TopDocs topAll = s.search(q, 600);

            int tp10 = 0;
            double apSum = 0.0;
            for (int i = 0; i < top10.scoreDocs.length; i++) {
                String docName = sf.document(top10.scoreDocs[i].doc).get(FileIndexer.CAMPO_NOME);
                if (rel.contains(docName)) {
                    tp10++;
                    apSum += (double) tp10 / (i + 1);
                }
            }
            int tpAll = 0;
            for (int i = 0; i < topAll.scoreDocs.length; i++) {
                String docName = sf.document(topAll.scoreDocs[i].doc).get(FileIndexer.CAMPO_NOME);
                if (rel.contains(docName)) {
                    tpAll++;
                }
            }

            double p = top10.scoreDocs.length == 0 ? 0.0 : (double) tp10 / top10.scoreDocs.length;
            double r10 = (double) tp10 / rel.size();
            double rAll = (double) tpAll / rel.size();
            double f1 = (p + r10 == 0) ? 0.0 : (2 * p * r10) / (p + r10);
            double ap = apSum / rel.size();

            sumP += p;
            sumR10 += r10;
            sumRTot += rAll;
            sumF1 += f1;
            sumAP += ap;

            if (printPerQuery) {
                System.out.printf(Locale.US,
                        "  %s | |R|=%d | |A|=%2d | |A_tot|=%2d | TP=%d | TP_tot=%d | P@10=%.3f | R@10=%.3f | R_tot=%.3f | F1=%.3f | AP=%.3f%n",
                        id, rel.size(), top10.scoreDocs.length, topAll.scoreDocs.length, tp10, tpAll, p, r10, rAll, f1, ap);
                if (id.equals("Q11")) {
                    for (int i = 0; i < topAll.scoreDocs.length; i++) {
                        String dn = sf.document(topAll.scoreDocs[i].doc).get(FileIndexer.CAMPO_NOME);
                        System.out.printf(Locale.US, "     [Q11 rank %2d] %s (score: %.4f)%n", i + 1, dn, topAll.scoreDocs[i].score);
                    }
                }
            }
        }

        System.out.printf(Locale.US,
                "Config [%s] -> Macro-P@10=%.3f | Macro-R@10=%.3f | Macro-R_tot=%.3f | Macro-F1=%.3f | MAP=%.3f%n",
                label, sumP / count, sumR10 / count, sumRTot / count, sumF1 / count, sumAP / count);

        r.close();
        dir.close();
    }
}
