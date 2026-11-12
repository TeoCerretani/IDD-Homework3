# Indicizzazione e Recupero dell'Informazione con Apache Lucene (IDD - Homework 3)

**Autore:** Matteo Cerretani (`mat.cerretani@stud.uniroma3.it` – `matteo.cerretani@gmail.com`)  
**Corso:** Ingegneria dei Dati – Università degli Studi Roma Tre  
**Relazione Tecnica (PDF):** [Relazione_HW3_Cerretani.pdf](./Relazione_HW3_Cerretani.pdf)

---

## Descrizione del Progetto

Il progetto implementa un sistema di Information Retrieval in **Java 20** basato su **Apache Lucene 9.12.0** per l'indicizzazione e l'interrogazione da console di una collezione di **80 file testuali (`.txt`)** in lingua italiana memorizzati su file system locale (`corpus/`), organizzati in **8 cluster tematici da 10 documenti ciascuno** con *ground truth* nota per la valutazione di *Precision*, *Recall*, $F_1$-score e *Mean Average Precision (MAP)*.

Ciascun file viene modellato come un'istanza di `Document` composta da due campi `TextField` distinti, gestiti mediante un `PerFieldAnalyzerWrapper` condiviso tra la fase di indicizzazione e la fase di ricerca:

- **Campo `nome`** (`Field.Store.YES`): memorizza il nome del file privato dell'estensione `.txt` ed è analizzato tramite un **`CustomAnalyzer`** composto da:
  1. `WhitespaceTokenizer` (`withTokenizer("whitespace")`)
  2. `WordDelimiterGraphFilter` (`addTokenFilter("wordDelimiterGraph")`), posizionato *prima* del filtro lowercase per segmentare sia i separatori intraparola (`-`, `_`, `.`) sia le transizioni *CamelCase* (es. `RetiNeurali_DeepLearning` $\rightarrow$ `reti`, `neurali`, `deep`, `learning`)
  3. `LowerCaseFilter` (`addTokenFilter("lowercase")`)
- **Campo `contenuto`** (`Field.Store.NO`): indicizza il corpo testuale integrale del file mediante **`ItalianAnalyzer`**, applicando tokenizzazione standard, rimozione delle elisioni apostrofate (es. `L'architettura`), filtraggio delle stopword italiane e stemming morfologico.
- **Persistenza e Ranking**: i segmenti dell'indice sono serializzati in formato diagnostico leggibile tramite **`SimpleTextCodec`** (con azzeramento iniziale tramite `writer.deleteAll()` e consolidamento mediante `writer.commit()`), mentre la valutazione della rilevanza a tempo di ricerca adotta il modello probabilistico **Okapi BM25** (`BM25Similarity`).

---

## Struttura del Repository

```text
IDD-Homework3/
├── corpus/                                      # Collezione di 80 documenti .txt (8 cluster tematici da 10 file)
├── src/
│   ├── main/java/it/uniroma3/idd/hw3/
│   │   ├── FileIndexer.java                     # Modulo di indicizzazione dei file .txt (SimpleTextCodec)
│   │   └── ConsoleSearcher.java                 # Modulo di ricerca interattiva da console (BM25Similarity)
│   └── test/java/it/uniroma3/idd/hw3/
│       └── LuceneSearchTest.java                # Suite di test JUnit 5 per la verifica delle 12 query sugli 80 file
├── pom.xml                                      # Configurazione Apache Maven (Java 20, Lucene 9.12.0)
├── RELAZIONE.md                                 # Sorgente Markdown della relazione tecnica
├── Relazione_HW3_Cerretani.pdf                  # Relazione tecnica completa in formato PDF accademico
└── README.md                                    # Documentazione del repository
```

---

## Requisiti ed Esecuzione

- **Java JDK:** 20 o superiore
- **Build Tool:** Apache Maven 3.8+

### 1. Indicizzazione del Corpus (`FileIndexer`)
Indicizza tutti gli 80 file `.txt` presenti in `corpus/` generando l'indice invertito nella directory `index/`:

```bash
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
```

### 2. Ricerca Interattiva da Console (`ConsoleSearcher`)
Avvia l'interfaccia a riga di comando per interrogare l'indice con la sintassi `<campo> <termini o "frase">`:

```bash
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
```

### 3. Esecuzione Automatica delle 12 Query di Test (`LuceneSearchTest`)
Esegue la suite di test JUnit 5 che verifica l'indicizzazione degli 80 file e tutte le 12 query sperimentali:

```bash
mvn test
```

---

## Riepilogo delle 12 Query di Test e Metriche di Valutazione (su 80 Documenti)

| # | Query da console | Query Lucene (`QueryParser`) | $\|R_q\|$ | $\|A_q\|$ | Precision ($P$) | Recall ($R$) | $F_1$ | Score BM25 (Top-1) | Fenomeno valutato |
|:-:|---|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| **1** | `nome marte` | `nome:marte` | 4 | 3 | `1.000` | `0.750` | `0.857` | `1.4832` | Segmentazione `-` e `_`; limite Recall su sinonimo (`pianeta_rosso`) |
| **2** | `nome "letteratura italiana"` | `nome:"letteratura italiana"` | 3 | 2 | `1.000` | `0.667` | `0.800` | `3.2841` | `PhraseQuery` su spazi bianchi e trattini |
| **3** | `nome "basi di dati"` | `nome:"basi di dati"` | 3 | 2 | `1.000` | `0.667` | `0.800` | `4.2235` | Conservazione preposizione `"di"`; anglicismo (`database`) |
| **4** | `nome neurali deep` | `nome:neurali nome:deep` | 3 | 3 | `1.000` | `1.000` | `1.000` | `2.9095` | Suddivisione *CamelCase* e underscore prima del `lowercase` |
| **5** | `contenuto "indice invertito"` | `contenuto:"indic invertit"` | 3 | 2 | `1.000` | `0.667` | `0.800` | `3.6495` | `PhraseQuery` + stemming singolare/plurale (`indici invertiti`) |
| **6** | `contenuto "sistema operativo"` | `contenuto:"sistem operativ"` | 4 | 3 | `1.000` | `0.750` | `0.857` | `2.5507` | Allineamento morfologico in `PhraseQuery` sul contenuto |
| **7** | `contenuto crittografia` | `contenuto:crittograf` | 4 | 2 | `1.000` | `0.500` | `0.667` | `2.2468` | Limite stemming leggero su derivati (`crittograf` vs `crittografic`) |
| **8** | `contenuto energie rinnovabili` | `contenuto:energ contenuto:rinnovabil` | 4 | 4 | `0.750` | `0.750` | `0.750` | `3.1187` | Stemming singolare/plurale e gestione falsi positivi con BM25 |
| **9** | `contenuto +apprendimento +modello` | `+contenuto:apprendiment +contenuto:modell` | 4 | 2 | `1.000` | `0.500` | `0.667` | `2.9703` | Compromesso Precision/Recall con operatore obbligatorio `+` (`MUST`) |
| **10** | `contenuto +architettura +memoria` | `+contenuto:architettur +contenuto:memor` | 3 | 2 | `1.000` | `0.667` | `0.800` | `2.5662` | Clausole obbligatorie e rimozione dell'elisione `"L'architettura"` |
| **11** | `contenuto architettura dati` | `contenuto:architettur contenuto:dati` | 5 | 10 | `0.500` | `1.000` | `0.667` | `2.0820` | Graduatoria BM25 ($P@3 = 1.000, AP = 0.927$) e soglia stem (`len < 6`) |
| **12** | `contenuto il dello nella` | *(vuota)* | 0 | 0 | — | — | — | — | Rimozione integrale delle stopword italiane |
| **Media** | **Macro-Average / MAP (Q1–Q11)** | — | **40** | **35** | **0.932** | **0.720** | **0.798** | **MAP = 0.717** | Valutazione complessiva sul benchmark di 80 documenti |
