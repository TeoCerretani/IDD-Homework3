# Indicizzazione e Recupero dell'Informazione su Documenti di Testo mediante Apache Lucene

**Matteo Cerretani**  
Dipartimento di Ingegneria – Corso di Ingegneria dei Dati  
Università degli Studi Roma Tre  
Email: `mat.cerretani@stud.uniroma3.it` – `matteo.cerretani@gmail.com`  
Repository del progetto: https://github.com/TeoCerretani/IDD-Homework3

---

**Sommario** — *Il presente lavoro descrive la progettazione, l'implementazione e la valutazione sperimentale di un sistema di Information Retrieval basato sulla libreria Apache Lucene per l'indicizzazione e l'interrogazione di file testuali (`.txt`) memorizzati su file system locale. Il sistema modella ciascun documento attraverso due campi distinti, corrispondenti al nome del file (`nome`) e al suo corpo testuale (`contenuto`), adottando pipeline di analisi lessicale differenziate tramite `PerFieldAnalyzerWrapper`. Vengono discusse le scelte progettuali relative agli analyzer, la metodologia di costruzione del dataset di valutazione costituito da 80 documenti suddivisi in 8 cluster tematici, le prestazioni del processo di indicizzazione e la valutazione quantitativa del motore di ricerca mediante il modello di ranking Okapi BM25 (`BM25Similarity`) e le metriche classiche di Information Retrieval (Precision, Recall, $F_1$-score e Mean Average Precision).*

---

## 1. Introduzione e Architettura del Sistema

L'obiettivo del sistema sviluppato è fornire un meccanismo di indicizzazione e ricerca full-text su collezioni di file `.txt` residenti in una directory locale. In conformità con il modello dei dati di Apache Lucene, ciascun file viene rappresentato come un'istanza della classe `Document` composta da due campi di tipo `TextField` (scelti in luogo di `StringField` affinché la corrispondenza avvenga a livello di singoli termini analizzati e non sull'intera stringa indivisa), a ciascuno dei quali corrisponde una *posting list* distinta:

1. Il campo **`nome`**, contenente il nome del file privato dell'estensione `.txt` (rimossa in `FileIndexer.indexDocs` tramite `file.getName().substring(0, file.getName().length() - 4)`). Tale campo è indicizzato e memorizzato nell'indice (`Field.Store.YES`), consentendo di rileggere il valore originale privo di estensione a tempo di ricerca tramite `searcher.storedFields().document(scoreDoc.doc).get("nome")` (come verificato direttamente dalle asserzioni di `LuceneSearchTest`); il suffisso `.txt` viene poi riaggiunto esclusivamente a scopo di leggibilità in fase di stampa su console in `ConsoleSearcher.runQuery` (`System.out.printf("  %d. %s.txt (score: %.4f)%n", ...)`).
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
3. **Stemming italiano**: riduce le parole flesse (singolari/plurali, maschili/femminili) alla medesima radice (ad esempio riducendo sia `"energia rinnovabile"` sia `"energie rinnovabili"` ai token `energ` e `rinnovabil`), incrementando il *recall* del sistema sulle interrogazioni testuali.

---

## 3. Costruzione del Dataset e Tempi di Indicizzazione

### 3.1 Metodologia di costruzione del corpus per la valutazione

Per condurre una valutazione sperimentale statisticamente significativa — evitando collezioni troppo esigue in cui il recupero e la verifica del *recall* risulterebbero banali — è stato costruito nella directory `corpus/` un dataset di **80 file `.txt`** in lingua italiana (per un totale di **29.069 byte**).

Il dataset è stato progettato secondo una metodologia controllata basata su *ground truth* nota a priori, articolando gli 80 documenti in **8 cluster tematici bilanciati da 10 documenti ciascuno**:
1. **Basi di Dati e Ingegneria dei Dati (10 file)**: es. `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `database_relazionali_sql.txt`, `IngegneriaDeiDati_Pipeline.txt`, `data_warehouse_olap.txt`, `data_lake_cloud_storage.txt`, ecc.
2. **Information Retrieval e Motori di Ricerca (10 file)**: es. `indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt`, `valutazione_precision_recall_map.txt`, `espansione_query_sinonimi_tesauro.txt`, ecc.
3. **Intelligenza Artificiale e Machine Learning (10 file)**: es. `RetiNeurali_DeepLearning.txt`, `ModelliNeurali_VisioneArtificiale.txt`, `DeepLearning_Trasformatori.txt`, `machine-learning-supervisionato.txt`, `regressione_lineare_logistica.txt`, `alberi_decisione_random_forest.txt`, `clustering_non_supervisionato.txt`, ecc.
4. **Architettura dei Calcolatori e Sistemi Operativi (10 file)**: es. `architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `pipeline_istruzioni_risc.txt`, `sistemi_operativi_kernel.txt`, `gestione_processi_scheduler.txt`, `memoria_virtuale_paginazione.txt`, `file_system_journaling.txt`, ecc.
5. **Reti di Calcolatori e Sicurezza Informatica (10 file)**: es. `CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `scambio_chiavi_diffie_hellman.txt`, `protocolli_rete_tcp_ip.txt`, ecc.
6. **Scienze Spaziali, Energia e Ambiente (10 file)**: es. `esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `atmosfera-marziana-marte.txt`, `pianeta_rosso_esobiologia.txt`, `energia_rinnovabile_italia.txt`, `impianti_fotovoltaici_solari.txt`, `parchi_eolici_offshore.txt`, `centrali_idroelettriche_alpine.txt`, `propulsione_ionica_sonde.txt`, ecc.
7. **Storia, Filosofia e Letteratura (10 file)**: es. `letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `romanzo_storico_italiano.txt`, `filosofia_greca_antica.txt`, ecc.
8. **Storia dell'Arte e Musica Classica (10 file)**: es. `rinascimento-artistico-firenze.txt`, `musica_classica_sinfonie.txt`, `prospettiva_brunelleschi_cupola.txt`, `contrappunto_fuga_bach.txt`, ecc.

Nella redazione dei testi e nella scelta dei nomi dei file sono stati introdotti intenzionalmente tre fenomeni linguistici per mettere alla prova il sistema in termini di **Precision** e **Recall**:
- **Variazioni morfologiche ed elisioni**: i documenti rilevanti alternano forme singolari e plurali (es. `"indice invertito"` in `indice_invertito_lucene.txt` e `"indici invertiti"` in `compressione_liste_invertite.txt`) e forme apostrofate (`"L'architettura"`), verificando la capacità di `ItalianAnalyzer` di ricondurle alla medesima radice.
- **Sinonimia, perifrasi e derivazione lessicale (*Vocabulary Mismatch*)**: per far emergere in modo realistico il **problema della Recall** nei modelli *bag-of-words*, all'interno di ciascun cluster alcuni documenti pertinenti descrivono il medesimo argomento senza impiegare la parola chiave principale della query (ad esempio `pianeta_rosso_esobiologia.txt` tratta di Marte usando la perifrasi `"Pianeta Rosso"`; `database_relazionali_sql.txt` usa il prestito `"database"` anziché `"basi di dati"`; `firma_digitale_rsa.txt` usa l'aggettivo derivato `"crittografici"` anziché il sostantivo `"crittografia"`).
- **Polisemia inter-dominio (Documenti "distrattori")**: termini quali `"architettura"` / `"architetture"` o `"energia solare"` compaiono anche in documenti appartenenti a cluster non pertinenti (es. `musica_classica_sinfonie.txt` o `propulsione_ionica_sonde.txt`), permettendo di misurare l'impatto dei falsi positivi sulla *Precision* e la capacità di `BM25Similarity` di ordinarli in coda ai documenti realmente rilevanti.

### 3.2 Numero di file indicizzati e tempi di indicizzazione

Il tempo di indicizzazione è stato misurato in `FileIndexer.main` tramite `System.currentTimeMillis()` sull'intera esecuzione del metodo `indexDocs` (comprensiva dell'apertura della `FSDirectory`, dell'istanziazione degli analyzer, della lettura da disco, dell'analisi dei due campi e della serializzazione su disco con `SimpleTextCodec` seguita da `writer.commit()`):

- **Numero di file `.txt` indicizzati:** **80 documenti** (29.069 byte complessivi).
- **Tempo di indicizzazione (80 file):** **536 ms** (confrontato con i ~490 ms rilevati su un sottoinsieme ridotto di 15 file: passando da 15 a 80 documenti — un incremento di oltre $5.3\times$ — il tempo cresce di soli ~45 ms, poiché la quota predominante del tempo di esecuzione è costituita dall'inizializzazione a freddo delle strutture di Lucene e della JVM, mentre la costruzione in RAM delle *posting list* e il flush su disco scalano linearmente).

---

## 4. Valutazione Sperimentale, Metriche di IR e Analisi delle Query

### 4.1 Metodologia e Metriche di Valutazione

Il programma `ConsoleSearcher` legge da console una query che inizia con `nome` oppure `contenuto`, seguita dall'espressione passata a `new QueryParser(campo, perFieldAnalyzer)`, supportando termini liberi opzionali (`BooleanClause.Occur.SHOULD`), termini obbligatori `+` (`BooleanClause.Occur.MUST`), termini proibiti `-` (`BooleanClause.Occur.MUST_NOT`) e *phrase query* tra virgolette `"..."` (`PhraseQuery` con `slop = 0`).

Per valutare rigorosamente l'efficacia del recupero sul corpus di 80 documenti, per ciascuna interrogazione informativa $q$ (Query 1–11, mentre la Query 12 costituisce un test di controllo negativo su sole stopword) si è confrontato l'insieme dei documenti restituiti dal sistema $A_q$ (con soglia `top-k = 10`) con l'insieme dei documenti rilevanti della *ground truth* $R_q$, calcolando quattro metriche standard di Information Retrieval:

1. **Precision ($P$)**: frazione di documenti rilevanti tra quelli recuperati dal sistema:
   $$P = \frac{|R_q \cap A_q|}{|A_q|}$$
2. **Recall ($R$)**: frazione di documenti rilevanti recuperati rispetto al totale dei documenti rilevanti presenti negli 80 file del corpus:
   $$R = \frac{|R_q \cap A_q|}{|R_q|}$$
3. **$F_1$-score**: media armonica tra Precision e Recall, che sintetizza il bilanciamento tra purezza e completezza del recupero:
   $$F_1 = \frac{2 \cdot P \cdot R}{P + R}$$
4. **Average Precision ($AP$) e Mean Average Precision ($MAP$)**: per valutare anche la qualità dell'ordinamento prodotto da `BM25Similarity`, si calcola $AP_q = \frac{1}{|R_q|} \sum_{k=1}^{|A_q|} P@k \cdot \text{rel}(k)$ (dove $\text{rel}(k) = 1$ se il $k$-esimo documento in graduatoria è rilevante, $0$ altrimenti) e la sua media $MAP$ sulle 11 query.

### 4.2 Risultati Sperimentali sulle 12 Query di Test

Di seguito sono riportati i risultati esatti prodotti dal codice (verificati automaticamente in `LuceneSearchTest.java`) con i punteggi **BM25** e le relative metriche di valutazione.

#### Interrogazioni sul campo `nome`

- **Query 1:** `nome marte` $\rightarrow$ *Query Lucene:* `nome:marte`  
  *Ground Truth ($|R_1| = 4$):* `atmosfera-marziana-marte.txt`, `esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `pianeta_rosso_esobiologia.txt`.  
  *Risultati restituiti ($|A_1| = 3$):*
  ```text
  1. atmosfera-marziana-marte.txt    (score: 1.4832) [Rilevante]
  2. esplorazione-spaziale-marte.txt (score: 1.4832) [Rilevante]
  3. missione_rover_marte.txt        (score: 1.4832) [Rilevante]
  ```
  *Metriche:* $P = 3/3 = \mathbf{1.000}$, $R = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.857}$, $AP = \mathbf{0.750}$.  
  *Analisi:* Il filtro `wordDelimiterGraph` separa sia i trattini (`-`) sia gli underscore (`_`), individuando tutti e 3 i file il cui nome contiene il token `marte`. Il quarto documento rilevante sul pianeta Marte (`pianeta_rosso_esobiologia.txt`) non viene recuperato poiché il suo nome utilizza la perifrasi sinonimica `"pianeta_rosso"`, illustrando come la sinonimia limiti la *Recall* ($0.750$) nelle ricerche lessicali sul titolo.

- **Query 2:** `nome "letteratura italiana"` $\rightarrow$ *Query Lucene:* `nome:"letteratura italiana"`  
  *Ground Truth ($|R_2| = 3$):* `letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `romanzo_storico_italiano.txt`.  
  *Risultati restituiti ($|A_2| = 2$):*
  ```text
  1. letteratura italiana novecento.txt (score: 3.2841) [Rilevante]
  2. letteratura-italiana-ottocento.txt (score: 3.2841) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* La `PhraseQuery` individua sia il file separato da spazi bianchi sia quello separato da trattini (`letteratura-italiana-ottocento.txt`), mentre `romanzo_storico_italiano.txt` resta escluso per l'assenza della frase esatta nel campo `nome`.

- **Query 3:** `nome "basi di dati"` $\rightarrow$ *Query Lucene:* `nome:"basi di dati"`  
  *Ground Truth ($|R_3| = 3$):* `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `database_relazionali_sql.txt`.  
  *Risultati restituiti ($|A_3| = 2$):*
  ```text
  1. basi-di-dati-relazionali.txt (score: 4.2235) [Rilevante]
  2. basi_di_dati_distribuite.txt (score: 4.2235) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* Conferma la scelta di non rimuovere le stopword sul campo `nome`: la preposizione `"di"` conserva la propria posizione (`position`) sia con i trattini sia con gli underscore. Il terzo file pertinente (`database_relazionali_sql.txt`) non viene recuperato perché impiega l'anglicismo `"database"` anziché `"basi di dati"`.

- **Query 4:** `nome neurali deep` $\rightarrow$ *Query Lucene:* `nome:neurali nome:deep`  
  *Ground Truth ($|R_4| = 3$):* `RetiNeurali_DeepLearning.txt`, `DeepLearning_Trasformatori.txt`, `ModelliNeurali_VisioneArtificiale.txt`.  
  *Risultati restituiti ($|A_4| = 3$):*
  ```text
  1. RetiNeurali_DeepLearning.txt          (score: 2.9095) [Rilevante]
  2. DeepLearning_Trasformatori.txt        (score: 1.6420) [Rilevante]
  3. ModelliNeurali_VisioneArtificiale.txt (score: 1.4547) [Rilevante]
  ```
  *Metriche:* $P = 3/3 = \mathbf{1.000}$, $R = 3/3 = \mathbf{1.000}$, $F_1 = \mathbf{1.000}$, $AP = \mathbf{1.000}$.  
  *Analisi:* L'applicazione di `wordDelimiterGraph` *prima* di `lowercase` risolve correttamente i confini *CamelCase* in tutti e 3 i file; inoltre `BM25Similarity` assegna il punteggio più alto (`2.9095`) a `RetiNeurali_DeepLearning.txt` in cui co-occorrono entrambi i token cercati.

#### Interrogazioni per termini e frasi sul campo `contenuto`

- **Query 5:** `contenuto "indice invertito"` $\rightarrow$ *Query Lucene:* `contenuto:"indic invertit"`  
  *Ground Truth ($|R_5| = 3$):* `indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt`.  
  *Risultati restituiti ($|A_5| = 2$):*
  ```text
  1. indice_invertito_lucene.txt      (score: 3.6495) [Rilevante]
  2. compressione_liste_invertite.txt (score: 3.2935) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* Dimostra il contributo diretto dello stemming di `ItalianAnalyzer` sulla *Recall*: la `PhraseQuery` al singolare recupera non solo `indice_invertito_lucene.txt`, ma anche `compressione_liste_invertite.txt` (che nel testo contiene il plurale `"gli indici invertiti"`, ridotto alla medesima sequenza `"indic invertit"`), raddoppiando la *Recall* da $0.333$ a $0.667$. Il terzo documento (`struttura_dizionario_termini.txt`) non viene estratto perché utilizza il sinonimo tecnico `"posting list"`.

- **Query 6:** `contenuto "sistema operativo"` $\rightarrow$ *Query Lucene:* `contenuto:"sistem operativ"`  
  *Ground Truth ($|R_6| = 4$):* `sistemi_operativi_kernel.txt`, `gestione_processi_scheduler.txt`, `memoria_virtuale_paginazione.txt`, `file_system_journaling.txt`.  
  *Risultati restituiti ($|A_6| = 3$):*
  ```text
  1. sistemi_operativi_kernel.txt     (score: 2.5507) [Rilevante]
  2. gestione_processi_scheduler.txt  (score: 2.2718) [Rilevante]
  3. memoria_virtuale_paginazione.txt (score: 2.1588) [Rilevante]
  ```
  *Metriche:* $P = 3/3 = \mathbf{1.000}$, $R = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.857}$, $AP = \mathbf{0.750}$.  
  *Analisi:* La `PhraseQuery` individua i 3 documenti che contengono la locuzione `"sistema operativo"` / `"sistemi operativi"`, mentre `file_system_journaling.txt` (che parla di `"sottosistema di archiviazione"` senza citare esplicitamente `"sistema operativo"`) costituisce l'unico falso negativo.

- **Query 7:** `contenuto crittografia` $\rightarrow$ *Query Lucene:* `contenuto:crittograf`  
  *Ground Truth ($|R_7| = 4$):* `CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `scambio_chiavi_diffie_hellman.txt`.  
  *Risultati restituiti ($|A_7| = 2$):*
  ```text
  1. CrittografiaModerna.txt    (score: 2.2468) [Rilevante]
  2. cifrari_simmetrici_aes.txt (score: 1.6916) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/4 = \mathbf{0.500}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.500}$.  
  *Analisi:* Questa query mette in luce un aspetto tecnico fondamentale sulla **Recall** e sullo stemming dell'italiano: mentre il sostantivo `"crittografia"` viene ridotto da `ItalianAnalyzer` alla radice `crittograf` (recuperando `CrittografiaModerna.txt` e `cifrari_simmetrici_aes.txt`), nel documento `firma_digitale_rsa.txt` compare l'aggettivo derivato `"crittografici"`, che lo stemmer leggero riduce a `crittografic` (rimuovendo la desinenza flessiva `-i` ma non il suffisso derivazionale `-ic-`). Di conseguenza `firma_digitale_rsa.txt` e `scambio_chiavi_diffie_hellman.txt` (che usa `"segreto condiviso"`) non vengono recuperati, fermando la *Recall* a $0.500$.

- **Query 8:** `contenuto energie rinnovabili` $\rightarrow$ *Query Lucene:* `contenuto:energ contenuto:rinnovabil`  
  *Ground Truth ($|R_8| = 4$):* `energia_rinnovabile_italia.txt`, `parchi_eolici_offshore.txt`, `impianti_fotovoltaici_solari.txt`, `centrali_idroelettriche_alpine.txt`.  
  *Risultati restituiti ($|A_8| = 4$):*
  ```text
  1. energia_rinnovabile_italia.txt   (score: 3.1187) [Rilevante]
  2. parchi_eolici_offshore.txt       (score: 2.9337) [Rilevante]
  3. impianti_fotovoltaici_solari.txt (score: 2.8559) [Rilevante]
  4. propulsione_ionica_sonde.txt     (score: 1.3506) [Non rilevante]
  ```
  *Metriche:* $P = 3/4 = \mathbf{0.750}$, $R = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.750}$, $AP = \mathbf{0.750}$.  
  *Analisi:* Lo stemming unifica singolare e plurale permettendo di recuperare in testa alla graduatoria (con score $> 2.85$) tutti e 3 i documenti sulle energie rinnovabili che usano tali termini (`centrali_idroelettriche_alpine.txt` usa invece la perifrasi `"fonte pulita e rigenerabile"`). In 4ª posizione viene estratto anche il falso positivo `propulsione_ionica_sonde.txt` (che cita i `"pannelli di energia solare della sonda"`), ma `BM25Similarity` gli assegna uno score nettamente inferiore (`1.3506`), mantenendo tutti i documenti rilevanti nelle prime 3 posizioni.

#### Interrogazioni con modificatori booleani (`+`), Ranking BM25 e Stopword

- **Query 9:** `contenuto +apprendimento +modello` $\rightarrow$ *Query Lucene:* `+contenuto:apprendiment +contenuto:modell`  
  *Ground Truth ($|R_9| = 4$):* `regressione_lineare_logistica.txt`, `machine-learning-supervisionato.txt`, `alberi_decisione_random_forest.txt`, `clustering_non_supervisionato.txt`.  
  *Risultati restituiti ($|A_9| = 2$):*
  ```text
  1. regressione_lineare_logistica.txt   (score: 2.9703) [Rilevante]
  2. machine-learning-supervisionato.txt (score: 2.2632) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/4 = \mathbf{0.500}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.500}$.  
  *Analisi:* Illustra il classico **compromesso Precision/Recall** indotto dall'operatore booleano obbligatorio `+` (`BooleanClause.Occur.MUST`): richiedere la co-occorrenza di entrambi i concetti elimina tutti i falsi positivi che contengono solo `"modello"` in altri contesti (portando la *Precision* a $1.000$), ma penalizza la *Recall* ($0.500$) escludendo i documenti di Machine Learning che parlano di `"alberi decisionali"` o `"algoritmi di partizionamento"` senza contenere contemporaneamente entrambi i token.

- **Query 10:** `contenuto +architettura +memoria` $\rightarrow$ *Query Lucene:* `+contenuto:architettur +contenuto:memor`  
  *Ground Truth ($|R_{10}| = 3$):* `architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `pipeline_istruzioni_risc.txt`.  
  *Risultati restituiti ($|A_{10}| = 2$):*
  ```text
  1. architettura_calcolatori.txt   (score: 2.5662) [Rilevante]
  2. gerarchia_cache_processore.txt (score: 2.1373) [Rilevante]
  ```
  *Metriche:* $P = 2/2 = \mathbf{1.000}$, $R = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* Verifica la gestione dell'elisione apostrofata (`"L'architettura"`) e la selezione congiuntiva dei documenti sull'architettura hardware e la gerarchia di memoria (escludendo i documenti che trattano solo di memoria virtuale a livello di sistema operativo o solo della pipeline RISC).

- **Query 11:** `contenuto architettura dati` $\rightarrow$ *Query Lucene:* `contenuto:architettur contenuto:dati`  
  *Ground Truth ($|R_{11}| = 5$, architetture di gestione dei dati):* `data_lake_cloud_storage.txt`, `data_warehouse_olap.txt`, `IngegneriaDeiDati_Pipeline.txt`, `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`.  
  *Risultati restituiti ($|A_{11}| = 10$, Top-10 ordinati con `BM25Similarity`):*
  ```text
  1.  data_lake_cloud_storage.txt          (score: 2.0820) [Rilevante]
  2.  data_warehouse_olap.txt              (score: 2.0024) [Rilevante]
  3.  IngegneriaDeiDati_Pipeline.txt       (score: 1.9933) [Rilevante]
  4.  architettura_calcolatori.txt         (score: 1.8519) [Non rilevante - Hardware]
  5.  basi-di-dati-relazionali.txt         (score: 1.4608) [Rilevante]
  6.  basi_di_dati_distribuite.txt         (score: 1.2532) [Rilevante]
  7.  ModelliNeurali_VisioneArtificiale.txt (score: 1.1271) [Non rilevante - AI]
  8.  gerarchia_cache_processore.txt       (score: 1.0398) [Non rilevante - Hardware]
  9.  machine-learning-supervisionato.txt  (score: 0.8280) [Non rilevante - AI]
  10. RetiNeurali_DeepLearning.txt         (score: 0.8105) [Non rilevante - AI]
  ```
  *Metriche (su Top-10):* $P@10 = 5/10 = \mathbf{0.500}$ ($P@3 = \mathbf{1.000}$, $P@6 = \mathbf{0.833}$), $R@10 = 5/5 = \mathbf{1.000}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.927}$.  
  *Analisi:* Questa query disgiuntiva (`SHOULD`) dimostra in modo esemplare il ruolo del ranking **BM25** quando si massimizza la **Recall** ($R@10 = 1.000$). Si noti innanzitutto che `ItalianAnalyzer` riduce `"architettura"` (12 caratteri) a `architettur`, mentre lascia invariato `"dati"` poiché lo stemmer italiano di Lucene non tronca le parole di lunghezza inferiore a 6 caratteri. Sebbene su 10 documenti estratti la precisione globale sia $P@10 = 0.500$ a causa della polisemia dei termini `"architettura"` e `"dati"` in ambito hardware e AI, **BM25 colloca i 5 documenti rilevanti nelle posizioni 1, 2, 3, 5 e 6**, garantendo una precisione ai primi tre posti $P@3 = 1.000$ e un'eccellente Average Precision ($AP = \frac{1}{5}(1/1 + 2/2 + 3/3 + 4/5 + 5/6) = 0.927$).

- **Query 12:** `contenuto il dello nella` $\rightarrow$ *Query Lucene:* *(vuota)*  
  *Risultato:* `Risultati trovati: 0`  
  *Analisi:* Test di controllo negativo: i tre termini sono stopword italiane e vengono interamente scartati da `ItalianAnalyzer`, producendo una query vuota e restituendo correttamente zero documenti ($|R_{12}| = 0, |A_{12}| = 0$).

---

### 4.3 Tabella Riepilogativa delle Metriche di Valutazione

La seguente tabella riassume le prestazioni quantitative ottenute dal sistema sulle 11 interrogazioni informative ($Q_1 \dots Q_{11}$) eseguite sul corpus di 80 documenti:

| Query | Campo | Sintassi Query | $\|R_q\|$ (Rilevanti) | $\|A_q\|$ (Estratti) | $\|R_q \cap A_q\|$ (Veri Positivi) | Precision ($P$) | Recall ($R$) | $F_1$-score | Average Precision ($AP$) |
|:---:|:---:|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Q1** | `nome` | `marte` | 4 | 3 | 3 | 1.000 | 0.750 | 0.857 | 0.750 |
| **Q2** | `nome` | `"letteratura italiana"` | 3 | 2 | 2 | 1.000 | 0.667 | 0.800 | 0.667 |
| **Q3** | `nome` | `"basi di dati"` | 3 | 2 | 2 | 1.000 | 0.667 | 0.800 | 0.667 |
| **Q4** | `nome` | `neurali deep` | 3 | 3 | 3 | 1.000 | 1.000 | 1.000 | 1.000 |
| **Q5** | `contenuto` | `"indice invertito"` | 3 | 2 | 2 | 1.000 | 0.667 | 0.800 | 0.667 |
| **Q6** | `contenuto` | `"sistema operativo"` | 4 | 3 | 3 | 1.000 | 0.750 | 0.857 | 0.750 |
| **Q7** | `contenuto` | `crittografia` | 4 | 2 | 2 | 1.000 | 0.500 | 0.667 | 0.500 |
| **Q8** | `contenuto` | `energie rinnovabili` | 4 | 4 | 3 | 0.750 | 0.750 | 0.750 | 0.750 |
| **Q9** | `contenuto` | `+apprendimento +modello` | 4 | 2 | 2 | 1.000 | 0.500 | 0.667 | 0.500 |
| **Q10** | `contenuto` | `+architettura +memoria` | 3 | 2 | 2 | 1.000 | 0.667 | 0.800 | 0.667 |
| **Q11** | `contenuto` | `architettura dati` | 5 | 10 | 5 | 0.500 | 1.000 | 0.667 | 0.927 |
| **Media** | — | **Macro-Average / MAP** | **40** | **35** | **29** | **0.932** | **0.720** | **0.798** | **MAP = 0.717** |

In conclusione, la valutazione sperimentale sugli 80 documenti evidenzia una **Precision media del 93.2%** e una **Recall media del 72.0%** ($F_1 = 79.8\%$, $MAP = 71.7\%$). L'analisi conferma che l'impiego di `wordDelimiterGraph` sul campo `nome` e dello stemming di `ItalianAnalyzer` sul campo `contenuto` abbatte le mancate corrispondenze dovute a separatori e flessioni morfologiche, mentre i casi residui di mancato recupero (*falsi negativi* che limitano la *Recall*) derivano da sinonimia lessicale (`"marte"` vs `"Pianeta Rosso"`, `"basi di dati"` vs `"database"`) o suffissi derivazionali (`"crittografia"` vs `"crittografici"`), fenomeni strutturali dei modelli *bag-of-words* affrontabili solo mediante tecniche di *query expansion* o dizionari di sinonimi.
