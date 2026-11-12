package it.uniroma3.idd.hw3;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test JUnit per verificare l'indicizzazione e le 12 query di test.
 */
class LuceneSearchTest {

    @TempDir
    static Path tempIndexDir;

    private static Directory directory;
    private static IndexReader reader;
    private static IndexSearcher searcher;
    private static Analyzer perFieldAnalyzer;

    @BeforeAll
    static void setUp() throws Exception {
        int count = FileIndexer.indexDocs("corpus", tempIndexDir.toString());
        assertEquals(80, count);

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
        assertEquals(4, results.size());
        assertEquals("energia_rinnovabile_italia", results.get(0));
        assertTrue(results.containsAll(List.of(
                "energia_rinnovabile_italia",
                "impianti_fotovoltaici_solari",
                "parchi_eolici_offshore",
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
        assertTrue(results.subList(0, 4).containsAll(List.of(
                "IngegneriaDeiDati_Pipeline",
                "data_warehouse_olap",
                "data_lake_cloud_storage",
                "architettura_calcolatori")));
    }

    @Test
    void testQuery12_ContenutoSoloStopword() throws Exception {
        assertTrue(executeAndGetNames("contenuto il dello nella").isEmpty());
    }
}
