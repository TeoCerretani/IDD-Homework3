# Indicizzazione e Recupero dell'Informazione su Documenti di Testo mediante Apache Lucene

**Matteo Cerretani**  
Dipartimento di Ingegneria – Corso di Ingegneria dei Dati  
Università degli Studi Roma Tre  
Repository del progetto: https://github.com/TeoCerretani/IDD-Homework3

---

**Sommario** — *Il presente lavoro descrive la progettazione, l'implementazione e la valutazione sperimentale di un sistema di Information Retrieval basato sulla libreria Apache Lucene per l'indicizzazione e l'interrogazione di file testuali (`.txt`) memorizzati su file system locale. Il sistema modella ciascun documento attraverso due campi distinti, corrispondenti al nome del file (`nome`) e al suo corpo testuale (`contenuto`), adottando pipeline di analisi lessicale differenziate tramite `PerFieldAnalyzerWrapper`. Vengono discusse le scelte progettuali relative agli analyzer, le prestazioni del processo di indicizzazione su un corpus di 15 documenti in lingua italiana e il comportamento del motore di ricerca su un insieme di 12 interrogazioni rappresentative valutate mediante il modello di ranking probabilistico Okapi BM25 (`BM25Similarity`).*

---

## 1. Introduzione e Architettura del Sistema

L'obiettivo del sistema sviluppato è fornire un meccanismo di indicizzazione e ricerca full-text su collezioni di file `.txt` residenti in una directory locale. In conformità con il modello dei dati di Apache Lucene, ciascun file viene rappresentato come un'istanza della classe `Document` composta da due campi di tipo `TextField` (scelti in luogo di `StringField` affinché la corrispondenza avvenga a livello di singoli termini analizzati e non sull'intera stringa indivisa), a ciascuno dei quali corrisponde una *posting list* distinta:

1. Il campo **`nome`**, contenente il nome del file privato dell'estensione `.txt` (rimossa in `FileIndexer.indexDocs` tramite `file.getName().substring(0, file.getName().length() - 4)`). Tale campo è indicizzato e memorizzato nell'indice (`Field.Store.YES`), consentendo di rileggere il valore originale privo di estensione a tempo di ricerca tramite `searcher.storedFields().document(scoreDoc.doc).get("nome")` (come verificato direttamente dalle asserzioni di `LuceneSearchTest`); il suffisso `.txt` viene poi riaggiunto esclusivamente a scopo di formattazione visiva in fase di stampa su console in `ConsoleSearcher.runQuery` (`System.out.printf("  %d. %s.txt (score: %.4f)%n", ...)`).
2. Il campo **`contenuto`**, contenente il testo integrale del file. Tale campo viene analizzato e indicizzato ma non viene memorizzato nell'indice (`Field.Store.NO`), poiché rimane interamente interrogabile tramite le *posting list* senza occupare spazio aggiuntivo nei campi *stored*.

L'architettura software, contenuta nel package `it.uniroma3.idd.hw3`, si articola in due programmi principali, affiancati da una classe di test JUnit 5 (`LuceneSearchTest.java`):

- **Programma di indicizzazione (`FileIndexer.java`)**: definisce il percorso dell'indice con `Path path = Paths.get(indexPath)` e apre la directory tramite `Directory directory = FSDirectory.open(path)`. Istanzia quindi `IndexWriterConfig config = new IndexWriterConfig(perFieldAnalyzer)`, imposta `config.setCodec(new SimpleTextCodec())` per generare segmenti in formato diagnostico leggibile e crea `IndexWriter writer = new IndexWriter(directory, config)`. Poiché Lucene è *append-only*, invoca `writer.deleteAll()` per evitare duplicazioni ad esecuzioni successive, aggiunge ciascun `Document` con `writer.addDocument(doc)` e rende visibili le modifiche a tempo di ricerca tramite `writer.commit()`.
- **Programma di ricerca (`ConsoleSearcher.java`)**: ottiene accesso in lettura all'indice tramite `IndexReader reader = DirectoryReader.open(directory)`, costruisce `IndexSearcher searcher = new IndexSearcher(reader)` e configura la funzione di ranking con `searcher.setSimilarity(new BM25Similarity())`. Legge quindi le query da console, utilizza `QueryParser` per tradurle in oggetti `Query`, esegue `TopDocs hits = searcher.search(query, 10)` e scorre l'array `hits.scoreDocs` recuperando per ciascun `ScoreDoc` il relativo `Document` tramite `StoredFields`.

L'esecuzione dei programmi tramite Maven avviene con i seguenti comandi:

```text
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
mvn test
```

---

## 2. Analisi del Testo e Motivazione degli Analyzer Scelti

In Lucene un `Analyzer` incapsula il processo di trasformazione del testo in un flusso di token (`TokenStream`), combinando un `Tokenizer` (che divide il testo in token) e una catena ordinata opzionale di `TokenFilter` (che trasformano o scartano i token, registrando attributi quali termine, posizione `position` e offset `start_offset`/`end_offset`). Poiché le modalità con cui un utente ricerca sul nome di un file differiscono da quelle sul contenuto testuale, si è utilizzato `PerFieldAnalyzerWrapper` per definire opzioni di analisi specifiche per ciascun campo, mantenendo lo stesso analyzer sia in fase di indicizzazione sia in fase di ricerca:

```java
Analyzer nomeAnalyzer = CustomAnalyzer.builder()
        .withTokenizer("whitespace")
        .addTokenFilter("wordDelimiterGraph")
        .addTokenFilter("lowercase")
        .build();

Map<String, Analyzer> byField = Map.of(
        "nome", nomeAnalyzer,
        "contenuto", new ItalianAnalyzer());

Analyzer perFieldAnalyzer = new PerFieldAnalyzerWrapper(
        new StandardAnalyzer(), byField);
```

### 2.1 Scelta dell'Analyzer per il campo `nome`

I nomi dei file presenti nel file system utilizzano frequentemente separatori quali trattini (`basi-di-dati-relazionali.txt`), underscore (`indice_invertito_lucene.txt`), spazi (`letteratura italiana novecento.txt`) o alternanza di maiuscole e minuscole (`RetiNeurali_DeepLearning.txt`). L'utilizzo di `WhitespaceAnalyzer` manterrebbe le maiuscole e non dividerebbe le parole unite da trattini o underscore, mentre `StandardAnalyzer` non separerebbe né gli underscore né le parole in *CamelCase*.

Per il campo `nome` è stato quindi definito un **`CustomAnalyzer`** composto da:
1. **`withTokenizer("whitespace")` (`WhitespaceTokenizer`)**: suddivide inizialmente la stringa in corrispondenza degli spazi bianchi.
2. **`addTokenFilter("wordDelimiterGraph")`**: spezza i token sui delimitatori intraparola (`-`, `_`, `.`) e sui cambi di cassa (*CamelCase*). Posizionare `wordDelimiterGraph` *prima* di `lowercase` consente di separare correttamente nomi come `RetiNeurali_DeepLearning` nei token `Reti`, `Neurali`, `Deep`, `Learning` e `basi-di-dati-relazionali` in `basi`, `di`, `dati`, `relazionali`, mantenendo le posizioni (`position`) necessarie per le *phrase query*.
3. **`addTokenFilter("lowercase")` (`LowerCaseFilter`)**: converte tutti i token in minuscolo per consentire ricerche *case-insensitive*.

Sul campo `nome` non vengono applicati né rimozione di stopword né stemming: poiché i nomi dei file sono brevi, anche parole brevi come `"di"` sono necessarie per cercare frasi esatte (es. `nome "basi di dati"`), mentre lo stemming altererebbe i termini esatti che identificano il file. L'estensione `.txt` viene rimossa prima dell'indicizzazione in quanto comune a tutti i file.

### 2.2 Scelta dell'Analyzer per il campo `contenuto`

Il campo `contenuto` contiene testo discorsivo in lingua italiana. Tra gli analyzer pre-costruiti messi a disposizione da Lucene è stato scelto **`ItalianAnalyzer`**, in quanto progettato specificamente per il trattamento del testo in italiano:
1. **Tokenizzazione e normalizzazione in minuscolo**: divide il testo sulle parole eliminando la punteggiatura, uniforma i caratteri in minuscolo e rimuove gli apostrofi delle elisioni italiane (ad esempio trasformando `"L'architettura"` in `architettura` e `"dell'uomo"` in `uomo`).
2. **Rimozione delle stopword italiane**: scarta le parole ad altissima frequenza prive di valore informativo (articoli, preposizioni e congiunzioni come `il`, `dello`, `nella`), evitando che falsino il ranking dei documenti.
3. **Stemming italiano**: riduce le parole flesse (singolari/plurali, maschili/femminili) alla medesima radice (ad esempio riducendo sia `"energia rinnovabile"` sia `"energie rinnovabili"` ai token `energ` e `rinnovabil`), permettendo il recupero dei documenti pertinenti indipendentemente dalla forma morfologica usata nella query.

---

## 3. Numero di File Indicizzati e Tempi di Indicizzazione

Il corpus utilizzato per la sperimentazione (`corpus/`) comprende **15 file `.txt`** in lingua italiana (per un totale di 9.044 byte):

1. `architettura_calcolatori.txt`
2. `basi-di-dati-relazionali.txt`
3. `CrittografiaModerna.txt`
4. `energia_rinnovabile_italia.txt`
5. `esplorazione-spaziale-marte.txt`
6. `filosofia_greca_antica.txt`
7. `indice_invertito_lucene.txt`
8. `IngegneriaDeiDati_Pipeline.txt`
9. `letteratura italiana novecento.txt`
10. `machine-learning-supervisionato.txt`
11. `musica_classica_sinfonie.txt`
12. `protocolli_rete_tcp_ip.txt`
13. `RetiNeurali_DeepLearning.txt`
14. `rinascimento-artistico-firenze.txt`
15. `sistemi_operativi_kernel.txt`

Il tempo di indicizzazione è stato misurato in `FileIndexer.main` sull'intera esecuzione del metodo `indexDocs`:

- **Numero di file `.txt` indicizzati:** 15 documenti.
- **Tempo di indicizzazione:** **745 ms** (comprensivo dell'apertura della `FSDirectory`, della configurazione degli analyzer, della lettura dei 15 file da disco, della scrittura dei segmenti tramite `SimpleTextCodec` e del `writer.commit()` finale; con il codec binario di default di Lucene il tempo è di circa 395 ms).

---

## 4. Sintassi delle Query e Valutazione Sperimentale

Il programma `ConsoleSearcher` legge da console una query che inizia con la parola chiave `nome` oppure `contenuto`, seguita dai termini di ricerca passati a `new QueryParser(campo, perFieldAnalyzer)`. Come previsto dalla sintassi del `QueryParser` di Lucene:
- i termini privi di modificatori sono considerati *opzionali* (combinati con `BooleanClause.Occur.SHOULD`);
- il prefisso `+` indica un termine *obbligatorio* (*required*, `BooleanClause.Occur.MUST`);
- il prefisso `-` indica un termine *proibito* (*prohibited*, `BooleanClause.Occur.MUST_NOT`);
- una sequenza di termini racchiusa tra virgolette `"..."` definisce una *phrase query* (`PhraseQuery` con `slop = 0`, che richiede la presenza di tutti i termini nella medesima sequenza posizionale).

Di seguito sono documentate le **12 query di test** utilizzate per verificare il sistema (eseguite anche automaticamente nel test JUnit `LuceneSearchTest`).

### 4.1 Interrogazioni sul campo `nome`

- **Query 1:** `nome marte`  
  *Query Lucene:* `nome:marte`  
  *Risultato:* `1. esplorazione-spaziale-marte.txt (score: 1.0950)`  
  *Analisi:* Verifica che il filtro `wordDelimiterGraph` separi le parole unite da trattini (`-`), permettendo a una `TermQuery` sul singolo termine `marte` di individuare il file.

- **Query 2:** `nome "letteratura italiana"`  
  *Query Lucene:* `nome:"letteratura italiana"`  
  *Risultato:* `1. letteratura italiana novecento.txt (score: 2.1901)`  
  *Analisi:* Verifica l'esecuzione di una `PhraseQuery` su un nome di file contenente spazi bianchi.

- **Query 3:** `nome "basi di dati"`  
  *Query Lucene:* `nome:"basi di dati"`  
  *Risultato:* `1. basi-di-dati-relazionali.txt (score: 2.6912)`  
  *Analisi:* Conferma l'importanza di non rimuovere le stopword sul campo `nome`: la preposizione `"di"` viene mantenuta nella sequenza di token e consente il match esatto della frase.

- **Query 4:** `nome neurali deep`  
  *Query Lucene:* `nome:neurali nome:deep`  
  *Risultato:* `1. RetiNeurali_DeepLearning.txt (score: 1.9332)`  
  *Analisi:* Dimostra che l'applicazione di `wordDelimiterGraph` prima di `lowercase` divide correttamente sia le transizioni *CamelCase* sia l'underscore (`Reti`, `Neurali`, `Deep`, `Learning`).

### 4.2 Interrogazioni per termini e frasi sul campo `contenuto`

- **Query 5:** `contenuto "indice invertito"`  
  *Query Lucene:* `contenuto:"indic invertit"`  
  *Risultato:* `1. indice_invertito_lucene.txt (score: 2.9390)`  
  *Analisi:* Verifica una `PhraseQuery` sul campo `contenuto` in cui i termini della frase vengono sottoposti a stemming da `ItalianAnalyzer` (`indic` seguito immediatamente da `invertit`).

- **Query 6:** `contenuto "sistema operativo"`  
  *Query Lucene:* `contenuto:"sistem operativ"`  
  *Risultato:* `1. sistemi_operativi_kernel.txt (score: 2.0287)`  
  *Analisi:* Sebbene il nome del file sia al plurale (`sistemi_operativi_kernel`), la `PhraseQuery` sul contenuto individua l'espressione nel testo.

- **Query 7:** `contenuto crittografia`  
  *Query Lucene:* `contenuto:crittograf`  
  *Risultato:* `1. CrittografiaModerna.txt (score: 1.7240)`  
  *Analisi:* Ricerca di un singolo termine sul contenuto; il punteggio dipende dalla frequenza del termine nel documento (*term frequency*, pari a 3 occorrenze) e dalla sua rarità nell'indice (*document frequency*).

- **Query 8:** `contenuto energie rinnovabili`  
  *Query Lucene:* `contenuto:energ contenuto:rinnovabil`  
  *Risultato:* `1. energia_rinnovabile_italia.txt (score: 2.9082)`  
  *Analisi:* Mostra l'effetto dello stemming di `ItalianAnalyzer`: cercando al plurale (`energie rinnovabili`), la query trova sia l'occorrenza plurale sia quella singolare (`"energia rinnovabile"`) contenute nel testo.

### 4.3 Interrogazioni con modificatori booleani (`+`), Ranking BM25 e Stopword

- **Query 9:** `contenuto +apprendimento +modello`  
  *Query Lucene:* `+contenuto:apprendiment +contenuto:modell`  
  *Risultato:* `1. machine-learning-supervisionato.txt (score: 1.7436)`  
  *Analisi:* Il modificatore `+` (*required*) impone che entrambi i termini compaiano nel documento, scartando i file che contengono solo la parola `"modello"` (`RetiNeurali_DeepLearning.txt` e `architettura_calcolatori.txt`).

- **Query 10:** `contenuto +architettura +memoria`  
  *Query Lucene:* `+contenuto:architettur +contenuto:memor`  
  *Risultato:* `1. architettura_calcolatori.txt (score: 1.8177)`  
  *Analisi:* Verifica la ricerca con due termini obbligatori (`+`) e la corretta gestione dell'apostrofo iniziale su `"L'architettura"` nel testo.

- **Query 11:** `contenuto architettura dati`  
  *Query Lucene:* `contenuto:architettur contenuto:dati`  
  *Risultati (8 documenti ordinati con `BM25Similarity`):*
  ```text
  1. IngegneriaDeiDati_Pipeline.txt      (score: 1.1604)
  2. architettura_calcolatori.txt        (score: 1.1508)
  3. basi-di-dati-relazionali.txt        (score: 0.6856)
  4. musica_classica_sinfonie.txt        (score: 0.5623)
  5. protocolli_rete_tcp_ip.txt          (score: 0.5623)
  6. machine-learning-supervisionato.txt (score: 0.4320)
  7. RetiNeurali_DeepLearning.txt        (score: 0.4251)
  8. indice_invertito_lucene.txt         (score: 0.4055)
  ```
  *Analisi:* Verifica il ranking **Okapi BM25** (`BM25Similarity`) su una query con due termini opzionali. Si noti innanzitutto il comportamento reale di `ItalianAnalyzer`: mentre `"architettura"` (12 caratteri) viene ridotto alla radice `architettur`, il termine `"dati"` resta invariato come `dati` poiché l'algoritmo di stemming italiano di Lucene non tronca le parole di lunghezza inferiore a 6 caratteri. Ai primi due posti della graduatoria si posizionano gli unici due documenti che contengono *entrambi* i token cercati (`IngegneriaDeiDati_Pipeline.txt`, dove il plurale `"architetture"` converge in `architettur` e `"dati"` compare 3 volte, seguito da `architettura_calcolatori.txt`). Dal 3° all'8° posto compaiono i documenti che contengono uno solo dei due termini, ordinati in base alla *term frequency* locale e alla lunghezza del documento (si noti in 4ª posizione `musica_classica_sinfonie.txt`, recuperato perché contiene il plurale `"architetture"` ridotto allo stem `architettur`).

- **Query 12:** `contenuto il dello nella`  
  *Query Lucene:* *(vuota)*  
  *Risultato:* `Risultati trovati: 0`  
  *Analisi:* I tre termini sono stopword italiane e vengono interamente scartati da `ItalianAnalyzer`, producendo zero risultati.
