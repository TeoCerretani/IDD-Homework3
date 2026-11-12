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
        assertEquals(15, count);

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
        assertEquals(List.of("esplorazione-spaziale-marte"), executeAndGetNames("nome marte"));
    }

    @Test
    void testQuery02_NomePhraseQuerySpazi() throws Exception {
        assertEquals(List.of("letteratura italiana novecento"), executeAndGetNames("nome \"letteratura italiana\""));
    }

    @Test
    void testQuery03_NomePhraseQueryTrattini() throws Exception {
        assertEquals(List.of("basi-di-dati-relazionali"), executeAndGetNames("nome \"basi di dati\""));
    }

    @Test
    void testQuery04_NomeCamelCaseEUnderscore() throws Exception {
        assertEquals(List.of("RetiNeurali_DeepLearning"), executeAndGetNames("nome neurali deep"));
    }

    @Test
    void testQuery05_ContenutoPhraseQuery() throws Exception {
        assertEquals(List.of("indice_invertito_lucene"), executeAndGetNames("contenuto \"indice invertito\""));
    }

    @Test
    void testQuery06_ContenutoPhraseQueryStemming() throws Exception {
        assertEquals(List.of("sistemi_operativi_kernel"), executeAndGetNames("contenuto \"sistema operativo\""));
    }

    @Test
    void testQuery07_ContenutoSingoloTermine() throws Exception {
        assertEquals(List.of("CrittografiaModerna"), executeAndGetNames("contenuto crittografia"));
    }

    @Test
    void testQuery08_ContenutoStemmingPluraleSingolare() throws Exception {
        assertEquals(List.of("energia_rinnovabile_italia"), executeAndGetNames("contenuto energie rinnovabili"));
    }

    @Test
    void testQuery09_ContenutoTerminiObbligatori() throws Exception {
        assertEquals(List.of("machine-learning-supervisionato"), executeAndGetNames("contenuto +apprendimento +modello"));
    }

    @Test
    void testQuery10_ContenutoTerminiObbligatoriArchitettura() throws Exception {
        assertEquals(List.of("architettura_calcolatori"), executeAndGetNames("contenuto +architettura +memoria"));
    }

    @Test
    void testQuery11_ContenutoRankingMultiDocumento() throws Exception {
        List<String> results = executeAndGetNames("contenuto architettura dati");
        assertTrue(results.size() >= 2);
        assertTrue(results.subList(0, 2).contains("IngegneriaDeiDati_Pipeline"));
        assertTrue(results.subList(0, 2).contains("architettura_calcolatori"));
    }

    @Test
    void testQuery12_ContenutoSoloStopword() throws Exception {
        assertTrue(executeAndGetNames("contenuto il dello nella").isEmpty());
    }
}
