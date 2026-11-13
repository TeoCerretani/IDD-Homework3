# Indicizzazione e Ricerca su File di Testo mediante Apache Lucene

**Matteo Cerretani**  
Dipartimento di Ingegneria – Corso di Ingegneria dei Dati  
Università degli Studi Roma Tre  
Email: `mat.cerretani@stud.uniroma3.it` – `matteo.cerretani@gmail.com`  
Repository del progetto: https://github.com/TeoCerretani/IDD-Homework3

---

**Sommario** — *In questa relazione vengono presentate la progettazione, l'implementazione e la valutazione sperimentale di un sistema di Information Retrieval basato su Apache Lucene (v9.12.0) per l'indicizzazione e la ricerca full-text su file di testo (`.txt`) memorizzati su file system locale. Ciascun file è rappresentato da un `Document` composto da due campi (`nome` e `contenuto`), indicizzati in posting list distinte tramite `PerFieldAnalyzerWrapper`: per il campo `nome` è stato definito un `CustomAnalyzer` basato su `WhitespaceTokenizer`, `WordDelimiterGraphFilter` e `LowerCaseFilter`, mentre per il campo `contenuto` è stato adottato `ItalianAnalyzer`. Per valutare i tempi di indicizzazione e le metriche di recupero su un numero significativo di file, è stato costruito un dataset di 600 documenti `.txt` (169.081 byte) suddiviso in 8 aree tematiche da 75 file ciascuna. Il sistema viene valutato misurando i tempi di indicizzazione al crescere del corpus da 50 a 600 documenti (confrontando il Codec binario predefinito con `SimpleTextCodec`) e calcolando su 12 interrogazioni le metriche di Precision ($P@10$), Recall ($R@10$ sui primi 10 risultati e $R_{\text{tot}}$ sull'intero indice), $F_1$-score e Mean Average Precision ($MAP$), confrontando infine la configurazione scelta con `ClassicSimilarity` (TF-IDF), `StandardAnalyzer` e `WhitespaceAnalyzer`.*

---

## 1. Architettura del Sistema

Il sistema realizzato permette di indicizzare una collezione di file `.txt` presenti in una directory locale e di eseguire interrogazioni da console sui singoli campi. In Apache Lucene l'unità fondamentale di indicizzazione e ricerca è la classe `Document`, costituita da un insieme di campi (`Field`) a ciascuno dei quali corrisponde una **posting list** separata nell'indice invertito.

Ogni file `.txt` del corpus viene rappresentato come un'istanza di `Document` con due campi di tipo `TextField` (scelti al posto di `StringField` affinché il testo non venga indicizzato come un'unica stringa indivisa ma venga sottoposto a *tokenizzazione* e *analisi*, consentendo ricerche per singoli termini e per frasi):

1. Il campo **`nome`**, che contiene il nome del file privato dell'estensione `.txt` (rimossa in `FileIndexer.indexDocs` tramite `file.getName().substring(0, file.getName().length() - 4)`). Il campo viene sia indicizzato sia memorizzato nell'indice (`Field.Store.YES`), così da poterne rileggere il valore originale a tempo di ricerca tramite `searcher.storedFields().document(scoreDoc.doc).get("nome")`. L'estensione `.txt` viene riaggiunta solo a video in fase di stampa dei risultati in `ConsoleSearcher.runQuery` (`System.out.printf("  %d. %s.txt (score: %.4f)%n", ...)`).
2. Il campo **`contenuto`**, che contiene il testo del file letto in codifica UTF-8. Questo campo viene analizzato e indicizzato nelle relative *posting list*, ma non viene memorizzato nei campi *stored* dell'indice (`Field.Store.NO`): non dovendo ristampare a schermo l'intero testo dei file trovati, si evita di duplicare inutilmente il contenuto nei file `.fld` dell'indice.

Il progetto (`it.uniroma3.idd.hw3`) è composto da due classi principali e da una classe di test JUnit 5 (`LuceneSearchTest.java`):

- **Indicizzazione (`FileIndexer.java`)**: apre la directory dell'indice su file system tramite `FSDirectory.open(Paths.get(indexPath))`, configura `IndexWriterConfig` con il `PerFieldAnalyzerWrapper` e imposta `config.setCodec(new SimpleTextCodec())` (dal modulo `lucene-codecs`) per scrivere i file dei segmenti dell'indice in formato testuale leggibile. Prima di scandire la cartella `corpus/` (i cui file vengono ordinati alfabeticamente per garantire un'indicizzazione deterministica), viene invocato `writer.deleteAll()` per ripulire eventuali indici precedenti; per ciascuno dei 600 file `.txt` viene creato il relativo `Document` e passato a `writer.addDocument(doc)`. Internamente, `IndexWriter` accumula i documenti analizzati in un buffer in memoria RAM gestito da `DocumentsWriter`; al termine del ciclo, la chiamata `writer.commit()` esegue il *flush* del buffer scrivendo il segmento immutabile su disco e sincronizza il file di commit `segments_1`, rendendo l'indice visibile ai lettori.
- **Ricerca (`ConsoleSearcher.java`)**: apre l'indice in lettura con `DirectoryReader.open(directory)`, crea `IndexSearcher` e imposta il modello di ranking probabilistico `searcher.setSimilarity(new BM25Similarity())`. Il programma legge da console una stringa che inizia con il nome del campo (`nome` oppure `contenuto`), utilizza `QueryParser` (configurato con lo stesso `PerFieldAnalyzerWrapper` usato in fase di indicizzazione) per costruire l'oggetto `Query` (`TermQuery`, `PhraseQuery` o `BooleanQuery`) e invoca `searcher.search(query, 10)`. A tempo di esecuzione, `IndexSearcher` crea a partire dalla `Query` un oggetto `Weight` (che calcola le statistiche globali *IDF* sull'indice) e, per ciascun segmento dell'indice, istanzia uno `Scorer` che scorre le *posting list* dei termini cercati calcolando il punteggio BM25 di ogni documento e restituendo i primi 10 risultati nell'oggetto `TopDocs`.

I comandi Maven per eseguire l'indicizzatore, il motore di ricerca da console e i test automatici sono:

```text
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
mvn test
```

---

## 2. Scelta e Progettazione degli Analyzer

In Lucene un `Analyzer` estrae i termini di indice dal testo attraverso una pipeline composta da un singolo **`Tokenizer`** (che divide il flusso di caratteri in token) seguito da una catena ordinata di **`TokenFilter`** (che modificano, normalizzano o rimuovono i token). Ad ogni token sono associati attributi quali la stringa del termine, la posizione (`position`, necessaria per le `PhraseQuery`) e gli offset nel testo originale.

Poiché i **nomi dei file** e il **contenuto testuale** hanno caratteristiche molto diverse, usare un solo analyzer per entrambi i campi penalizzerebbe la ricerca. Si è quindi utilizzato `PerFieldAnalyzerWrapper` per associare a ciascun campo un analyzer specifico:

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

### 2.1 Analyzer per il campo `nome`

I nomi dei file utilizzano spesso convenzioni prive di spazi bianchi, come il trattino (`basi-di-dati-relazionali.txt`), l'underscore (`indice_invertito_lucene.txt`), gli spazi (`letteratura italiana novecento.txt`) o il *CamelCase* (`RetiNeurali_DeepLearning.txt`). Gli analyzer predefiniti di Lucene presentano limiti evidenti su questo tipo di stringhe:
- **`WhitespaceAnalyzer`**: divide solo sugli spazi bianchi senza convertire in minuscolo né spezzare trattini o underscore; il file `basi-di-dati-relazionali` resterebbe un unico token indiviso e non sarebbe trovato cercando `nome:dati` o `nome:"basi di dati"`.
- **`SimpleAnalyzer`**: divide sui caratteri non alfabetici e converte in minuscolo, ma elimina i numeri (es. `x86` in `architettura_set_istruzioni_x86_arm.txt` o `5g` in `reti_cellulari_5g_beamforming.txt`) e non separa il *CamelCase* (`RetiNeurali` diventerebbe `retineurali`).
- **`StandardAnalyzer`**: separa i trattini, ma secondo la segmentazione Unicode standard non divide l'underscore `_` né il *CamelCase*. Di conseguenza `basi_di_dati_distribuite` rimarrebbe un singolo token con gli underscore e `RetiNeurali_DeepLearning` diventerebbe `retineurali_deeplearning`, facendo perdere metà dei risultati rilevanti.

Per gestire tutte queste convenzioni è stato costruito con `CustomAnalyzer.builder()` un analyzer composto da tre fasi:
1. **`whitespace` (`WhitespaceTokenizer`)**: separa i token in corrispondenza degli spazi bianchi.
2. **`wordDelimiterGraph` (`WordDelimiterGraphFilter`)**: spezza i token sui delimitatori non alfanumerici (`-`, `_`, `.`), sui passaggi lettera-numero e sui cambi di cassa minuscolo-maiuscolo (*CamelCase*), aggiornando le posizioni dei sotto-token. **L'ordine dei filtri è fondamentale**: `wordDelimiterGraph` deve precedere `lowercase` affinché possa riconoscere le lettere maiuscole in `RetiNeurali_DeepLearning` e generare i 4 token `Reti`, `Neurali`, `Deep`, `Learning`; se `lowercase` venisse eseguito prima, l'informazione sulle maiuscole andrebbe persa.
3. **`lowercase` (`LowerCaseFilter`)**: converte tutti i token estratti in minuscolo (`reti`, `neurali`, `deep`, `learning`) per permettere ricerche *case-insensitive*.

Sul campo `nome` non vengono applicati né rimozione delle stopword né stemming: nei titoli brevi dei file le preposizioni fanno parte di locuzioni esatte (come `"di"` nella phrase query `nome "basi di dati"`), mentre l'estensione `.txt` viene rimossa a monte perché presente in tutti i file ($\text{IDF} \approx 0$).

### 2.2 Analyzer per il campo `contenuto`

Il campo `contenuto` contiene testo discorsivo in lingua italiana. Per questo campo è stato scelto **`ItalianAnalyzer`** (dal modulo `lucene-analysis-common`), che combina quattro operazioni:
1. **`StandardTokenizer` ed `ElisionFilter`**: divide il testo sulle parole rimuovendo la punteggiatura e taglia gli articoli e le preposizioni apostrofate (`l'`, `un'`, `dell'`, `nell'`, `all'`, `sull'`, `d'`), così che `"L'architettura"` o `"dell'informazione"` vengano indicizzati correttamente come `architettura` e `informazione`.
2. **`LowerCaseFilter`**: converte i token in minuscolo.
3. **`StopFilter` (Stopword italiane)**: elimina articoli, preposizioni, congiunzioni e verbi ausiliari (`il`, `lo`, `la`, `dei`, `dello`, `nella`, `che`, `per`, `con`, `sono`), riducendo la dimensione dell'indice ed evitando che parole prive di valore informativo alterino il punteggio dei documenti.
4. **`ItalianLightStemFilter`**: applica lo stemming leggero per l'italiano riconducendo singolari e plurali alla stessa radice (es. sia `"indice invertito"` sia `"indici invertiti"` diventano `"indic invertit"`, e `"energia rinnovabile"` / `"energie rinnovabili"` diventano `energ` e `rinnovabil`). Trattandosi di uno stemmer *light*, rimuove le desinenze flessive ma non altera le parole più corte di 6 caratteri (ad esempio `"dati"`, di 4 lettere, resta `dati`).

---

## 3. Costruzione del Dataset e Tempi di Indicizzazione

### 3.1 Costruzione del Corpus di 600 file `.txt`

Testare un motore di ricerca su soli 10–15 file non permette né di misurare i tempi di indicizzazione al netto dell'avvio della JVM, né di valutare in modo realistico la **Recall**, perché con pochi documenti quasi tutti i file contenenti una parola rientrano sempre nei primi 10 risultati (`topN = 10`).

Per effettuare test più significativi, nella cartella `corpus/` è stato costruito un dataset di **600 file `.txt` in lingua italiana**, per un totale di **169.081 byte** (in media ~282 byte e circa 40 parole per file). Il corpus è stato diviso in modo bilanciato in **8 aree tematiche da 75 documenti ciascuna**, come riassunto nella Tabella 1.

| Dominio Tematico | N. File | Argomenti Trattati | Esempi di File nel Corpus |
|:---|:---:|:---|:---|
| **D1 – Basi di Dati e Data Engineering** | 75 | Modello relazionale, SQL, sistemi NoSQL e distribuiti, pipeline ETL, Data Warehouse e Data Lake | `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `IngegneriaDeiDati_Pipeline.txt`, `data_lake_cloud_storage.txt` |
| **D2 – Information Retrieval** | 75 | Indici invertiti, dizionario dei termini, compressione delle posting list, stemming, modelli TF-IDF e BM25 | `indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt` |
| **D3 – Intelligenza Artificiale e ML** | 75 | Apprendimento supervisionato, clustering, reti neurali profonde, transformer e visione artificiale | `RetiNeurali_DeepLearning.txt`, `DeepLearning_Trasformatori.txt`, `machine-learning-supervisionato.txt` |
| **D4 – Architettura e Sistemi Operativi** | 75 | Processori, pipeline RISC, memoria cache, kernel, scheduling dei processi, paginazione e file system | `architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `sistemi_operativi_kernel.txt` |
| **D5 – Reti e Sicurezza Informatica** | 75 | Crittografia simmetrica e asimmetrica, firme digitali, hash, protocolli TCP/IP, routing ed edge computing | `CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `protocolli_rete_tcp_ip.txt` |
| **D6 – Spazio, Energia e Ambiente** | 75 | Esplorazione di Marte, sonde spaziali, telerilevamento satellitare, energie rinnovabili (eolico, solare, idroelettrico) | `esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `energia_rinnovabile_italia.txt` |
| **D7 – Storia, Filosofia e Letteratura** | 75 | Letteratura italiana dell'Ottocento e Novecento, poetica dantesca, filologia, filosofia e storiografia | `letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `poetica_dante_commedia_allegoria.txt` |
| **D8 – Storia dell'Arte e Musica** | 75 | Arte rinascimentale e barocca, tecniche di restauro pittorico, forme musicali classiche e acustica | `rinascimento-artistico-firenze.txt`, `restauro-dipinti-riflettografia-infrarossa-pentimenti.txt`, `musica_classica_sinfonie.txt` |

Nella creazione dei 600 file e nella definizione dei documenti rilevanti per ciascuna query (*ground truth*) sono stati introdotti quattro aspetti specifici per mettere alla prova il sistema:
1. **Varietà nei nomi dei file**: i file pertinenti a uno stesso argomento usano convenzioni diverse (*kebab-case*, *snake_case*, spazi e *CamelCase*).
2. **Singolari, plurali ed elisioni nei testi**: i documenti alternano forme singolari e plurali (`"indice invertito"` / `"indici invertiti"`, `"sistema operativo"` / `"sistemi operativi"`, `"energia rinnovabile"` / `"energie rinnovabili"`) e parole apostrofate (`"L'architettura"`).
3. **Uso di sinonimi e termini derivati**: alcuni documenti pertinenti a una ricerca non usano la parola esatta della query ma un sinonimo o una variante derivata: ad esempio `pianeta_rosso_esobiologia.txt` si riferisce a Marte chiamandolo `"Pianeta Rosso"`; `database_relazionali_sql.txt` usa `"database"` invece di `"basi di dati"`; `struttura_dizionario_termini.txt` usa `"posting list"` invece di `"indice invertito"`; `firma_digitale_rsa.txt` usa l'aggettivo `"crittografici"` invece del sostantivo `"crittografia"`; `catalogo_metadati_data_governance.txt` parla di `"patrimonio informativo"` invece di `"architettura dati"`.
4. **Termini presenti in più domini (polisemia) e superamento dei 10 risultati**: parole comuni come `"architettura"`, `"dati"` o `"energia"` compaiono anche in domini diversi da quello cercato (es. `"l'architettura morale... l'architettura simmetrica"` nella Divina Commedia in `poetica_dante_commedia_allegoria.txt`, `"l'architettura di Von Neumann"` nei calcolatori, `"dati spettrometrici"` nel restauro artistico, `"dati satellitari"` nello spazio, `"dati archivistici"` nella storia, `"energia cinetica orbitale"` nelle sonde spaziali). In questo modo, per la Query 11 (`contenuto architettura dati`) l'indice restituisce **19 documenti totali**, superando il limite `topN = 10` di `ConsoleSearcher`.

### 3.2 Analisi dei Tempi di Indicizzazione (`DefaultCodec` vs `SimpleTextCodec`)

I tempi di indicizzazione sono stati misurati sia eseguendo a freddo `FileIndexer.main` sull'intero corpus di 600 file, sia misurando in `LuceneSearchTest.java` il tempo di indicizzazione al crescere del numero di documenti $N \in \{50, 100, 200, 400, 600\}$ (dopo una breve fase di *warm-up* della JVM), confrontando il Codec binario predefinito di Lucene con `SimpleTextCodec`.

- **Esecuzione completa da riga di comando (`FileIndexer.main`, 600 file, 169.081 byte)**: il tempo complessivo rilevato (incluso il caricamento iniziale delle classi Lucene e l'apertura dei 600 file su disco) varia tra **951 ms e 3.450 ms** a seconda dello stato della cache del file system del sistema operativo (contro i ~490 ms richiesti quando il corpus conteneva solo 15 file).
- **Scalabilità al crescere di $N$ (50–600 documenti)**: la Tabella 2 riporta i tempi netti di indicizzazione e `commit()` a JVM calda.

| Numero di Documenti ($N$) | Dimensione Totale (Byte) | Tempo `DefaultCodec` (ms) | Tempo `SimpleTextCodec` (ms) | File Segmento Generati (`SimpleTextCodec`) |
|:---:|:---:|:---:|:---:|:---|
| **50 file** | ~14.100 B | 205 ms | 152 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **100 file** | ~28.200 B | 327 ms | 148 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **200 file** | ~56.400 B | 289 ms | 237 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **400 file** | ~112.700 B | 409 ms | 297 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **600 file** | **169.081 B** | **495 ms** | **351 ms** | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |

Passando da 50 a 600 documenti ($12\times$), il tempo di indicizzazione a caldo cresce in modo contenuto da **152 ms a 351 ms** con `SimpleTextCodec` e da **205 ms a 495 ms** con `DefaultCodec` (meno di 1 ms per file). Poiché l'intero corpus di 600 file occupa circa 169 KB, i dati rientrano interamente nel buffer RAM predefinito di `IndexWriter` (16 MB), producendo al momento del `commit()` un unico segmento `_0`. Si nota inoltre che su volumi di dati contenuti `SimpleTextCodec` impiega leggermente meno tempo in fase di scrittura rispetto al codec binario di default perché scrive un unico file `.pst` testuale per le *posting list* senza eseguire algoritmi di compressione binaria (come LZ4) né sincronizzare decine di file binari separati; naturalmente, su indici di grandi dimensioni in produzione, `SimpleTextCodec` risulterebbe molto più lento in ricerca e occuperebbe molto più spazio su disco, motivo per cui viene utilizzato solo a scopo didattico e di ispezione dell'indice.

---

## 4. Valutazione Sperimentale e Analisi dei Risultati

### 4.1 Metriche di Valutazione Utilizzate

Per valutare la qualità dei risultati restituiti dal sistema sui 600 documenti, per ciascuna delle query di test $q \in \{Q_1, \dots, Q_{11}\}$ (mentre $Q_{12}$ verifica il comportamento su sole stopword) è stato definito l'insieme dei documenti rilevanti del corpus $R_q$ (*ground truth*) e sono stati misurati sia l'insieme $A_q$ dei risultati mostrati all'utente da `searcher.search(query, 10)` (valutazione sui primi 10 risultati, nota in letteratura come *Precision/Recall at 10*), sia l'insieme $A_{q,\text{tot}}$ di tutti i documenti dell'indice che soddisfano la query senza il limite dei 10 risultati (`searcher.search(query, 600)`). Le metriche calcolate sono:

- **Precision at 10 ($P@10$)**: frazione di documenti rilevanti tra quelli restituiti all'utente (fino a $k = 10$):
  $$P@10 = \frac{|R_q \cap A_q|}{|A_q|}$$
- **Recall at 10 ($R@10$) e Recall Totale ($R_{\text{tot}}$)**: frazione dei documenti rilevanti del corpus recuperati rispettivamente entro i primi 10 risultati ed entro l'intera lista non troncata:
  $$R@10 = \frac{|R_q \cap A_q|}{|R_q|}, \qquad R_{\text{tot}} = \frac{|R_q \cap A_{q,\text{tot}}|}{|R_q|}$$
- **$F_1$-score**: media armonica tra Precision e Recall ($F_1 = \frac{2 \cdot P@10 \cdot R@10}{P@10 + R@10}$).
- **Average Precision ($AP$) e Mean Average Precision ($MAP$)**: media della precisione $P@k$ calcolata ad ogni posizione $k$ della graduatoria in cui compare un documento rilevante, premiando le configurazioni che posizionano i documenti pertinenti ai primi posti della classifica:
  $$AP_q = \frac{1}{|R_q|} \sum_{k=1}^{|A_q|} P@k \cdot \text{rel}(k), \qquad MAP = \frac{1}{|Q|} \sum_{q \in Q} AP_q$$

---

### 4.2 Query Sottomesse al Sistema e Risultati Ottenuti

Di seguito sono riportate le 12 query eseguite sul corpus di 600 documenti con i relativi punteggi `BM25Similarity` e il commento dei risultati.

#### A. Interrogazioni sul campo `nome`

- **Query 1:** `nome marte` $\rightarrow$ *Query Lucene:* `nome:marte`  
  *Documenti rilevanti nel corpus ($|R_1| = 4$):* `atmosfera-marziana-marte.txt`, `esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `pianeta_rosso_esobiologia.txt`.  
  *Risultati restituiti (3):*
  ```text
  1. atmosfera-marziana-marte.txt    (score: 2.7752)
  2. esplorazione-spaziale-marte.txt (score: 2.7752)
  3. missione_rover_marte.txt        (score: 2.7752)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.750$, $F_1 = 0.857$, $AP = 0.750$.  
  *Commento:* Il filtro `wordDelimiterGraph` separa sia i trattini (`-`) sia gli underscore (`_`). I tre file ottengono lo stesso punteggio BM25 (`2.7752`) perché il termine `marte` compare una volta in ciascun nome e i tre nomi hanno la stessa lunghezza (3 token). Il quarto file rilevante (`pianeta_rosso_esobiologia.txt`) non viene recuperato perché nel nome usa il sinonimo `"pianeta_rosso"`.

- **Query 2:** `nome "letteratura italiana"` $\rightarrow$ *Query Lucene:* `nome:"letteratura italiana"`  
  *Documenti rilevanti nel corpus ($|R_2| = 3$):* `letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `romanzo_storico_italiano.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. letteratura italiana novecento.txt (score: 5.7318)
  2. letteratura-italiana-ottocento.txt (score: 5.7318)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.667$, $F_1 = 0.800$, $AP = 0.667$.  
  *Commento:* La `PhraseQuery` verifica che i token `letteratura` (pos 1) e `italiana` (pos 2) siano adiacenti sia quando sono separati da spazi sia quando sono separati da trattini. Il file `romanzo_storico_italiano.txt` resta escluso perché non contiene la frase `"letteratura italiana"`.

- **Query 3:** `nome "basi di dati"` $\rightarrow$ *Query Lucene:* `nome:"basi di dati"`  
  *Documenti rilevanti nel corpus ($|R_3| = 3$):* `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `database_relazionali_sql.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. basi-di-dati-relazionali.txt (score: 6.9975)
  2. basi_di_dati_distribuite.txt (score: 6.9975)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.667$, $F_1 = 0.800$, $AP = 0.667$.  
  *Commento:* Conferma l'utilità di non rimuovere le stopword sul campo `nome`: la preposizione `"di"` viene mantenuta in posizione 2 tra `"basi"` e `"dati"`. Il terzo file rilevante (`database_relazionali_sql.txt`) non viene trovato perché utilizza il termine inglese `"database"`.

- **Query 4:** `nome neurali deep` $\rightarrow$ *Query Lucene:* `nome:neurali nome:deep`  
  *Documenti rilevanti nel corpus ($|R_4| = 3$):* `RetiNeurali_DeepLearning.txt`, `DeepLearning_Trasformatori.txt`, `ModelliNeurali_VisioneArtificiale.txt`.  
  *Risultati restituiti (3):*
  ```text
  1. RetiNeurali_DeepLearning.txt          (score: 5.3775)
  2. DeepLearning_Trasformatori.txt        (score: 2.9567)
  3. ModelliNeurali_VisioneArtificiale.txt (score: 2.6888)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 1.000$, $F_1 = 1.000$, $AP = 1.000$.  
  *Commento:* Avendo posizionato `wordDelimiterGraph` prima di `lowercase`, i nomi in *CamelCase* vengono spezzati correttamente. `RetiNeurali_DeepLearning.txt` è al primo posto (`5.3775`) perché contiene entrambi i termini cercati (`neurali` e `deep`), mentre `DeepLearning_Trasformatori.txt` (`2.9567`, 3 token totali) precede `ModelliNeurali_VisioneArtificiale.txt` (`2.6888`, 4 token totali) poiché BM25 premia il campo più corto a parità di frequenza.

#### B. Interrogazioni sul campo `contenuto`

- **Query 5:** `contenuto "indice invertito"` $\rightarrow$ *Query Lucene:* `contenuto:"indic invertit"`  
  *Documenti rilevanti nel corpus ($|R_5| = 3$):* `indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. indice_invertito_lucene.txt      (score: 4.2849)
  2. compressione_liste_invertite.txt (score: 3.8797)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.667$, $F_1 = 0.800$, $AP = 0.667$.  
  *Commento:* Mostra il vantaggio dello stemming di `ItalianAnalyzer`: cercando al singolare `"indice invertito"`, viene recuperato anche `compressione_liste_invertite.txt` che nel testo contiene solo il plurale `"indici invertiti"` (entrambi ridotti a `"indic invertit"`). Il terzo file rilevante (`struttura_dizionario_termini.txt`) usa invece il termine `"posting list"` e non viene estratto.

- **Query 6:** `contenuto "sistema operativo"` $\rightarrow$ *Query Lucene:* `contenuto:"sistem operativ"`  
  *Documenti rilevanti nel corpus ($|R_6| = 4$):* `sistemi_operativi_kernel.txt`, `gestione_processi_scheduler.txt`, `memoria_virtuale_paginazione.txt`, `file_system_journaling.txt`.  
  *Risultati restituiti (3):*
  ```text
  1. sistemi_operativi_kernel.txt     (score: 3.2274)
  2. gestione_processi_scheduler.txt  (score: 2.8777)
  3. memoria_virtuale_paginazione.txt (score: 2.7126)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.750$, $F_1 = 0.857$, $AP = 0.750$.  
  *Commento:* Anche qui lo stemming unifica singolare e plurale recuperando i 3 file principali; `file_system_journaling.txt` tratta il file system del sistema operativo ma senza usare esplicitamente la frase `"sistema operativo"`.

- **Query 7:** `contenuto crittografia` $\rightarrow$ *Query Lucene:* `contenuto:crittograf`  
  *Documenti rilevanti nel corpus ($|R_7| = 4$):* `CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `scambio_chiavi_diffie_hellman.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. CrittografiaModerna.txt    (score: 3.2583)
  2. cifrari_simmetrici_aes.txt (score: 2.4013)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.500$, $F_1 = 0.667$, $AP = 0.500$.  
  *Commento:* `CrittografiaModerna.txt` ha uno score maggiore (`3.2583`) perché contiene due occorrenze del termine contro l'unica occorrenza in `cifrari_simmetrici_aes.txt` (`2.4013`). Il file `firma_digitale_rsa.txt` non viene recuperato perché contiene l'aggettivo `"crittografici"`, che `ItalianLightStemFilter` riduce a `crittografic` (diverso da `crittograf`).

- **Query 8:** `contenuto energie rinnovabili` $\rightarrow$ *Query Lucene:* `contenuto:energ contenuto:rinnovabil`  
  *Documenti rilevanti nel corpus ($|R_8| = 4$):* `energia_rinnovabile_italia.txt`, `parchi_eolici_offshore.txt`, `impianti_fotovoltaici_solari.txt`, `centrali_idroelettriche_alpine.txt`.  
  *Risultati restituiti (5):*
  ```text
  1. energia_rinnovabile_italia.txt                (score: 4.5355) [Rilevante]
  2. parchi_eolici_offshore.txt                    (score: 4.3098) [Rilevante]
  3. impianti_fotovoltaici_solari.txt              (score: 4.1766) [Rilevante]
  4. rientro_atmosferico_scudo_termico_ablativo.txt (score: 2.0893) [Non rilevante]
  5. propulsione_ionica_sonde.txt                  (score: 1.9621) [Non rilevante]
  ```
  *Metriche:* $P@10 = 3/5 = 0.600$, $R@10 = 0.750$, $F_1 = 0.667$, $AP = 0.750$.  
  *Commento:* Essendo una query disgiuntiva (`OR` di default in Lucene), oltre ai 3 file rilevanti sulle energie rinnovabili (che contengono entrambi i termini e superano lo score `4.17`) vengono estratti in 4ª e 5ª posizione due file di ambito spaziale che contengono la sola parola `"energia"` (`"energia cinetica orbitale"` ed `"energia solare della sonda"`). Anche se $P@10$ scende a $0.600$, BM25 posiziona tutti i documenti rilevanti nei primi tre posti con uno score doppio rispetto ai due falsi positivi ($AP = 0.750$).

- **Query 9:** `contenuto +apprendimento +modello` $\rightarrow$ *Query Lucene:* `+contenuto:apprendiment +contenuto:modell`  
  *Documenti rilevanti nel corpus ($|R_9| = 4$):* `regressione_lineare_logistica.txt`, `machine-learning-supervisionato.txt`, `alberi_decisione_random_forest.txt`, `clustering_non_supervisionato.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. regressione_lineare_logistica.txt   (score: 3.7384)
  2. machine-learning-supervisionato.txt (score: 2.7318)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.500$, $F_1 = 0.667$, $AP = 0.500$.  
  *Commento:* L'operatore booleano obbligatorio `+` (`MUST`) richiede la presenza simultanea di entrambi i termini: ciò evita di estrarre documenti di altri domini che contengono solo la parola `"modello"` ($P@10 = 1.000$), ma esclude i due file sul Machine Learning che non usano contemporaneamente entrambi i vocaboli ($R@10 = 0.500$).

- **Query 10:** `contenuto +architettura +memoria` $\rightarrow$ *Query Lucene:* `+contenuto:architettur +contenuto:memor`  
  *Documenti rilevanti nel corpus ($|R_{10}| = 4$):* `architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `pipeline_istruzioni_risc.txt`, `coerenza_cache_protocollo_mesi_multiprocessore.txt`.  
  *Risultati restituiti (2):*
  ```text
  1. architettura_calcolatori.txt   (score: 3.4007)
  2. gerarchia_cache_processore.txt (score: 2.8676)
  ```
  *Metriche:* $P@10 = 1.000$, $R@10 = 0.500$, $F_1 = 0.667$, $AP = 0.500$.  
  *Commento:* Verifica che `ElisionFilter` rimuova l'apostrofo iniziale in `"L'architettura"` e che l'intersezione `+` selezioni solo i documenti che parlano sia di architettura sia di memoria.

- **Query 11:** `contenuto architettura dati` $\rightarrow$ *Query Lucene:* `contenuto:architettur contenuto:dati`  
  *Documenti rilevanti nel corpus ($|R_{11}| = 8$):* `data_lake_cloud_storage.txt`, `data_warehouse_olap.txt`, `IngegneriaDeiDati_Pipeline.txt`, `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `federazione_sorgenti_wrapper_mediatori.txt`, `indice_invertito_lucene.txt`, `catalogo_metadati_data_governance.txt`.  
  *Risultati restituiti (10 mostrati da `TopDocs(10)` su 19 totali nell'indice):*
  ```text
  1.  data_lake_cloud_storage.txt                    (score: 3.3291) [Rilevante]
  2.  data_warehouse_olap.txt                        (score: 3.1816) [Rilevante]
  3.  IngegneriaDeiDati_Pipeline.txt                 (score: 3.1394) [Rilevante]
  4.  architettura_calcolatori.txt                   (score: 2.8943) [Non rilevante]
  5.  poetica_dante_commedia_allegoria.txt           (score: 2.8853) [Non rilevante]
  6.  basi-di-dati-relazionali.txt                   (score: 2.6545) [Rilevante]
  7.  basi_di_dati_distribuite.txt                   (score: 2.0210) [Rilevante]
  8.  federazione_sorgenti_wrapper_mediatori.txt     (score: 1.8935) [Rilevante]
  9.  ModelliNeurali_VisioneArtificiale.txt          (score: 1.8311) [Non rilevante]
  10. gerarchia_cache_processore.txt                 (score: 1.6664) [Non rilevante]
  ```
  *Metriche:* $P@10 = 6/10 = 0.600$, $R@10 = 6/8 = 0.750$, $R_{\text{tot}} = 7/8 = 0.875$, $F_1 = 0.667$, $AP = 0.641$.  
  *Commento:* Su questa query emergono tre aspetti importanti:
  1. `ItalianLightStemFilter` riduce `"architettura"` (12 lettere) a `architettur`, mentre lascia invariato `"dati"` (4 lettere) perché non tronca le parole con meno di 6 caratteri.
  2. Ai primi tre posti compaiono i 3 documenti di Data Engineering che contengono entrambi i termini della query ($P@3 = 1.000$). Al 5° posto compare il file letterario `poetica_dante_commedia_allegoria.txt` (`2.8853`), che non contiene `"dati"` ma ripete 4 volte la parola `"architettura"`: grazie alla saturazione della frequenza di BM25 ($k_1 = 1.2$), la ripetizione di una sola parola non basta a superare i documenti che contengono entrambi i termini cercati.
  3. Nell'indice ci sono in tutto **19 documenti** che contengono almeno uno dei due termini: poiché 12 documenti di altri domini (hardware, letteratura, AI, spazio, arte, storia, musica, reti) contengono `"architettura"` o `"dati"`, il documento rilevante `indice_invertito_lucene.txt` (`score: 1.1622`, che contiene una sola occorrenza di `"dati"`) scivola in **19ª posizione**, restando escluso dai primi 10 risultati e facendo scendere la Recall da $R_{\text{tot}} = 0.875$ a $R@10 = 0.750$.

- **Query 12:** `contenuto il dello nella` $\rightarrow$ *Query Lucene:* *(vuota)*  
  *Risultato:* `Risultati trovati: 0`  
  *Commento:* Tutte e tre le parole sono stopword italiane e vengono rimosse da `StopFilter` di `ItalianAnalyzer`, producendo una query vuota che restituisce correttamente 0 documenti.

---

### 4.3 Riepilogo delle Metriche e Confronto tra Configurazioni

La Tabella 3 riassume i risultati ottenuti sulle query $Q_1 \dots Q_{11}$ dalla configurazione adottata (`PerFieldAnalyzerWrapper` con `CustomAnalyzer` + `ItalianAnalyzer` e `BM25Similarity`).

| Query | Campo | Sintassi Query | $\|R_q\|$ | $\|A\|$ | $\|A_{\text{tot}}\|$ | $TP$ | $TP_{\text{tot}}$ | $P@10$ | $R@10$ | $R_{\text{tot}}$ | $F_1$ | $AP$ |
|:---:|:---:|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Q1** | `nome` | `marte` | 4 | 3 | 3 | 3 | 3 | 1.000 | 0.750 | 0.750 | 0.857 | 0.750 |
| **Q2** | `nome` | `"letteratura italiana"` | 3 | 2 | 2 | 2 | 2 | 1.000 | 0.667 | 0.667 | 0.800 | 0.667 |
| **Q3** | `nome` | `"basi di dati"` | 3 | 2 | 2 | 2 | 2 | 1.000 | 0.667 | 0.667 | 0.800 | 0.667 |
| **Q4** | `nome` | `neurali deep` | 3 | 3 | 3 | 3 | 3 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| **Q5** | `contenuto` | `"indice invertito"` | 3 | 2 | 2 | 2 | 2 | 1.000 | 0.667 | 0.667 | 0.800 | 0.667 |
| **Q6** | `contenuto` | `"sistema operativo"` | 4 | 3 | 3 | 3 | 3 | 1.000 | 0.750 | 0.750 | 0.857 | 0.750 |
| **Q7** | `contenuto` | `crittografia` | 4 | 2 | 2 | 2 | 2 | 1.000 | 0.500 | 0.500 | 0.667 | 0.500 |
| **Q8** | `contenuto` | `energie rinnovabili` | 4 | 5 | 5 | 3 | 3 | 0.600 | 0.750 | 0.750 | 0.667 | 0.750 |
| **Q9** | `contenuto` | `+apprendimento +modello` | 4 | 2 | 2 | 2 | 2 | 1.000 | 0.500 | 0.500 | 0.667 | 0.500 |
| **Q10** | `contenuto` | `+architettura +memoria` | 4 | 2 | 2 | 2 | 2 | 1.000 | 0.500 | 0.500 | 0.667 | 0.500 |
| **Q11** | `contenuto` | `architettura dati` | 8 | 10 | 19 | 6 | 7 | 0.600 | 0.750 | 0.875 | 0.667 | 0.641 |
| **Media** | — | **Media (Q1–Q11) / MAP** | **44** | **36** | **45** | **30** | **31** | **0.927** | **0.682** | **0.693** | **0.768** | **0.672** |

Per verificare sperimentalmente quanto incidano le scelte progettuali effettuate, in `LuceneSearchTest.java` i 600 documenti sono stati indicizzati e interrogati anche sostituendo `BM25Similarity` con la funzione TF-IDF classica di Lucene (`ClassicSimilarity`, in cui $\text{tf} = \sqrt{\text{freq}}$ senza saturazione asintotica) e sostituendo il `PerFieldAnalyzerWrapper` con `StandardAnalyzer` o `WhitespaceAnalyzer` su entrambi i campi. I risultati del confronto sono riportati nella Tabella 4.

| Configurazione | Analyzer (`nome` / `contenuto`) | `Similarity` | $P@10$ Media | $R@10$ Media | $R_{\text{tot}}$ Media | $F_1$ Media | $MAP$ | Differenza $R@10$ |
|:---|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Config A (Scelta)** | `CustomAnalyzer` / `ItalianAnalyzer` | `BM25Similarity` | **0.927** | **0.682** | **0.693** | **0.768** | **0.672** | — |
| **Config B (TF-IDF)** | `CustomAnalyzer` / `ItalianAnalyzer` | `ClassicSimilarity` | 0.927 | 0.682 | 0.693 | 0.768 | 0.669 | $0.0\%$ ($AP_{Q11}: -4.8\%$) |
| **Config C (Standard)** | `StandardAnalyzer` (entrambi) | `BM25Similarity` | 0.682 | 0.383 | 0.394 | 0.474 | 0.372 | **$-29.9\%$** |
| **Config D (Whitespace)** | `WhitespaceAnalyzer` (entrambi) | `BM25Similarity` | 0.500 | 0.277 | 0.288 | 0.341 | 0.266 | **$-40.5\%$** |

Dal confronto si osserva che:
1. **Confronto tra Analyzer**: usando `StandardAnalyzer` su entrambi i campi (Config C) la Recall media $R@10$ scende dal **68.2% al 38.3%**, perché sul campo `nome` non vengono spezzati gli underscore e il *CamelCase* (la Query 4 restituisce 0 risultati) e sul campo `contenuto` manca lo stemming per unificare singolari e plurali nelle Query 5, 6 e 8. Con `WhitespaceAnalyzer` (Config D) la Recall scende ulteriormente al **27.7%**, poiché anche i trattini, le maiuscole, la punteggiatura e gli apostrofi impediscono il match dei termini.
2. **Confronto tra `BM25Similarity` e `ClassicSimilarity` (TF-IDF)**: a parità di analyzer, i due modelli recuperano gli stessi documenti, ma nella Query 11 (`contenuto architettura dati`) `ClassicSimilarity` fa salire il falso positivo letterario `poetica_dante_commedia_allegoria.txt` (`score: 1.6669`, che ripete 4 volte il solo termine `architettur`) al **3° posto assoluto**, superando il documento rilevante `IngegneriaDeiDati_Pipeline.txt` (`score: 1.6591`, che contiene sia `architettur` sia `dati`). Con `BM25Similarity`, invece, la saturazione della frequenza ($k_1 = 1.2$) limita il peso delle ripetizioni di una singola parola e mantiene `IngegneriaDeiDati_Pipeline.txt` al 3° posto davanti al testo su Dante ($AP_{Q11} = 0.641$ contro $0.610$).

---

### 4.4 Il Problema della Valutazione e del Recupero sulla Recall

L'esperienza sul corpus di 600 documenti mette in evidenza perché valutare e ottenere una **Recall** elevata ($R@10 = |R_q \cap A_q| / |R_q|$) sia molto più complesso rispetto alla Precision:

1. **Difficoltà nel determinare tutti i documenti rilevanti del corpus ($|R_q|$)**: mentre per calcolare la Precision $P@10$ basta leggere i $\le 10$ file restituiti dal sistema e verificare se sono pertinenti, per calcolare la Recall occorre conoscere a priori il denominatore $|R_q|$, cioè **tutti** i documenti rilevanti presenti nell'intera collezione. Su 15 file è immediato controllarli tutti a mano, ma già con 600 file ($600 \times 11 = 6.600$ coppie documento-query) è necessario organizzare il dataset per argomenti in fase di costruzione per non dimenticare documenti pertinenti; su collezioni reali di migliaia o milioni di documenti non strutturati a priori, conoscere l'esatto valore di $|R_q|$ diventa di fatto impossibile.
2. **Taglio dei risultati a `topN = 10` ($R@10 < R_{\text{tot}}$)**: quando il numero di documenti cresce, una query composta da termini frequenti come `architettura dati` (Query 11) trova corrispondenza in 19 file diversi. Poiché 12 file di altri argomenti condividono una delle due parole con frequenza più alta, il documento rilevante `indice_invertito_lucene.txt` finisce in 19ª posizione e viene tagliato fuori dai primi 10 risultati mostrati all'utente, abbassando la Recall da $R_{\text{tot}} = 0.875$ a $R@10 = 0.750$.
3. **Sinonimi e varianti lessicali**: poiché Lucene confronta i token estratti dal testo, i documenti pertinenti che usano sinonimi o espressioni equivalenti (`"Pianeta Rosso"` invece di `"Marte"`, `"database"` invece di `"basi di dati"`, `"posting list"` invece di `"indice invertito"`, `"crittografici"` invece di `"crittografia"`) non hanno token in comune con la query e non possono essere recuperati senza introdurre filtri di sinonimi (`SynonymGraphFilter`).
4. **Compromesso tra operatori booleani (`+`) e Recall**: come mostrato nelle Query 9 e 10, imporre che tutti i termini siano obbligatori con `+` porta la Precision al 100%, ma dimezza la Recall ($0.500$) perché esclude i documenti pertinenti in cui compare uno solo dei termini cercati.

---

## 5. Conclusioni

In questo lavoro è stato progettato, implementato e valutato un sistema completo di indicizzazione e ricerca testuale basato su Apache Lucene (`v9.12.0`). L'organizzazione di ciascun file in due campi distinti (`nome` con `Field.Store.YES` e `contenuto` con `Field.Store.NO`) gestiti da `PerFieldAnalyzerWrapper` si è dimostrata determinante per l'efficacia del sistema: l'unione di un `CustomAnalyzer` (`WhitespaceTokenizer` + `WordDelimiterGraphFilter` prima di `LowerCaseFilter`) per la segmentazione di trattini, underscore e *CamelCase* nei nomi dei file e di `ItalianAnalyzer` per la gestione di elisioni, stopword e stemming nel testo italiano consente di raggiungere una **Precision media ($P@10$) del 92.7%**, una **Recall media ($R@10$) del 68.2%** ($R_{\text{tot}} = 69.3\%$), un **$F_1$-score del 76.8%** e una **MAP di 0.672**, migliorando la Recall di circa 30–40 punti percentuali rispetto a configurazioni basate su `StandardAnalyzer` (38.3%) e `WhitespaceAnalyzer` (27.7%).

L'ampliamento del corpus a **600 documenti `.txt`** (169.081 byte distribuiti su 8 aree tematiche) ha permesso inoltre di osservare aspetti che non emergono su collezioni di poche decine di file. Sul piano delle prestazioni, i tempi di indicizzazione a caldo crescono in modo contenuto fino a **351 ms** con `SimpleTextCodec` e **495 ms** con `DefaultCodec` (restando ampiamente all'interno di un singolo segmento in RAM), evidenziando come `SimpleTextCodec` sia pratico per ispezionare dizionario e *posting list* a scopo didattico pur restando inadatto a volumi produttivi su larga scala. Sul piano del ranking, il confronto con `ClassicSimilarity` (TF-IDF) ha confermato il vantaggio della saturazione non lineare di `BM25Similarity`, capace di impedire che la ripetizione multipla di una singola parola polisemica scavalchi i documenti che soddisfano entrambi i termini dell'interrogazione.

Infine, la valutazione su 600 file ha messo in luce i limiti strutturali dei sistemi puramente lessicali rispetto alla **Recall**: oltre alla difficoltà pratica di costruire e verificare i giudizi di rilevanza completi ($|R_q|$) al crescere della collezione, il limite fisso dei primi 10 risultati (`topN = 10`) esclude documenti pertinenti quando molti file di altri domini condividono termini generici (come osservato nella Query 11, dove la Recall scende da $R_{\text{tot}} = 0.875$ a $R@10 = 0.750$), mentre sinonimi, perifrasi e vincoli booleani congiuntivi riducono il richiamo complessivo. Come naturale estensione futura, l'integrazione di un dizionario di sinonimi tramite `SynonymGraphFilter` permetterebbe di colmare il divario lessicale tra query e documenti mantenendo inalterata l'elevata Precision già ottenuta.
