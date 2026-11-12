# Indicizzazione e Recupero dell'Informazione su Collezioni Documentali mediante Apache Lucene: Architettura, Analisi Lessicale e Valutazione Sperimentale

**Matteo Cerretani**  
Dipartimento di Ingegneria – Corso di Ingegneria dei Dati  
Università degli Studi Roma Tre  
Email: `mat.cerretani@stud.uniroma3.it` – `matteo.cerretani@gmail.com`  
Repository del progetto: https://github.com/TeoCerretani/IDD-Homework3

---

**Sommario** — *Il presente lavoro descrive la progettazione, l'implementazione e la valutazione sperimentale di un sistema di Information Retrieval basato su Apache Lucene (v9.12.0) per l'indicizzazione e l'interrogazione full-text di file testuali (`.txt`) memorizzati su file system locale. Ciascun documento è modellato attraverso due campi distinti, corrispondenti al nome del file (`nome`) e al suo corpo testuale (`contenuto`), indicizzati in posting list separate mediante pipeline di analisi lessicale differenziate gestite da `PerFieldAnalyzerWrapper`. Nello specifico, viene progettato un `CustomAnalyzer` basato su `WhitespaceTokenizer`, `WordDelimiterGraphFilter` e `LowerCaseFilter` per la scomposizione sub-lessicale dei nomi dei file, affiancato da `ItalianAnalyzer` per il trattamento morfologico del testo in lingua italiana. Per condurre una valutazione statisticamente significativa dei tempi di indicizzazione e delle metriche di recupero, è stato costruito un corpus controllato di 200 documenti (63.781 byte) articolato in 8 domini tematici bilanciati da 25 documenti ciascuno, corredato di giudizi di rilevanza (ground truth). L'analisi sperimentale esamina la scalabilità dei tempi di indicizzazione al crescere della collezione confrontando il Codec binario predefinito con `SimpleTextCodec`, valuta il sistema su 12 interrogazioni tramite Precision ($P@10$), Recall ($R@10$ e $R_{\text{tot}}$), $F_1$-score, Average Precision ($AP$) e Mean Average Precision ($MAP$), e presenta uno studio di ablazione che mette a confronto `BM25Similarity` con `ClassicSimilarity` (TF-IDF) e gli analyzer specializzati con le baseline `StandardAnalyzer` e `WhitespaceAnalyzer`, discutendo infine le cause strutturali di perdita di Recall nei sistemi bag-of-words.*

---

## 1. Introduzione e Architettura del Sistema

L'obiettivo del sistema realizzato è fornire un'architettura completa di indicizzazione e ricerca full-text su collezioni di file `.txt` residenti in una directory locale. In conformità con il modello dei dati di Apache Lucene, l'unità fondamentale di indicizzazione e recupero è rappresentata dalla classe `Document`, concepita come un insieme di campi tipizzati (`Field`), a ciascuno dei quali corrisponde una **posting list** fisicamente e logicamente distinta all'interno dell'indice invertito.

Ogni file `.txt` della collezione viene rappresentato come un'istanza di `Document` composta da due campi di tipo `TextField` (scelti in luogo di `StringField` affinché i valori non siano indicizzati come un'unica stringa indivisa ma sottoposti al processo di *tokenizzazione* e *analisi* per consentire interrogazioni a livello di singoli termini e frasi):

1. Il campo **`nome`**, contenente il nome del file privato dell'estensione `.txt` (rimossa in `FileIndexer.indexDocs` tramite `file.getName().substring(0, file.getName().length() - 4)`). Tale campo viene sia indicizzato sia memorizzato nell'indice (`Field.Store.YES`), consentendo di rileggere il valore originale privo di estensione a tempo di ricerca tramite `searcher.storedFields().document(scoreDoc.doc).get("nome")` (come verificato direttamente dalle asserzioni JUnit in `LuceneSearchTest`); il suffisso `.txt` viene riaggiunto esclusivamente a scopo di leggibilità in fase di formattazione dell'output su console in `ConsoleSearcher.runQuery` (`System.out.printf("  %d. %s.txt (score: %.4f)%n", ...)`).
2. Il campo **`contenuto`**, contenente il testo integrale del file letto in codifica UTF-8. Tale campo viene analizzato e indicizzato nelle relative *posting list*, ma non viene memorizzato nei campi *stored* dell'indice (`Field.Store.NO`): poiché non è richiesto di ristampare l'intero corpo del documento nei risultati di ricerca, questa scelta evita di duplicare inutilmente il testo grezzo nei file `.fld` dell'indice, riducendo l'occupazione su disco e i tempi di I/O senza alcuna perdita di capacità interrogativa.

L'architettura software, organizzata nel package `it.uniroma3.idd.hw3`, si articola in due componenti principali affiancati da una suite di test automatizzati e benchmark sperimentali JUnit 5 (`LuceneSearchTest.java`):

### 1.1 Pipeline di Indicizzazione (`FileIndexer.java`)

Il componente di indicizzazione configura l'accesso al file system definendo `Path path = Paths.get(indexPath)` e aprendo `Directory directory = FSDirectory.open(path)`. Crea quindi `IndexWriterConfig config = new IndexWriterConfig(perFieldAnalyzer)`, registra il codec testuale diagnostico tramite `config.setCodec(new SimpleTextCodec())` (dal modulo `lucene-codecs`) per serializzare i segmenti dell'indice invertito in formato leggibile, e istanzia `IndexWriter writer = new IndexWriter(directory, config)`. Poiché la struttura dell'indice di Lucene è *append-only* su segmenti immutabili, il programma invoca preliminarmente `writer.deleteAll()` per evitare la duplicazione dei documenti ad esecuzioni successive.

Dal punto di vista dell'architettura interna di Lucene, quando viene invocato `writer.addDocument(doc)` per ciascuno dei 200 file `.txt`:
1. **`DocumentsWriter` e `DWPT`**: la richiesta viene delegata a un `DocumentsWriter`, che gestisce un pool di thread di indicizzazione indipendenti (`DocumentsWriterPerThread`, **DWPT**). Ciascun `DWPT` mantiene un buffer di indicizzazione isolato in memoria RAM e processa il documento attraverso una catena di consumatori specializzati (*consumer chain*).
2. **Estrazione del `TokenStream` e inversione in memoria**: il consumatore `DocFieldProcessor` ispeziona i campi del `Document` e, per ciascun `TextField`, invoca `Field.tokenStream(Analyzer)` passando il `PerFieldAnalyzerWrapper`. Il flusso di token prodotto viene consumato da `FreqProxTermsWriter` (che costruisce in RAM il dizionario dei termini ordinato lessicograficamente e le *posting list* contenenti gli identificativi di documento `docID`, le frequenze `termFreq` e le posizioni `position` per le *phrase query*) e da `StoredFieldsWriter` (che serializza sul buffer i soli campi marcati con `Field.Store.YES`, ossia `nome`).
3. **Differenza tra `flush` e `commit`**: al riempimento del buffer RAM (o all'invocazione del commit), i dati accumulati dai `DWPT` subiscono un'operazione di **`flush`**, che scrive su disco nella `Directory` un nuovo **segmento immutabile** (es. `_0.scf`, `_0.pst`, `_0.fld` con `SimpleTextCodec`), un'unità autonoma che è già un indice invertito completo e ricercabile ma non ancora visibile ai lettori esterni. Successivamente, l'invocazione esplicita di `writer.commit()` forza la sincronizzazione fisica su disco (*fsync*) di tutti i file dei segmenti generati e scrive il nuovo file di punto di commit `segments_N`, rendendo le modifiche atomicamente visibili a qualsiasi `IndexReader` aperto successivamente.

### 1.2 Pipeline di Ricerca (`ConsoleSearcher.java`)

Il componente di ricerca apre l'indice in sola lettura tramite `IndexReader reader = DirectoryReader.open(directory)`, istanzia `IndexSearcher searcher = new IndexSearcher(reader)` e imposta esplicitamente il modello di ranking probabilistico con `searcher.setSimilarity(new BM25Similarity())`. Il programma acquisisce da console stringhe di interrogazione prefissate dal nome del campo (`nome` o `contenuto`), utilizza `QueryParser` (configurato con il medesimo `PerFieldAnalyzerWrapper` impiegato in fase di indicizzazione) per generare l'albero sintattico dell'oggetto `Query` (`TermQuery`, `PhraseQuery` o `BooleanQuery`) e invoca `TopDocs hits = searcher.search(query, 10)`.

Internamente, la chiamata `searcher.search(query, 10)` attiva la seguente catena di esecuzione sui segmenti dell'indice:
1. **`Query` e `Weight`**: l'`IndexSearcher` invoca `query.createWeight(searcher, ScoreMode.TOP_SCORES, 1.0f)` per creare un oggetto `Weight`, che incapsula lo stato dipendente dall'indice (come il calcolo delle statistiche globali **IDF**, *Inverse Document Frequency*, su tutta la collezione tramite `DirectoryReader`) mantenendo l'oggetto `Query` immutabile e riutilizzabile.
2. **`LeafReaderContext` e `Scorer`**: l'`IndexSearcher` itera sulle foglie dell'albero dei segmenti (`reader.leaves()`, ciascuna rappresentata da un `LeafReaderContext` associato a un singolo segmento immutabile $S_i$) e, per ciascun segmento, richiede al `Weight` di istanziare uno **`Scorer`** (`weight.scorer(context)`). Lo `Scorer` scorre in parallelo gli iteratori sulle *posting list* (`PostingsEnum`) dei termini di query e calcola il punteggio di rilevanza di ciascun documento candidato delegando la formula matematica all'`IndexSearcher.getSimilarity()` (`BM25Similarity`).
3. **`LeafCollector` e `TopDocs`**: i documenti qualificati e i relativi punteggi vengono raccolti da un `TopScoreDocCollector` (tramite il relativo `LeafCollector` di segmento), che mantiene una coda di priorità (*min-heap*) limitata ai migliori `topN = 10` risultati. Al termine della scansione di tutti i segmenti, il collettore restituisce l'oggetto `TopDocs`, dal cui array `hits.scoreDocs` (`ScoreDoc[]`, contenente le coppie $\langle\text{docID}, \text{score}\rangle$) il programma recupera i valori memorizzati del campo `nome` tramite `searcher.storedFields().document(scoreDoc.doc)`.

L'esecuzione dei programmi e dei test tramite Apache Maven avviene con i seguenti comandi:

```text
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
mvn test
```

---

## 2. Analisi Lessicale e Progettazione degli Analyzer

In Apache Lucene un `Analyzer` definisce la politica di estrazione dei termini di indice dal testo attraverso un'istanza di `TokenStream`, costituita dalla composizione sequenziale di un singolo **`Tokenizer`** (che riceve un `Reader` di caratteri e lo segmenta in token elementari) e di una catena ordinata di zero o più **`TokenFilter`** (che modificano, espandono o sopprimono i token). Ogni token che attraversa il `TokenStream` trasporta attributi fondamentali che vengono serializzati nelle *posting list*: la stringa del termine (`CharTermAttribute`), l'incremento di posizione (`PositionIncrementAttribute`, indispensabile per verificare l'adiacenza dei termini nelle `PhraseQuery` e calcolare lo *slop*), e gli offset di inizio e fine nel testo sorgente (`OffsetAttribute`: `start_offset` ed `end_offset`).

Poiché la natura morfologica e sintattica dei **nomi dei file** differisce radicalmente da quella del **contenuto testuale**, l'adozione di un unico analyzer globale produrrebbe gravi anomalie sia in termini di *Precision* sia in termini di *Recall*. Si è pertanto adottato `PerFieldAnalyzerWrapper` per associare a ciascun campo una pipeline dedicata, garantendo la totale coerenza tra indicizzazione (`FileIndexer`) e interrogazione (`ConsoleSearcher`):

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

Le convenzioni di denominazione dei file nei moderni file system sostituiscono frequentemente gli spazi bianchi con separatori sintetici quali il trattino (*kebab-case*, es. `basi-di-dati-relazionali.txt`), l'underscore (*snake_case*, es. `indice_invertito_lucene.txt`), gli spazi liberi (`letteratura italiana novecento.txt`) o le transizioni di cassa tra minuscole e maiuscole (*CamelCase* / *PascalCase*, es. `RetiNeurali_DeepLearning.txt`).

Un'analisi critica degli analyzer standard forniti da Lucene evidenzia perché nessuno di essi sia singolarmente adeguato al campo `nome`:
- **`WhitespaceAnalyzer`**: suddivide il testo esclusivamente sugli spazi bianchi senza applicare normalizzazione *lowercase* né rimozione dei delimitatori intraparola; di conseguenza, il file `basi-di-dati-relazionali` genererebbe un unico token indiviso `basi-di-dati-relazionali`, rendendo impossibile recuperarlo cercando `nome:dati` o `nome:"basi di dati"`, mentre `CrittografiaModerna` non risulterebbe ricercabile in minuscolo.
- **`SimpleAnalyzer`** (`LetterTokenizer` + `LowerCaseFilter`): divide il testo sui caratteri non alfabetici (separando trattini e underscore) e converte in minuscolo, ma elimina i caratteri numerici (es. `x86` in `architettura_set_istruzioni_x86_arm.txt` o `5g` in `reti_cellulari_5g_beamforming.txt`) e non è in grado di spezzare le transizioni *CamelCase*, trasformando `RetiNeurali` nell'unico token `retineurali`.
- **`StandardAnalyzer`**: applica l'algoritmo di segmentazione Unicode UAX#29 seguito da `LowerCaseFilter`. Sebbene separi i trattini (`basi-di-dati` $\to$ `basi`, `di`, `dati`), la specifica UAX#29 considera l'underscore `_` come un connettore interno di parola e non spezza il *CamelCase*: pertanto `basi_di_dati_distribuite` verrebbe indicizzato come un unico token indiviso `basi_di_dati_distribuite` e `RetiNeurali_DeepLearning` come `retineurali_deeplearning`, dimezzando la *Recall* sulle interrogazioni per nome (come confermato sperimentalmente nello studio di ablazione in Sezione 4.4).

Per risolvere simultaneamente tutte queste criticità è stato costruito tramite `CustomAnalyzer.builder()` un analyzer su misura composto da tre stadi ordinati:
1. **`withTokenizer("whitespace")` (`WhitespaceTokenizer`)**: esegue una prima segmentazione conservativa in corrispondenza degli spazi bianchi (gestendo nomi come `letteratura italiana novecento`).
2. **`addTokenFilter("wordDelimiterGraph")` (`WordDelimiterGraphFilter`)**: spezza i token in corrispondenza di qualsiasi delimitatore non alfanumerico (`-`, `_`, `.`), delle transizioni tra lettere e cifre e delle transizioni da minuscolo a maiuscolo (*CamelCase*), incrementando correttamente l'attributo `position` di ciascun sotto-token generato. **L'ordine dei filtri nella catena è determinante**: posizionare `wordDelimiterGraph` *prima* di `lowercase` permette al filtro di rilevare il cambio di cassa in `RetiNeurali_DeepLearning` producendo i 4 token posizionali `Reti` (pos 1), `Neurali` (pos 2), `Deep` (pos 3), `Learning` (pos 4); se `lowercase` venisse applicato prima, l'informazione sulle maiuscole andrebbe persa prima dello split.
3. **`addTokenFilter("lowercase")` (`LowerCaseFilter`)**: normalizza in minuscolo i token già segmentati (`reti`, `neurali`, `deep`, `learning`), garantendo interrogazioni *case-insensitive*.

Sul campo `nome` si è scelto deliberatamente di **non applicare né `StopFilter` né filtri di stemming**: nei nomi dei file (stringhe brevi di 2–5 parole) le preposizioni articolate e semplici fanno parte integrante di locuzioni tecniche esatte (es. `"di"` in `nome "basi di dati"`, dove l'eliminazione della stopword altererebbe la corrispondenza posizionale), mentre lo stemming altererebbe identificatori propri o acronimi tecnici. L'estensione `.txt`, essendo invariante su tutta la collezione ($\text{df} = N = 200$), ha potere discriminante nullo ($\text{IDF} \approx 0$) e viene rimossa prima dell'indicizzazione.

### 2.2 Scelta dell'Analyzer per il campo `contenuto`

Il campo `contenuto` ospita prosa tecnico-scientifica in lingua italiana, caratterizzata da una ricca morfologia flessiva, frequenti elisioni apostrofate e un'elevata densità di parole funzionali. Tra gli analyzer pre-costruiti del modulo `lucene-analysis-common` è stato selezionato **`ItalianAnalyzer`**, la cui pipeline interna esegue quattro trasformazioni linguistiche essenziali:
1. **`StandardTokenizer` e `ElisionFilter`**: segmentano il testo eliminando la punteggiatura e rimuovono i prefissi apostrofati tipici dell'italiano (`l'`, `un'`, `dell'`, `nell'`, `all'`, `sull'`, `d'`), trasformando espressioni quali `"L'architettura"` in `architettura` e `"dell'analisi"` in `analisi`. Senza `ElisionFilter` (ad esempio usando `WhitespaceAnalyzer`), la parola `"L'architettura"` genererebbe il token `l'architettura`, impedendo il match con la query `contenuto architettura`.
2. **`LowerCaseFilter`**: uniforma tutti i caratteri in minuscolo.
3. **`StopFilter` (Stopword italiane)**: rimuove le parole funzionali ad altissima frequenza (articoli, preposizioni, congiunzioni, ausiliari quali `il`, `lo`, `la`, `dei`, `dello`, `nella`, `che`, `per`, `con`, `sono`), evitando di gonfiare l'indice con *posting list* lunghe quanto l'intera collezione e impedendo che termini privi di contenuto semantico introducano rumore nel calcolo dello score.
4. **`ItalianLightStemFilter` (Stemming morfologico leggero)**: riconduce le varianti flessive (maschile/femminile, singolare/plurale) alla medesima radice lessicale, riducendo ad esempio sia il singolare `"indice invertito"` sia il plurale `"indici invertiti"` alla sequenza di radici `"indic invertit"`, e `"energia rinnovabile"` / `"energie rinnovabili"` ai token `energ` e `rinnovabil`. Come analizzato in dettaglio nella Sezione 4.5, `ItalianLightStemFilter` adotta un approccio *light* che rimuove i suffissi flessivi preservando quelli derivazionali e non tronca i termini più corti di 6 caratteri (ad esempio `"dati"`, di 4 caratteri, rimane invariato `dati`), bilanciando l'aumento di *Recall* con la tutela della *Precision*.

---

## 3. Costruzione del Dataset di Valutazione e Analisi dei Tempi di Indicizzazione

### 3.1 Metodologia di Costruzione del Corpus di 200 Documenti e della Ground Truth

Uno dei problemi metodologici centrali nella valutazione sperimentale dei sistemi di Information Retrieval consiste nel dimensionamento e nella strutturazione della collezione di test (*test collection*). Collezioni giocattolo di 10–15 file non consentono né di osservare il comportamento reale dell'indicizzatore oltre la fase di bootstrap della JVM, né di valutare in modo significativo la **Recall** e la selettività del ranking, poiché il numero totale di documenti contenenti un termine risulta quasi sempre inferiore alla soglia di taglio `topN = 10`.

Per superare tale limite e condurre una valutazione conforme al paradigma **Cranfield / TREC** (basato sulla tripla $\langle \text{Collezione } C, \text{Insieme di bisogni informativi } Q, \text{Giudizi di rilevanza } R \rangle$), è stato progettato e costruito nella directory `corpus/` un dataset di **200 documenti `.txt` in lingua italiana**, per una dimensione complessiva di **63.781 byte** (media di ~319 byte e ~42 parole per documento).

La collezione è stata strutturata secondo un disegno fattoriale bilanciato in **8 domini tematici da 25 documenti ciascuno** ($8 \times 25 = 200$ file):
1. **D1 – Basi di Dati e Ingegneria dei Dati (25 file)**: modelli relazionali e SQL (`basi-di-dati-relazionali.txt`, `database_relazionali_sql.txt`), sistemi distribuiti e NoSQL (`basi_di_dati_distribuite.txt`, `partizionamento_sharding_orizzontale.txt`, `teorema_cap_consistenza_eventuale.txt`), pipeline ETL/ELT e architetture analitiche (`IngegneriaDeiDati_Pipeline.txt`, `data_warehouse_olap.txt`, `data_lake_cloud_storage.txt`, `federazione_sorgenti_wrapper_mediatori.txt`, `catalogo_metadati_data_governance.txt`, `integrazione_dati_schema_matching.txt`, `qualita_dati_data_cleaning.txt`, ecc.).
2. **D2 – Information Retrieval e Motori di Ricerca (25 file)**: strutture di indicizzazione (`indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt`, `albero_b_piu_indicizzazione.txt`, `skip_pointer_intersezione_liste.txt`), analisi del testo (`analisi_lessicale_tokenizzazione.txt`, `stemming_lemmatizzazione_italiano.txt`, `espansione_query_sinonimi_tesauro.txt`), modelli di ranking e metriche (`modello_spazio_vettoriale_tfidf.txt`, `ranking_probabilistico_bm25.txt`, `valutazione_precision_recall_map.txt`, `rilevanza_feedback_rocchio.txt`, ecc.).
3. **D3 – Intelligenza Artificiale e Machine Learning (25 file)**: apprendimento supervisionato e non supervisionato (`machine-learning-supervisionato.txt`, `regressione_lineare_logistica.txt`, `alberi_decisione_random_forest.txt`, `clustering_non_supervisionato.txt`, `macchine_vettori_supporto_svm.txt`), architetture neurali (`RetiNeurali_DeepLearning.txt`, `DeepLearning_Trasformatori.txt`, `ModelliNeurali_VisioneArtificiale.txt`, `reti_ricorrenti_lstm_sequenze.txt`, `modelli_generativi_diffusione.txt`, ecc.).
4. **D4 – Architettura dei Calcolatori e Sistemi Operativi (25 file)**: organizzazione del processore e gerarchia di memoria (`architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `pipeline_istruzioni_risc.txt`, `coerenza_cache_protocollo_mesi_multiprocessore.txt`), kernel, processi e memoria virtuale (`sistemi_operativi_kernel.txt`, `gestione_processi_scheduler.txt`, `memoria_virtuale_paginazione.txt`, `file_system_journaling.txt`, `container_isolamento_namespace_cgroups.txt`, ecc.).
5. **D5 – Reti di Calcolatori e Sicurezza Informatica (25 file)**: crittografia e protocolli di sicurezza (`CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `scambio_chiavi_diffie_hellman.txt`, `funzioni_hash_crittografiche_sha3.txt`, `crittografia_post_quantistica_reticoli.txt`), architetture di rete (`protocolli_rete_tcp_ip.txt`, `instradamento_bgp_sistemi_autonomi.txt`, `reti_cellulari_5g_beamforming.txt`, ecc.).
6. **D6 – Scienze Spaziali, Energia e Ambiente (25 file)**: esplorazione marziana e propulsione (`esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `atmosfera-marziana-marte.txt`, `pianeta_rosso_esobiologia.txt`, `propulsione_ionica_sonde.txt`, `rientro_atmosferico_scudo_termico_ablativo.txt`), transizione energetica (`energia_rinnovabile_italia.txt`, `impianti_fotovoltaici_solari.txt`, `parchi_eolici_offshore.txt`, `centrali_idroelettriche_alpine.txt`, `idrogeno_verde_elettrolisi_pem.txt`, ecc.).
7. **D7 – Storia, Filosofia e Letteratura (25 file)**: storia della letteratura italiana (`letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `romanzo_storico_italiano.txt`, `poetica_dante_commedia_allegoria.txt`, `ermetismo_ungaretti_montale_poesia.txt`), filosofia e storia (`filosofia_greca_antica.txt`, `rivoluzione_industriale_europa.txt`, `logica_aristotelica_sillogismo_deduzione.txt`, ecc.).
8. **D8 – Storia dell'Arte e Musica Classica (25 file)**: arte rinascimentale e moderna (`rinascimento-artistico-firenze.txt`, `prospettiva_brunelleschi_cupola.txt`, `barocco_romano_bernini_borromini.txt`), forme musicali e acustica (`musica_classica_sinfonie.txt`, `contrappunto_fuga_bach.txt`, `sintesi_sonora_modulazione_frequenza.txt`, ecc.).

Nella stesura dei 200 documenti e nell'assegnazione dei giudizi di rilevanza binari (*qrels*, $R_q \subset C$) sono stati iniettati in modo controllato **quattro fenomeni linguistici e strutturali** progettati per sollecitare criticamente sia gli `Analyzer` sia la funzione di ranking:
1. **Eterogeneità sintattica dei nomi dei file**: i file rilevanti per una stessa query combinano *kebab-case* (`basi-di-dati-relazionali.txt`), *snake_case* (`basi_di_dati_distribuite.txt`), spazi (`letteratura italiana novecento.txt`) e *PascalCase/CamelCase* (`RetiNeurali_DeepLearning.txt`, `IngegneriaDeiDati_Pipeline.txt`).
2. **Flessioni morfologiche ed elisioni apostrofate**: i testi rilevanti alternano forme singolari e plurali (`"indice invertito"` vs `"indici invertiti"`, `"sistema operativo"` vs `"sistemi operativi"`, `"energia rinnovabile"` vs `"energie rinnovabili"`) ed elisioni (`"L'architettura"`, `"dell'informazione"`).
3. **Sinonimia, perifrasi e derivazione lessicale (*Vocabulary Mismatch*)**: all'interno di ciascun dominio, specifici documenti realmente pertinenti al bisogno informativo trattano il concetto usando sinonimi, prestiti linguistici o derivazioni morfologiche anziché i termini esatti della query: ad esempio `pianeta_rosso_esobiologia.txt` parla di Marte usando la perifrasi `"Pianeta Rosso"`; `database_relazionali_sql.txt` usa `"database"` invece di `"basi di dati"`; `struttura_dizionario_termini.txt` usa `"posting list"` invece di `"indice invertito"`; `firma_digitale_rsa.txt` usa l'aggettivo derivato `"crittografici"` invece del sostantivo `"crittografia"`; `catalogo_metadati_data_governance.txt` usa `"patrimonio informativo"` e `"dizionari aziendali"` invece di `"architettura dati"`.
4. **Polisemia inter-dominio e superamento della soglia di cutoff ($|A_{\text{tot}}| > 10$)**: termini ad alta frequenza come `"architettura"` / `"architetture"`, `"dati"` o `"energia"` compaiono con accezioni differenti anche in altri domini (es. `"L'architettura morale... l'architettura simmetrica delle cantiche... l'architettura simbolica"` nel documento letterario `poetica_dante_commedia_allegoria.txt`, `"l'architettura di Von Neumann"` in `architettura_calcolatori.txt`, o `"l'energia cinetica orbitale"` in `rientro_atmosferico_scudo_termico_ablativo.txt`). In particolare, per la Query 11 (`contenuto architettura dati`) l'indice invertito sui 200 file contiene **15 documenti corrispondenti** ($|A_{\text{tot}}| = 15 > 10$), permettendo di misurare sperimentalmente la perdita di *Recall* dovuta al troncamento `topN = 10`.

### 3.2 Analisi dei Tempi di Indicizzazione e Confronto tra Codec (Default vs `SimpleTextCodec`)

L'ampliamento del corpus a 200 documenti consente di condurre un'analisi rigorosa dei tempi di indicizzazione lungo due direttrici complementari: il costo di avvio a freddo (*cold start*) dell'applicazione e la scalabilità asintotica al crescere di $N$ in funzione del **Codec di serializzazione** (confrontando il Codec binario predefinito di Lucene con il `SimpleTextCodec` introdotto nella Slide 34).

1. **Indicizzazione a freddo (*Cold-Start*) tramite `FileIndexer.main`**:  
   Misurando tramite `System.currentTimeMillis()` l'intero metodo `FileIndexer.indexDocs` su una nuova istanza della JVM (comprensivo del caricamento delle classi del modulo `lucene-core` e `lucene-codecs`, dell'inizializzazione di `MemorySegmentIndexInputProvider`, della costruzione di `CustomAnalyzer` e `ItalianAnalyzer`, della lettura dei 200 file da file system NTFS, dell'inversione nei `DWPT` con `SimpleTextCodec` e del `writer.commit()` finale), si rileva:
   - **Numero di file `.txt` indicizzati:** **200 documenti** (63.781 byte complessivi).
   - **Tempo totale di indicizzazione (*cold-start*, 200 file):** **684 – 809 ms** (confrontato con i ~490 ms richiesti per 15 file e i ~536 ms per 80 file: quadruplicando e poi decuplicando la collezione da 15 a 200 documenti, il tempo totale cresce meno che proporzionalmente poiché oltre 400 ms sono assorbiti dal bootstrap una tantum della JVM e dei componenti di analisi di Lucene).

2. **Benchmark di Scalabilità a caldo ($N \in \{25, 50, 100, 150, 200\}$) e Confronto `DefaultCodec` vs `SimpleTextCodec`**:  
   Nel metodo `testExperimentalBenchmarksAndAblationStudy` di `LuceneSearchTest.java`, dopo una fase preliminare di *warm-up* del compilatore JIT della JVM, sono stati misurati i tempi netti di indicizzazione e commit al crescere del numero di documenti $N$ da 25 a 200, confrontando il Codec binario predefinito (`Lucene99Codec`, che applica compressione binaria *packed ints* e *LZ4* alle *posting list* e ai campi *stored*) con `SimpleTextCodec` (che formatta dizionario, frequenze, posizioni e campi memorizzati in file di testo `.scf`, `.pst`, `.fld` ispezionabili dall'utente per finalità di debugging):

| Dimensione Corpus ($N$) | Volume Dati (Byte) | Tempo `DefaultCodec` (ms) | Tempo `SimpleTextCodec` (ms) | File Segmento Generati (`SimpleTextCodec`) |
|:---:|:---:|:---:|:---:|:---|
| **25 documenti** | ~8.000 B | 74 ms | 40 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **50 documenti** | ~16.000 B | 104 ms | 67 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **100 documenti** | ~31.900 B | 121 ms | 97 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **150 documenti** | ~47.800 B | 175 ms | 93 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |
| **200 documenti** | 63.781 B | 137 ms | 84 ms | `_0.scf`, `_0.pst`, `_0.fld`, `_0.inf`, `_0.si`, `segments_1` |

Dai dati sperimentali emergono due osservazioni tecniche rilevanti:
- Per entrambi i codec, l'indicizzazione di 200 file risiede interamente nel buffer RAM del singolo `DocumentsWriterPerThread` (che ha una soglia di flush predefinita di `16 MB` in `IndexWriterConfig.DEFAULT_RAM_BUFFER_SIZE_MB`), generando un **unico segmento `_0`** al momento del `writer.commit()` con tempi netti nell'ordine di **84–137 ms** (~0,4–0,7 ms per documento, dominati dalle *system call* di apertura e lettura dei 200 file su file system).
- Come evidenziato nella **Slide 34** del corso, `SimpleTextCodec` è progettato esclusivamente per scopi didattici e di debugging (non per ambienti di produzione su scala multi-gigabyte, dove l'assenza di compressione binaria e di strutture di indicizzazione compatte come i *Finite State Transducers* FST in RAM degrada drasticamente le prestazioni di ricerca e lo spazio su disco). Su micro-collezioni di poche decine di kilobyte ($N \le 200$), tuttavia, `SimpleTextCodec` risulta persino lievemente più rapido in scrittura (`84 ms` contro `137 ms` a $N=200$) poiché scrive un singolo file `.pst` testuale sequenziale evitando l'overhead di creazione e sincronizzazione (*fsync*) dei molteplici file binari specializzati (`.tim`, `.tip`, `.doc`, `.pos`, `.fdt`, `.fdx`, `.fdm`, `.nvd`, `.nvm`) generati dal codec di produzione.

---

## 4. Valutazione Sperimentale, Studio di Ablazione e Analisi della Recall

### 4.1 Metodologia e Metriche Formali di Information Retrieval

Il programma `ConsoleSearcher` interpreta la stringa inserita dall'utente estraendo il primo token come campo di destinazione (`nome` o `contenuto`) e delegando la restante espressione a `new QueryParser(campo, perFieldAnalyzer)`. Ciò permette di esprimere interrogazioni disgiuntive a termini liberi (`BooleanClause.Occur.SHOULD`), interrogazioni congiuntive con l'operatore obbligatorio `+` (`BooleanClause.Occur.MUST`), esclusioni con `-` (`BooleanClause.Occur.MUST_NOT`) e ricerche di frasi esatte tra virgolette `"..."` (`PhraseQuery` con `slop = 0`).

Per valutare in modo quantitativo l'efficacia del sistema sul corpus di $N = 200$ documenti, per ciascuna interrogazione informativa $q \in \{Q_1, \dots, Q_{11}\}$ (mentre $Q_{12}$ funge da test di controllo negativo su sole stopword) vengono messi a confronto tre insiemi:
- $R_q \subset C$: l'insieme dei documenti rilevanti secondo la *ground truth* sul corpus di 200 documenti;
- $A_q@10$: l'insieme ordinato dei documenti restituiti da `searcher.search(query, 10)` (con soglia di taglio `topN = 10`);
- $A_{q,\text{tot}}$: l'insieme totale dei documenti dell'indice invertito che soddisfano la query $q$ senza troncamento (`searcher.search(query, 200)`).

Su tali insiemi vengono calcolate cinque metriche classiche di Information Retrieval:

1. **Precision al cutoff $k=10$ ($P@10$)**: frazione dei documenti restituiti all'utente (fino a un massimo di 10) che risultano effettivamente rilevanti:
   $$P@10 = \frac{|R_q \cap A_q@10|}{|A_q@10|}$$
2. **Recall al cutoff $k=10$ ($R@10$)**: frazione dei documenti rilevanti dell'intero corpus che compaiono nei primi 10 risultati mostrati all'utente:
   $$R@10 = \frac{|R_q \cap A_q@10|}{|R_q|}$$
3. **Recall Totale sull'Indice ($R_{\text{tot}}$)**: frazione dei documenti rilevanti presenti in qualsiasi posizione della graduatoria non troncata $A_{q,\text{tot}}$. Il confronto $R_{\text{tot}} - R@10$ isola esattamente la quota di documenti rilevanti indicizzati correttamente ma persi a causa del taglio `topN = 10`:
   $$R_{\text{tot}} = \frac{|R_q \cap A_{q,\text{tot}}|}{|R_q|}$$
4. **$F_1$-score**: media armonica tra $P@10$ e $R@10$:
   $$F_1 = \frac{2 \cdot P@10 \cdot R@10}{P@10 + R@10}$$
5. **Average Precision ($AP$) e Mean Average Precision ($MAP$)**: metrica sensibile all'ordine (*rank-sensitive*) che premia i modelli di *Similarity* capaci di posizionare i documenti rilevanti ai primi posti della graduatoria:
   $$AP_q = \frac{1}{|R_q|} \sum_{k=1}^{|A_q@10|} P@k \cdot \text{rel}(k), \qquad MAP = \frac{1}{|Q|} \sum_{q \in Q} AP_q$$
   dove $\text{rel}(k) \in \{0, 1\}$ vale $1$ se il documento in posizione $k$ appartiene a $R_q$ e $0$ altrimenti.

---

### 4.2 Risultati Sperimentali sulle 12 Query di Test (Corpus $N = 200$)

Di seguito sono riportati gli output esatti generati dal sistema sui 200 documenti (verificati dalla suite JUnit `LuceneSearchTest.java`) con i relativi punteggi `BM25Similarity` ($k_1 = 1.2, b = 0.75$) e l'analisi puntuale di ciascun risultato.

#### A. Interrogazioni sul campo `nome`

- **Query 1:** `nome marte` $\rightarrow$ *Query Lucene:* `nome:marte`  
  *Ground Truth ($|R_1| = 4$):* `atmosfera-marziana-marte.txt`, `esplorazione-spaziale-marte.txt`, `missione_rover_marte.txt`, `pianeta_rosso_esobiologia.txt`.  
  *Risultati restituiti ($|A_1@10| = |A_{1,\text{tot}}| = 3$):*
  ```text
  1. atmosfera-marziana-marte.txt    (score: 2.0579) [Rilevante]
  2. esplorazione-spaziale-marte.txt (score: 2.0579) [Rilevante]
  3. missione_rover_marte.txt        (score: 2.0579) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.857}$, $AP = \mathbf{0.750}$.  
  *Analisi:* Il filtro `wordDelimiterGraph` segmenta correttamente sia i trattini (`-`) sia gli underscore (`_`), attribuendo il medesimo score BM25 (`2.0579`) ai tre file, poiché in ciascuno di essi il termine `marte` ha $\text{tf} = 1$ su un campo `nome` di uguale lunghezza (3 token). Il quarto documento rilevante (`pianeta_rosso_esobiologia.txt`) costituisce un falso negativo dovuto a sinonimia nel titolo (`"pianeta_rosso"`).

- **Query 2:** `nome "letteratura italiana"` $\rightarrow$ *Query Lucene:* `nome:"letteratura italiana"`  
  *Ground Truth ($|R_2| = 3$):* `letteratura italiana novecento.txt`, `letteratura-italiana-ottocento.txt`, `romanzo_storico_italiano.txt`.  
  *Risultati restituiti ($|A_2@10| = |A_{2,\text{tot}}| = 2$):*
  ```text
  1. letteratura italiana novecento.txt (score: 4.4576) [Rilevante]
  2. letteratura-italiana-ottocento.txt (score: 4.4576) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* La `PhraseQuery` verifica che i token `letteratura` (pos 1) e `italiana` (pos 2) siano contigui sia quando sono separati da spazi bianchi sia quando sono uniti da trattini. Il documento `romanzo_storico_italiano.txt` non viene recuperato poiché contiene l'aggettivo maschile `"italiano"` anziché la frase `"letteratura italiana"`.

- **Query 3:** `nome "basi di dati"` $\rightarrow$ *Query Lucene:* `nome:"basi di dati"`  
  *Ground Truth ($|R_3| = 3$):* `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `database_relazionali_sql.txt`.  
  *Risultati restituiti ($|A_3@10| = |A_{3,\text{tot}}| = 2$):*
  ```text
  1. basi-di-dati-relazionali.txt (score: 5.2370) [Rilevante]
  2. basi_di_dati_distribuite.txt (score: 5.2370) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* Dimostra la correttezza della scelta di non rimuovere le stopword sul campo `nome`: la preposizione `"di"` viene mantenuta in posizione 2 tra `"basi"` (pos 1) e `"dati"` (pos 3). Il terzo file rilevante (`database_relazionali_sql.txt`) non viene estratto per *vocabulary mismatch* inter-linguistico (`"database"` vs `"basi di dati"`).

- **Query 4:** `nome neurali deep` $\rightarrow$ *Query Lucene:* `nome:neurali nome:deep`  
  *Ground Truth ($|R_4| = 3$):* `RetiNeurali_DeepLearning.txt`, `DeepLearning_Trasformatori.txt`, `ModelliNeurali_VisioneArtificiale.txt`.  
  *Risultati restituiti ($|A_4@10| = |A_{4,\text{tot}}| = 3$):*
  ```text
  1. RetiNeurali_DeepLearning.txt          (score: 4.0044) [Rilevante]
  2. DeepLearning_Trasformatori.txt        (score: 2.2288) [Rilevante]
  3. ModelliNeurali_VisioneArtificiale.txt (score: 2.0022) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 3/3 = \mathbf{1.000}$, $F_1 = \mathbf{1.000}$, $AP = \mathbf{1.000}$.  
  *Analisi:* L'applicazione di `wordDelimiterGraph` prima di `lowercase` separa le parole in *CamelCase* in tutti e tre i file. Il ranking BM25 ordina perfettamente i risultati: `RetiNeurali_DeepLearning.txt` ottiene il punteggio massimo (`4.0044`) perché soddisfa entrambe le clausole `SHOULD` (`neurali` e `deep`), mentre `DeepLearning_Trasformatori.txt` (`2.2288`, 3 token totali nel nome) precede `ModelliNeurali_VisioneArtificiale.txt` (`2.0022`, 4 token totali nel nome) per effetto della normalizzazione sulla lunghezza del campo ($b = 0.75$).

#### B. Interrogazioni per termini e frasi sul campo `contenuto`

- **Query 5:** `contenuto "indice invertito"` $\rightarrow$ *Query Lucene:* `contenuto:"indic invertit"`  
  *Ground Truth ($|R_5| = 3$):* `indice_invertito_lucene.txt`, `compressione_liste_invertite.txt`, `struttura_dizionario_termini.txt`.  
  *Risultati restituiti ($|A_5@10| = |A_{5,\text{tot}}| = 2$):*
  ```text
  1. indice_invertito_lucene.txt      (score: 4.1779) [Rilevante]
  2. compressione_liste_invertite.txt (score: 3.7769) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/3 = \mathbf{0.667}$, $F_1 = \mathbf{0.800}$, $AP = \mathbf{0.667}$.  
  *Analisi:* Evidenzia il guadagno diretto di *Recall* prodotto dallo stemming di `ItalianAnalyzer`: la `PhraseQuery` al singolare recupera anche `compressione_liste_invertite.txt`, che nel corpo del testo contiene esclusivamente la forma plurale `"gli indici invertiti"` (ricondotta alla medesima sequenza `"indic invertit"`), raddoppiando la *Recall* rispetto a `StandardAnalyzer` (da $0.333$ a $0.667$). Il terzo file rilevante (`struttura_dizionario_termini.txt`) usa il sinonimo tecnico `"posting list"` e non viene recuperato.

- **Query 6:** `contenuto "sistema operativo"` $\rightarrow$ *Query Lucene:* `contenuto:"sistem operativ"`  
  *Ground Truth ($|R_6| = 4$):* `sistemi_operativi_kernel.txt`, `gestione_processi_scheduler.txt`, `memoria_virtuale_paginazione.txt`, `file_system_journaling.txt`.  
  *Risultati restituiti ($|A_6@10| = |A_{6,\text{tot}}| = 3$):*
  ```text
  1. sistemi_operativi_kernel.txt     (score: 2.8886) [Rilevante]
  2. gestione_processi_scheduler.txt  (score: 2.5743) [Rilevante]
  3. memoria_virtuale_paginazione.txt (score: 2.4358) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.857}$, $AP = \mathbf{0.750}$.  
  *Analisi:* Anche in questo caso lo stemming unifica `"sistema operativo"` e `"sistemi operativi"`, recuperando i 3 documenti principali; `file_system_journaling.txt` descrive il sottosistema di archiviazione su disco senza menzionare esplicitamente la locuzione `"sistema operativo"`.

- **Query 7:** `contenuto crittografia` $\rightarrow$ *Query Lucene:* `contenuto:crittograf`  
  *Ground Truth ($|R_7| = 4$):* `CrittografiaModerna.txt`, `cifrari_simmetrici_aes.txt`, `firma_digitale_rsa.txt`, `scambio_chiavi_diffie_hellman.txt`.  
  *Risultati restituiti ($|A_7@10| = |A_{7,\text{tot}}| = 2$):*
  ```text
  1. CrittografiaModerna.txt    (score: 2.7159) [Rilevante]
  2. cifrari_simmetrici_aes.txt (score: 2.0221) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/4 = \mathbf{0.500}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.500}$.  
  *Analisi:* Questa query dimostra sperimentalmente la differenza tra **morfologia flessiva** e **morfologia derivazionale** nello stemming: `ItalianLightStemFilter` riduce il sostantivo `"crittografia"` a `crittograf` (recuperando `CrittografiaModerna.txt` con $\text{tf}=2$ e `cifrari_simmetrici_aes.txt` con $\text{tf}=1$), ma riduce l'aggettivo derivato `"crittografici"` (presente in `firma_digitale_rsa.txt`) alla radice distinta `crittografic`, rimuovendo la desinenza plurale `-i` ma non il suffisso aggettivale `-ic-`. Ciò evita falsi positivi da *over-stemming*, ma lascia scoperto `firma_digitale_rsa.txt` (oltre a `scambio_chiavi_diffie_hellman.txt` che usa `"segreto condiviso"`).

- **Query 8:** `contenuto energie rinnovabili` $\rightarrow$ *Query Lucene:* `contenuto:energ contenuto:rinnovabil`  
  *Ground Truth ($|R_8| = 4$):* `energia_rinnovabile_italia.txt`, `parchi_eolici_offshore.txt`, `impianti_fotovoltaici_solari.txt`, `centrali_idroelettriche_alpine.txt`.  
  *Risultati restituiti ($|A_8@10| = |A_{8,\text{tot}}| = 5$):*
  ```text
  1. energia_rinnovabile_italia.txt                (score: 3.7280) [Rilevante]
  2. parchi_eolici_offshore.txt                    (score: 3.5256) [Rilevante]
  3. impianti_fotovoltaici_solari.txt              (score: 3.4240) [Rilevante]
  4. rientro_atmosferico_scudo_termico_ablativo.txt (score: 1.6836) [Non rilevante - Spazio]
  5. propulsione_ionica_sonde.txt                  (score: 1.5879) [Non rilevante - Spazio]
  ```
  *Metriche:* $P@10 = 3/5 = \mathbf{0.600}$ ($P@3 = \mathbf{1.000}$), $R@10 = R_{\text{tot}} = 3/4 = \mathbf{0.750}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.750}$.  
  *Analisi:* L'ampliamento del corpus a 200 documenti fa emergere in modo nitido l'effetto della **polisemia inter-dominio** nelle query disgiuntive (`SHOULD`): oltre ai 3 documenti rilevanti sulle energie rinnovabili (che contengono entrambi i token `energ` e `rinnovabil` e ottengono score $> 3.42$), vengono estratti in 4ª e 5ª posizione due documenti aerospaziali del dominio D6 (`rientro_atmosferico_scudo_termico_ablativo.txt`, che cita `"l'energia cinetica orbitale"`, e `propulsione_ionica_sonde.txt`, che cita `"i pannelli di energia solare della sonda"`). Sebbene la presenza di questi due distrattori riduca la precisione complessiva a $P@10 = 0.600$, `BM25Similarity` assegna loro uno score dimezzato (`1.6836` e `1.5879`), mantenendo intatta l'Average Precision ($AP = \frac{1}{4}(1/1 + 2/2 + 3/3) = 0.750$).

#### C. Interrogazioni con operatori booleani obbligatori (`+`), Ranking Multi-Documento e Stopword

- **Query 9:** `contenuto +apprendimento +modello` $\rightarrow$ *Query Lucene:* `+contenuto:apprendiment +contenuto:modell`  
  *Ground Truth ($|R_9| = 4$):* `regressione_lineare_logistica.txt`, `machine-learning-supervisionato.txt`, `alberi_decisione_random_forest.txt`, `clustering_non_supervisionato.txt`.  
  *Risultati restituiti ($|A_9@10| = |A_{9,\text{tot}}| = 2$):*
  ```text
  1. regressione_lineare_logistica.txt   (score: 3.4562) [Rilevante]
  2. machine-learning-supervisionato.txt (score: 2.5758) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/4 = \mathbf{0.500}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.500}$.  
  *Analisi:* Illustra il compromesso classico tra Precision e Recall indotto dall'operatore booleano obbligatorio `+` (`BooleanClause.Occur.MUST`): l'intersezione congiuntiva delle due *posting list* scarta qualsiasi documento che contenga il solo termine `"modello"` in altri domini (garantendo $P@10 = 1.000$), ma dimezza la *Recall* ($0.500$) escludendo i documenti di Machine Learning che descrivono `"alberi decisionali"` o `"algoritmi di partizionamento"` senza impiegare contemporaneamente entrambi i vocaboli.

- **Query 10:** `contenuto +architettura +memoria` $\rightarrow$ *Query Lucene:* `+contenuto:architettur +contenuto:memor`  
  *Ground Truth ($|R_{10}| = 4$):* `architettura_calcolatori.txt`, `gerarchia_cache_processore.txt`, `pipeline_istruzioni_risc.txt`, `coerenza_cache_protocollo_mesi_multiprocessore.txt`.  
  *Risultati restituiti ($|A_{10}@10| = |A_{10,\text{tot}}| = 2$):*
  ```text
  1. architettura_calcolatori.txt   (score: 2.9024) [Rilevante]
  2. gerarchia_cache_processore.txt (score: 2.4283) [Rilevante]
  ```
  *Metriche:* $P@10 = \mathbf{1.000}$, $R@10 = R_{\text{tot}} = 2/4 = \mathbf{0.500}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.500}$.  
  *Analisi:* Verifica la rimozione dell'elisione apostrofata (`"L'architettura"` $\to$ `architettur`) e l'intersezione congiuntiva con `memor`: vengono recuperati con precisione unitaria i due documenti che trattano congiuntamente l'architettura del processore e la gerarchia di memoria, mentre restano esclusi sia i falsi positivi di altri domini sia i due documenti hardware che menzionano uno solo dei due termini (`pipeline_istruzioni_risc.txt` e `coerenza_cache_protocollo_mesi_multiprocessore.txt`).

- **Query 11:** `contenuto architettura dati` $\rightarrow$ *Query Lucene:* `contenuto:architettur contenuto:dati`  
  *Ground Truth ($|R_{11}| = 8$, architetture di gestione e indicizzazione dei dati):* `data_lake_cloud_storage.txt`, `data_warehouse_olap.txt`, `IngegneriaDeiDati_Pipeline.txt`, `basi-di-dati-relazionali.txt`, `basi_di_dati_distribuite.txt`, `federazione_sorgenti_wrapper_mediatori.txt`, `indice_invertito_lucene.txt`, `catalogo_metadati_data_governance.txt`.  
  *Risultati restituiti ($|A_{11}@10| = 10$ mostrati su $|A_{11,\text{tot}}| = 15$ documenti corrispondenti nell'indice):*
  ```text
  1.  data_lake_cloud_storage.txt                (score: 2.6876) [Rilevante]
  2.  data_warehouse_olap.txt                    (score: 2.5761) [Rilevante]
  3.  IngegneriaDeiDati_Pipeline.txt             (score: 2.5753) [Rilevante]
  4.  architettura_calcolatori.txt               (score: 2.3391) [Non rilevante - Hardware]
  5.  basi-di-dati-relazionali.txt               (score: 1.9588) [Rilevante]
  6.  poetica_dante_commedia_allegoria.txt       (score: 1.9034) [Non rilevante - Letteratura]
  7.  basi_di_dati_distribuite.txt               (score: 1.7011) [Rilevante]
  8.  federazione_sorgenti_wrapper_mediatori.txt (score: 1.4465) [Rilevante]
  9.  ModelliNeurali_VisioneArtificiale.txt      (score: 1.4022) [Non rilevante - AI]
  10. gerarchia_cache_processore.txt             (score: 1.2843) [Non rilevante - Hardware]
  --- Soglia di cutoff topN = 10 (documenti estratti nell'indice ma esclusi da TopDocs(10)) ---
  11. machine-learning-supervisionato.txt        (score: 1.0973) [Non rilevante - AI]
  12. RetiNeurali_DeepLearning.txt               (score: 1.0724) [Non rilevante - AI]
  13. indice_invertito_lucene.txt                (score: 1.0042) [Rilevante - Troncatura top-10]
  14. musica_classica_sinfonie.txt               (score: 0.9511) [Non rilevante - Musica]
  15. protocolli_rete_tcp_ip.txt                 (score: 0.9511) [Non rilevante - Reti]
  ```
  *Metriche:* $P@10 = 6/10 = \mathbf{0.600}$ ($P@3 = \mathbf{1.000}$), $R@10 = 6/8 = \mathbf{0.750}$, $R_{\text{tot}} = 7/8 = \mathbf{0.875}$, $F_1 = \mathbf{0.667}$, $AP = \mathbf{0.658}$.  
  *Analisi:* Sul corpus di 200 documenti, questa interrogazione disgiuntiva rappresenta un caso di studio completo sul funzionamento di `BM25Similarity` e sul **problema della Recall**:
  1. **Comportamento dello stemmer**: `ItalianLightStemFilter` tronca `"architettura"` (12 caratteri) a `architettur` ($\text{df} = 7$), ma mantiene invariato `"dati"` (4 caratteri, $\text{df} = 11$) poiché non riduce parole di lunghezza inferiore a 6 caratteri.
  2. **Ordinamento per co-occorrenza, saturazione TF ($k_1 = 1.2$) e normalizzazione della lunghezza ($b = 0.75$)**: i primi 4 documenti (`data_lake_cloud_storage.txt`, `data_warehouse_olap.txt`, `IngegneriaDeiDati_Pipeline.txt`, `architettura_calcolatori.txt`) contengono *entrambi* i token cercati; `data_lake_cloud_storage.txt` (`2.6876`) e `data_warehouse_olap.txt` (`2.5761`), essendo più concisi, precedono `IngegneriaDeiDati_Pipeline.txt` (`2.5753`) per effetto del parametro $b = 0.75$.
  3. **Resistenza di BM25 alla ripetizione di un singolo termine ad alto IDF rispetto a TF-IDF**: in 5ª posizione `BM25Similarity` colloca correttamente `basi-di-dati-relazionali.txt` (`1.9588`, rilevante, con $\text{tf}(\text{dati}) = 4$) davanti al distrattore letterario `poetica_dante_commedia_allegoria.txt` (`1.9034`, non rilevante, che usa tre volte il termine metaforico `"architettura"` descrivendo la Divina Commedia). Come mostrato nello studio di ablazione (Sezione 4.4), sostituendo `BM25Similarity` con `ClassicSimilarity` (TF-IDF), l'assenza di saturazione non lineare di $\text{tf}$ consente al documento dantesco (`1.0438`) di scavalcare `basi-di-dati-relazionali.txt` (`1.0330`) al 5° posto, degradando l'Average Precision da `0.658` a `0.641`.
  4. **Divergenza tra $R@10$ e $R_{\text{tot}}$ per effetto del cutoff `topN = 10`**: l'indice invertito individua 7 degli 8 documenti rilevanti ($R_{\text{tot}} = 7/8 = 0.875$), ma poiché 4 documenti non rilevanti contenenti il termine più raro `architettur` ($\text{df}=7$, quindi con IDF superiore a `dati` che ha $\text{df}=11$) si inseriscono nelle prime 10 posizioni, il documento rilevante `indice_invertito_lucene.txt` (che contiene solo `dati` con $\text{tf}=1$, `score: 1.0042`) scivola in **13ª posizione**, venendo tagliato fuori da `searcher.search(query, 10)` e riducendo la *Recall* effettiva percepita dall'utente a $R@10 = 6/8 = 0.750$. L'ottavo documento rilevante (`catalogo_metadati_data_governance.txt`) non compare nemmeno nei 15 risultati totali a causa del *vocabulary mismatch* (`"patrimonio informativo"` anziché `"architettura dati"`).

- **Query 12:** `contenuto il dello nella` $\rightarrow$ *Query Lucene:* *(vuota)*  
  *Risultato:* `Risultati trovati: 0`  
  *Analisi:* Test di controllo negativo: tutti e tre i termini appartengono alla lista delle stopword italiane di `ItalianAnalyzer` e vengono rimossi da `StopFilter` prima della generazione della query, producendo una stringa vuota e restituendo correttamente 0 documenti ($|R_{12}| = 0, |A_{12}| = 0$).

---

### 4.3 Tabella Riepilogativa delle Metriche per Query (Configurazione Proposta)

La Tabella 1 sintetizza le metriche ottenute dalla configurazione proposta (`PerFieldAnalyzerWrapper(CustomAnalyzer + ItalianAnalyzer)` + `BM25Similarity`) sulle 11 interrogazioni informative eseguite sul corpus di 200 documenti:

| Query | Campo | Sintassi Query | $\|R_q\|$ | $\|A@10\|$ | $\|A_{\text{tot}}\|$ | $TP@10$ | $TP_{\text{tot}}$ | $P@10$ | $R@10$ | $R_{\text{tot}}$ | $F_1$-score | $AP$ |
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
| **Q11** | `contenuto` | `architettura dati` | 8 | 10 | 15 | 6 | 7 | 0.600 | 0.750 | 0.875 | 0.667 | 0.658 |
| **Media** | — | **Macro-Average / MAP** | **44** | **36** | **41** | **30** | **31** | **0.927** | **0.682** | **0.693** | **0.768** | **0.673** |

---

### 4.4 Studio di Ablazione: Confronto tra Funzioni di Similarity e Analyzer (Slide 22–29, 46–48)

Per validare scientificamente il contributo di ciascuna scelta progettuale rispetto alle alternative presentate a lezione, in `LuceneSearchTest.testExperimentalBenchmarksAndAblationStudy` i 200 documenti del corpus sono stati indicizzati e interrogati sotto **quattro configurazioni sperimentali distinte**:

- **Configurazione A (Proposta)**: `PerFieldAnalyzerWrapper` (`CustomAnalyzer` su `nome`, `ItalianAnalyzer` su `contenuto`) combinato con **`BM25Similarity`** (Slide 48: saturazione non lineare della frequenza $\frac{\text{tf} \cdot (k_1 + 1)}{\text{tf} + k_1 \cdot (1 - b + b \cdot |d|/\text{avgdl})}$ con $k_1 = 1.2, b = 0.75$).
- **Configurazione B (Ablazione Similarity)**: medesimo `PerFieldAnalyzerWrapper` combinato con **`ClassicSimilarity`** (Slide 47: implementazione classica del Vector Space Model **TF-IDF** di Lucene, in cui $\text{tf}(t, d) = \sqrt{\text{freq}}$, $\text{idf}(t) = 1 + \ln\frac{N + 1}{\text{df} + 1}$ e $\text{lengthNorm}(d) = 1/\sqrt{\text{numTerms}}$).
- **Configurazione C (Ablazione Analyzer – `StandardAnalyzer`)**: impiego di `StandardAnalyzer` (Slide 22 e 27) su entrambi i campi con `BM25Similarity`.
- **Configurazione D (Ablazione Analyzer – `WhitespaceAnalyzer`)**: impiego di `WhitespaceAnalyzer` (Slide 23 e 27) su entrambi i campi con `BM25Similarity`.

| Configurazione Sperimentale | Analyzer (`nome` / `contenuto`) | Funzione `Similarity` | Macro-$P@10$ | Macro-$R@10$ | Macro-$R_{\text{tot}}$ | Macro-$F_1$ | $MAP$ | $\Delta R@10$ vs Config A |
|:---|:---|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Config A (Proposta)** | `CustomAnalyzer` / `ItalianAnalyzer` | `BM25Similarity` | **0.927** | **0.682** | **0.693** | **0.768** | **0.673** | — |
| **Config B (TF-IDF)** | `CustomAnalyzer` / `ItalianAnalyzer` | `ClassicSimilarity` | 0.927 | 0.682 | 0.693 | 0.768 | 0.672 | $0.0\%$ ($\Delta AP_{Q11} = -1.7\%$) |
| **Config C (Standard)** | `StandardAnalyzer` (entrambi) | `BM25Similarity` | 0.697 | 0.394 | 0.394 | 0.488 | 0.391 | **$-28.8\%$** (crollo relativo $-42.2\%$) |
| **Config D (Whitespace)** | `WhitespaceAnalyzer` (entrambi) | `BM25Similarity` | 0.515 | 0.288 | 0.288 | 0.354 | 0.285 | **$-39.4\%$** (crollo relativo $-57.8\%$) |

Lo studio di ablazione fornisce due evidenze sperimentali nette:
1. **Impatto determinante degli Analyzer sulla Recall ($-28.8\%$ e $-39.4\%$ punti percentuali)**: sostituire il `PerFieldAnalyzerWrapper` con `StandardAnalyzer` (Config C) provoca un crollo della Recall media dal **68.2% al 39.4%** ($MAP$ da `0.673` a `0.391`), poiché `StandardAnalyzer` non separa né gli underscore (`missione_rover_marte`, `basi_di_dati_distribuite`) né il *CamelCase* (`RetiNeurali_DeepLearning`, portando a $0$ i risultati della Query 4) sul campo `nome`, e non applica stemming sul campo `contenuto` (perdendo i plurali `"indici invertiti"`, `"sistemi operativi"` e `"energia rinnovabile"` nelle Query 5, 6 e 8). Con `WhitespaceAnalyzer` (Config D) la Recall scende ulteriormente al **28.8%** ($MAP = 0.285$), poiché anche i trattini (`basi-di-dati-relazionali`), le maiuscole (`CrittografiaModerna`), la punteggiatura adiacente alle parole e le elisioni apostrofate (`"L'architettura"`) impediscono l'allineamento dei token tra documento e query.
2. **Superiorità di `BM25Similarity` su `ClassicSimilarity` (TF-IDF) nel ranking di query multi-termine**: a parità di analyzer, `Config A` e `Config B` estraggono il medesimo insieme booleano di documenti candidati (stessa $P@10 = 0.927$ e $R@10 = 0.682$), ma nella Query 11 (`contenuto architettura dati`) `ClassicSimilarity` soffre della crescita non limitata di $\text{tf} = \sqrt{\text{freq}}$ sul termine a più alto IDF (`architettur`), promuovendo il falso positivo letterario `poetica_dante_commedia_allegoria.txt` (`score: 1.0438`, $\text{tf}(\text{architettur}) = 3$) in 5ª posizione davanti al documento rilevante `basi-di-dati-relazionali.txt` (`score: 1.0330`, 6ª posizione), laddove `BM25Similarity`, grazie alla saturazione asintotica controllata da $k_1 = 1.2$, mantiene `basi-di-dati-relazionali.txt` (`1.9588`) al 5° posto davanti al documento dantesco (`1.9034`), ottenendo un'Average Precision superiore (`0.658` vs `0.641`).

---

### 4.5 Discussione Critica: Le Cinque Dimensioni del Problema della Recall in Information Retrieval

La sperimentazione sul corpus di 200 documenti consente di sintetizzare una tassonomia rigorosa dei fattori che rendono la valutazione e l'ottimizzazione della **Recall** ($R = |R_q \cap A_q| / |R_q|$) intrinsecamente più complessa rispetto a quella della Precision:

1. **Perdita di Recall da troncamento Top-$k$ ($R@10 < R_{\text{tot}}$)**:  
   In un motore di ricerca reale l'utente ispeziona solo le prime $k$ posizioni (`searcher.search(query, 10)`). Quando una collezione scala a centinaia o migliaia di documenti, il numero di documenti che soddisfano una query disgiuntiva supera $k$ (nella Query 11, $|A_{11,\text{tot}}| = 15 > 10$). In presenza di **polisemia** su un termine della query ad alto IDF (come `"architettura"` in ambito hardware e letterario), alcuni falsi positivi possono ottenere uno score superiore a quello di documenti rilevanti che contengono il solo termine a più basso IDF (`"dati"` in `indice_invertito_lucene.txt`), spingendo questi ultimi oltre la 10ª posizione (13° posto in Q11) e causando una perdita di Recall effettiva ($R@10 = 0.750$ contro $R_{\text{tot}} = 0.875$).
2. **Divario lessicale (*Vocabulary Mismatch*: Sinonimia e Perifrasi)**:  
   Poiché l'indice invertito opera per corrispondenza lessicale esatta sui token normalizzati, i documenti rilevanti che esprimono il medesimo concetto mediante sinonimi, perifrasi o forestierismi (`"Pianeta Rosso"` per `"Marte"` in Q1, `"database"` per `"basi di dati"` in Q3, `"posting list"` per `"indice invertito"` in Q5, `"patrimonio informativo"` per `"architettura dati"` in Q11) non condividono alcun termine con la query e restano invisibili anche in $A_{q,\text{tot}}$. Il superamento di questo limite richiede l'integrazione di un filtro di sinonimi a tempo di indicizzazione o di ricerca (`SynonymGraphFilter` basato su dizionario o tesauro di dominio) o tecniche di *Query Expansion* / *Pseudo-Relevance Feedback* (algoritmo di Rocchio).
3. **Morfologia Flessiva vs Derivazionale e Soglie dello Stemmer**:  
   Come dimostrato nella Query 7 (`"crittografia"` $\to$ `crittograf` vs `"crittografici"` $\to$ `crittografic`) e nella Query 11 (`"dati"` non troncato a `dat` perché lungo meno di 6 caratteri), `ItalianLightStemFilter` rimuove le sole desinenze flessive conservando i suffissi derivazionali. Sebbene uno stemmer più aggressivo (es. Snowball / Porter italiano) potrebbe unificare sostantivi e aggettivi derivati aumentando la Recall su Q7, esso introdurrebbe fenomeni di *over-stemming* (fondendo parole semanticamente distinte nella stessa radice) con conseguente degrado della Precision.
4. **Rigidità dei Vincoli Booleani Congiuntivi (`MUST` vs `SHOULD`)**:  
   Le Query 9 e 10 dimostrano che l'operatore obbligatorio `+` (`BooleanClause.Occur.MUST`) massimizza la Precision ($P@10 = 1.000$) ma taglia drasticamente la Recall ($R@10 = 0.500$), poiché basta l'omissione di uno solo dei termini obbligatori nel testo per escludere un documento altamente pertinente.
5. **Il Problema della Stima del Denominatore $|R_q|$ su Larga Scala (*Pooling*)**:  
   Dal punto di vista metodologico, mentre il calcolo della Precision $P@10$ richiede di valutare la rilevanza dei soli $\le 10$ documenti restituiti, il calcolo esatto della Recall richiede di conoscere il denominatore $|R_q|$, ossia **tutti** i documenti rilevanti presenti nell'intera collezione $C$. Su 200 documenti è ancora possibile ispezionare esaustivamente i 25 file di ciascuno degli 8 domini per costruire una *ground truth* completa ($200 \times 11 = 2.200$ coppie query-documento); su collezioni di centinaia di migliaia o milioni di documenti, la verifica manuale esaustiva diventa impraticabile e la comunità scientifica IR ricorre al metodo del **Pooling** (introdotto nelle campagne TREC), che stima $R_q$ unendo e valutando manualmente l'insieme $\bigcup_{m} A_q^{(m)}@k$ dei primi $k$ documenti restituiti da una famiglia diversificata di modelli di recupero $m$ (esattamente come realizzato in scala ridotta attraverso le 4 configurazioni del nostro studio di ablazione).

---

## 5. Conclusioni

Il sistema sviluppato risponde integralmente alle specifiche progettuali e metodologiche del corso di Ingegneria dei Dati, coniugando un'architettura software fedele alle astrazioni interne di Apache Lucene (`Document`, `TextField`, `PerFieldAnalyzerWrapper`, `DocumentsWriter`/`DWPT`, `SimpleTextCodec`, `IndexSearcher`/`Weight`/`Scorer` e `BM25Similarity`) con un protocollo sperimentale riproducibile su un corpus strutturato di **200 documenti** (63.781 byte). I risultati quantitativi confermano che la combinazione tra `CustomAnalyzer` (`WhitespaceTokenizer` + `WordDelimiterGraphFilter` + `LowerCaseFilter`) sul campo `nome`, `ItalianAnalyzer` sul campo `contenuto` e il modello probabilistico `BM25Similarity` raggiunge una **Precision media del 92.7%**, una **Recall media del 68.2%** ($R_{\text{tot}} = 69.3\%$), un **$F_1$-score del 76.8%** e una **Mean Average Precision ($MAP$) di 0.673**, superando di oltre 28 punti percentuali in Recall e MAP le baseline basate su `StandardAnalyzer` e `WhitespaceAnalyzer` e dimostrando maggiore robustezza rispetto a `ClassicSimilarity` (TF-IDF) nei confronti della ripetizione di termini polisemici ad alto IDF.
