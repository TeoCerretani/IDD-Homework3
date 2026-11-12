package it.uniroma3.idd.hw3;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Programma che legge una query da console, interroga l'indice Lucene e stampa il risultato.
 * Sintassi della query:
 *   nome <sequenza di termini o "frase tra virgolette">
 *   contenuto <sequenza di termini o "frase tra virgolette">
 */
public class ConsoleSearcher {

    /**
     * Analizza la stringa inserita dall'utente (che inizia con "nome" o "contenuto")
     * e restituisce l'oggetto Query tramite il QueryParser di Lucene (Slide 38 e 45).
     */
    public static Query parseQuery(String inputLine, Analyzer perFieldAnalyzer) throws ParseException {
        String[] parts = inputLine.trim().split("\\s+", 2);
        String campo = parts[0].toLowerCase();

        if (!campo.equals(FileIndexer.CAMPO_NOME) && !campo.equals(FileIndexer.CAMPO_CONTENUTO)) {
            throw new IllegalArgumentException("La query deve iniziare con 'nome' oppure 'contenuto'.");
        }
        if (parts.length < 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("Inserire i termini di ricerca dopo il nome del campo.");
        }

        QueryParser parser = new QueryParser(campo, perFieldAnalyzer);
        Query query = parser.parse(parts[1]);
        return query;
    }

    /**
     * Esegue la query sull'indice e stampa i documenti trovati (Slide 43, 44, 45).
     */
    public static TopDocs runQuery(IndexSearcher searcher, Query query) throws IOException {
        TopDocs hits = searcher.search(query, 10);
        StoredFields storedFields = searcher.storedFields();

        System.out.println("Query Lucene: " + query + " -> Risultati trovati: " + hits.scoreDocs.length);
        for (int i = 0; i < hits.scoreDocs.length; i++) {
            ScoreDoc scoreDoc = hits.scoreDocs[i];
            Document doc = storedFields.document(scoreDoc.doc);
            String nome = doc.get(FileIndexer.CAMPO_NOME);
            System.out.printf("  %d. %s.txt (score: %.4f)%n", i + 1, nome, scoreDoc.score);
        }
        return hits;
    }

    public static void main(String[] args) throws IOException {
        String indexPath = args.length > 0 ? args[0] : "index";

        Path path = Paths.get(indexPath);
        Directory directory = FSDirectory.open(path);
        IndexReader reader = DirectoryReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());

        Analyzer perFieldAnalyzer = FileIndexer.getAnalyzer();

        System.out.println("Inserisci una query (es: nome marte | contenuto \"indice invertito\"). Scrivi 'exit' per uscire.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }

            try {
                Query query = parseQuery(line, perFieldAnalyzer);
                runQuery(searcher, query);
            } catch (IllegalArgumentException | ParseException e) {
                System.out.println("Errore: " + e.getMessage());
            }
        }

        scanner.close();
        reader.close();
        directory.close();
    }
}
