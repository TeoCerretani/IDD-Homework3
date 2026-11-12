# Indicizzazione e Recupero dell'Informazione con Apache Lucene (IDD - Homework 3)

**Autore:** Matteo Cerretani (`mat.cerretani@stud.uniroma3.it` – `matteo.cerretani@gmail.com`)  
**Corso:** Ingegneria dei Dati – Università degli Studi Roma Tre  
**Relazione Scientifica (PDF):** [Relazione_HW3_Cerretani.pdf](./Relazione_HW3_Cerretani.pdf)

---

## Descrizione del Progetto

Il progetto implementa e valuta sperimentalmente un sistema di Information Retrieval in **Java 20** basato su **Apache Lucene 9.12.0** per l'indicizzazione e l'interrogazione full-text da console di una collezione strutturata di **200 file testuali (`.txt`)** in lingua italiana (`corpus/`, 63.781 byte complessivi), articolata in **8 domini tematici bilanciati da 25 documenti ciascuno** con *ground truth* (*qrels*) nota per la valutazione di *Precision* ($P@10$), *Recall* ($R@10$ e $R_{\text{tot}}$), $F_1$-score, *Average Precision ($AP$)* e *Mean Average Precision ($MAP$)*.

Ciascun file viene modellato come un'istanza di `Document` composta da due campi `TextField` distinti (a ciascuno dei quali corrisponde una *posting list* separata), gestiti mediante un `PerFieldAnalyzerWrapper` condiviso tra la fase di indicizzazione (`FileIndexer`) e la fase di ricerca (`ConsoleSearcher`):

- **Campo `nome`** (`Field.Store.YES`): memorizza il nome del file privato dell'estensione `.txt` ed è analizzato tramite un **`CustomAnalyzer`** composto da:
  1. `WhitespaceTokenizer` (`withTokenizer("whitespace")`)
  2. `WordDelimiterGraphFilter` (`addTokenFilter("wordDelimiterGraph")`), posizionato *prima* del filtro lowercase per segmentare sia i separatori intraparola (`-`, `_`, `.`) sia le transizioni *CamelCase* (es. `RetiNeurali_DeepLearning` $\rightarrow$ `reti`, `neurali`, `deep`, `learning`)
  3. `LowerCaseFilter` (`addTokenFilter("lowercase")`)
- **Campo `contenuto`** (`Field.Store.NO`): indicizza il corpo testuale integrale del file mediante **`ItalianAnalyzer`**, applicando tokenizzazione standard, rimozione delle elisioni apostrofate (es. `L'architettura`), filtraggio delle stopword italiane e stemming morfologico leggero (`ItalianLightStemFilter`).
- **Persistenza e Ranking**: i segmenti dell'indice sono serializzati in formato diagnostico leggibile tramite **`SimpleTextCodec`** (con azzeramento iniziale tramite `writer.deleteAll()` e consolidamento mediante `writer.commit()`), mentre la valutazione della rilevanza a tempo di ricerca adotta il modello probabilistico **Okapi BM25** (`BM25Similarity`, $k_1 = 1.2, b = 0.75$), confrontato tramite uno studio di ablazione con `ClassicSimilarity` (TF-IDF), `StandardAnalyzer` e `WhitespaceAnalyzer`.

---

## Struttura del Repository

```text
IDD-Homework3/
├── corpus/                                      # Collezione di 200 documenti .txt (8 domini tematici da 25 file)
├── src/
│   ├── main/java/it/uniroma3/idd/hw3/
│   │   ├── FileIndexer.java                     # Modulo di indicizzazione dei file .txt (SimpleTextCodec)
│   │   └── ConsoleSearcher.java                 # Modulo di ricerca interattiva da console (BM25Similarity)
│   └── test/java/it/uniroma3/idd/hw3/
│       └── LuceneSearchTest.java                # Suite JUnit 5: 12 query, benchmark tempi Codec e studio di ablazione
├── pom.xml                                      # Configurazione Apache Maven (Java 20, Lucene 9.12.0)
├── RELAZIONE.md                                 # Sorgente Markdown della relazione scientifica
├── Relazione_HW3_Cerretani.pdf                  # Relazione scientifica completa in formato PDF accademico
└── README.md                                    # Documentazione del repository
```

---

## Requisiti ed Esecuzione

- **Java JDK:** 20 o superiore
- **Build Tool:** Apache Maven 3.8+

### 1. Indicizzazione del Corpus (`FileIndexer`)
Indicizza tutti i 200 file `.txt` presenti in `corpus/` generando l'indice invertito nella directory `index/`:

```bash
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
```

### 2. Ricerca Interattiva da Console (`ConsoleSearcher`)
Avvia l'interfaccia a riga di comando per interrogare l'indice con la sintassi `<campo> <termini o "frase">`:

```bash
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
```

### 3. Esecuzione Automatica dei Test e dello Studio di Ablazione (`LuceneSearchTest`)
Esegue la suite JUnit 5 che verifica l'indicizzazione dei 200 file, tutte le 12 query sperimentali, il benchmark dei tempi di indicizzazione (`DefaultCodec` vs `SimpleTextCodec` per $N \in \{25, 50, 100, 150, 200\}$) e lo studio di ablazione IR:

```bash
mvn test
```

---

## Riepilogo delle 12 Query di Test e Metriche di Valutazione (su 200 Documenti)

| # | Query da console | Query Lucene (`QueryParser`) | $\|R_q\|$ | $\|A@10\|$ | $\|A_{\text{tot}}\|$ | $P@10$ | $R@10$ | $R_{\text{tot}}$ | $F_1$ | $AP$ | Score BM25 (Top-1) |
|:-:|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **1** | `nome marte` | `nome:marte` | 4 | 3 | 3 | `1.000` | `0.750` | `0.750` | `0.857` | `0.750` | `2.0579` |
| **2** | `nome "letteratura italiana"` | `nome:"letteratura italiana"` | 3 | 2 | 2 | `1.000` | `0.667` | `0.667` | `0.800` | `0.667` | `4.4576` |
| **3** | `nome "basi di dati"` | `nome:"basi di dati"` | 3 | 2 | 2 | `1.000` | `0.667` | `0.667` | `0.800` | `0.667` | `5.2370` |
| **4** | `nome neurali deep` | `nome:neurali nome:deep` | 3 | 3 | 3 | `1.000` | `1.000` | `1.000` | `1.000` | `1.000` | `4.0044` |
| **5** | `contenuto "indice invertito"` | `contenuto:"indic invertit"` | 3 | 2 | 2 | `1.000` | `0.667` | `0.667` | `0.800` | `0.667` | `4.1779` |
| **6** | `contenuto "sistema operativo"` | `contenuto:"sistem operativ"` | 4 | 3 | 3 | `1.000` | `0.750` | `0.750` | `0.857` | `0.750` | `2.8886` |
| **7** | `contenuto crittografia` | `contenuto:crittograf` | 4 | 2 | 2 | `1.000` | `0.500` | `0.500` | `0.667` | `0.500` | `2.7159` |
| **8** | `contenuto energie rinnovabili` | `contenuto:energ contenuto:rinnovabil` | 4 | 5 | 5 | `0.600` | `0.750` | `0.750` | `0.667` | `0.750` | `3.7280` |
| **9** | `contenuto +apprendimento +modello` | `+contenuto:apprendiment +contenuto:modell` | 4 | 2 | 2 | `1.000` | `0.500` | `0.500` | `0.667` | `0.500` | `3.4562` |
| **10** | `contenuto +architettura +memoria` | `+contenuto:architettur +contenuto:memor` | 4 | 2 | 2 | `1.000` | `0.500` | `0.500` | `0.667` | `0.500` | `2.9024` |
| **11** | `contenuto architettura dati` | `contenuto:architettur contenuto:dati` | 8 | 10 | 15 | `0.600` | `0.750` | `0.875` | `0.667` | `0.658` | `2.6876` |
| **12** | `contenuto il dello nella` | *(vuota)* | 0 | 0 | 0 | — | — | — | — | — | — |
| **Media** | **Macro-Average / MAP (Q1–Q11)** | — | **44** | **36** | **41** | **0.927** | **0.682** | **0.693** | **0.768** | **0.673** | — |

### Sintesi dello Studio di Ablazione ($N = 200$ documenti)

| Configurazione Sperimentale | Analyzer (`nome` / `contenuto`) | `Similarity` | Macro-$P@10$ | Macro-$R@10$ | Macro-$R_{\text{tot}}$ | Macro-$F_1$ | $MAP$ |
|:---|:---|:---|:---:|:---:|:---:|:---:|:---:|
| **Config A (Proposta)** | `CustomAnalyzer` / `ItalianAnalyzer` | `BM25Similarity` | **0.927** | **0.682** | **0.693** | **0.768** | **0.673** |
| **Config B (TF-IDF)** | `CustomAnalyzer` / `ItalianAnalyzer` | `ClassicSimilarity` | `0.927` | `0.682` | `0.693` | `0.768` | `0.672` |
| **Config C (Standard)** | `StandardAnalyzer` (entrambi) | `BM25Similarity` | `0.697` | `0.394` | `0.394` | `0.488` | `0.391` |
| **Config D (Whitespace)** | `WhitespaceAnalyzer` (entrambi) | `BM25Similarity` | `0.515` | `0.288` | `0.288` | `0.354` | `0.285` |
