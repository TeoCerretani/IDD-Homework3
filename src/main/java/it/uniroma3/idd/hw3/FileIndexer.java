package it.uniroma3.idd.hw3;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.simpletext.SimpleTextCodec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Programma che indicizza i file .txt contenuti in una directory locale
 * utilizzando due campi ("nome" e "contenuto") e un analyzer dedicato per ciascuno.
 */
public class FileIndexer {

    public static final String CAMPO_NOME = "nome";
    public static final String CAMPO_CONTENUTO = "contenuto";

    /**
     * Definisce gli analyzer per ciascun campo:
     * - "nome": CustomAnalyzer con whitespace, wordDelimiterGraph e lowercase
     * - "contenuto": ItalianAnalyzer per il testo in lingua italiana
     */
    public static Analyzer getAnalyzer() throws IOException {
        Analyzer nomeAnalyzer = CustomAnalyzer.builder()
                .withTokenizer("whitespace")
                .addTokenFilter("wordDelimiterGraph")
                .addTokenFilter("lowercase")
                .build();

        Map<String, Analyzer> byField = Map.of(
                CAMPO_NOME, nomeAnalyzer,
                CAMPO_CONTENUTO, new ItalianAnalyzer());

        Analyzer perFieldAnalyzer = new PerFieldAnalyzerWrapper(
                new StandardAnalyzer(), byField);

        return perFieldAnalyzer;
    }

    /**
     * Indicizza tutti i file .txt presenti nella directory docsPath salvando l'indice in indexPath.
     */
    public static int indexDocs(String docsPath, String indexPath) throws IOException {
        Path path = Paths.get(indexPath);
        Directory directory = FSDirectory.open(path);

        Analyzer perFieldAnalyzer = getAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(perFieldAnalyzer);
        config.setCodec(new SimpleTextCodec());

        IndexWriter writer = new IndexWriter(directory, config);
        writer.deleteAll();

        File docsDir = new File(docsPath);
        File[] files = docsDir.listFiles();
        int count = 0;

        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".txt")) {
                    String nomeFile = file.getName().substring(0, file.getName().length() - 4);
                    String contenutoFile = Files.readString(file.toPath(), StandardCharsets.UTF_8);

                    Document doc = new Document();
                    doc.add(new TextField(CAMPO_NOME, nomeFile, Field.Store.YES));
                    doc.add(new TextField(CAMPO_CONTENUTO, contenutoFile, Field.Store.NO));

                    writer.addDocument(doc);
                    count++;
                }
            }
        }

        writer.commit();
        writer.close();
        directory.close();
        return count;
    }

    public static void main(String[] args) throws IOException {
        String docsPath = args.length > 0 ? args[0] : "corpus";
        String indexPath = args.length > 1 ? args[1] : "index";

        long startTime = System.currentTimeMillis();
        int indexedFiles = indexDocs(docsPath, indexPath);
        long endTime = System.currentTimeMillis();

        System.out.println("File .txt indicizzati: " + indexedFiles);
        System.out.println("Tempo di indicizzazione: " + (endTime - startTime) + " ms");
    }
}
