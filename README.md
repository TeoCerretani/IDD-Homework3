# Indicizzazione e Recupero dell'Informazione con Apache Lucene (IDD - Homework 3)

**Autore:** Matteo Cerretani  
**Corso:** Ingegneria dei Dati – Università degli Studi Roma Tre  
**Relazione Tecnica (PDF):** [Relazione_HW3_Cerretani.pdf](./Relazione_HW3_Cerretani.pdf)

---

## Descrizione del Progetto

Il progetto implementa un sistema di Information Retrieval in **Java 20** basato su **Apache Lucene 9.12.0** per l'indicizzazione e l'interrogazione da console di una collezione di file testuali (`.txt`) memorizzati su file system locale (`corpus/`).

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
├── corpus/                                      # Collezione di 15 documenti .txt in lingua italiana
├── src/
│   ├── main/java/it/uniroma3/idd/hw3/
│   │   ├── FileIndexer.java                     # Modulo di indicizzazione dei file .txt (SimpleTextCodec)
│   │   └── ConsoleSearcher.java                 # Modulo di ricerca interattiva da console (BM25Similarity)
│   └── test/java/it/uniroma3/idd/hw3/
│       └── LuceneSearchTest.java                # Suite di test JUnit 5 per la verifica delle 12 query
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
Indicizza tutti i file `.txt` presenti in `corpus/` generando l'indice invertito nella directory `index/`:

```bash
mvn compile exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.FileIndexer"
```

### 2. Ricerca Interattiva da Console (`ConsoleSearcher`)
Avvia l'interfaccia a riga di comando per interrogare l'indice con la sintassi `<campo> <termini o "frase">`:

```bash
mvn exec:java -Dexec.mainClass="it.uniroma3.idd.hw3.ConsoleSearcher"
```

### 3. Esecuzione Automatica delle 12 Query di Test (`LuceneSearchTest`)
Esegue la suite di test JUnit 5 che verifica l'indicizzazione dei 15 file e tutte le 12 query sperimentali:

```bash
mvn test
```

---

## Riepilogo delle 12 Query di Test

| # | Query inserita da console | Query Lucene (`QueryParser`) | Documenti restituiti (`TopDocs`) | Score BM25 (Top-1) | Obiettivo del test |
|:-:|---|---|---|:-:|---|
| **1** | `nome marte` | `nome:marte` | `esplorazione-spaziale-marte.txt` | `1.0950` | Segmentazione sui trattini (`-`) tramite `wordDelimiterGraph` |
| **2** | `nome "letteratura italiana"` | `nome:"letteratura italiana"` | `letteratura italiana novecento.txt` | `2.1901` | `PhraseQuery` su nome di file con spazi bianchi |
| **3** | `nome "basi di dati"` | `nome:"basi di dati"` | `basi-di-dati-relazionali.txt` | `2.6912` | Conservazione della preposizione `"di"` (assenza di stopword sul campo `nome`) |
| **4** | `nome neurali deep` | `nome:neurali nome:deep` | `RetiNeurali_DeepLearning.txt` | `1.9332` | Suddivisione *CamelCase* e underscore prima del `lowercase` |
| **5** | `contenuto "indice invertito"` | `contenuto:"indic invertit"` | `indice_invertito_lucene.txt` | `2.9390` | `PhraseQuery` combinata con stemming di `ItalianAnalyzer` |
| **6** | `contenuto "sistema operativo"` | `contenuto:"sistem operativ"` | `sistemi_operativi_kernel.txt` | `2.0287` | Allineamento morfologico singolare/plurale in una `PhraseQuery` |
| **7** | `contenuto crittografia` | `contenuto:crittograf` | `CrittografiaModerna.txt` | `1.7240` | `TermQuery` singola ad alta *term frequency* locale |
| **8** | `contenuto energie rinnovabili` | `contenuto:energ contenuto:rinnovabil` | `energia_rinnovabile_italia.txt` | `2.9082` | Recupero congiunto di occorrenze singolari e plurali tramite stemming |
| **9** | `contenuto +apprendimento +modello` | `+contenuto:apprendiment +contenuto:modell` | `machine-learning-supervisionato.txt` | `1.7436` | Operatore booleano obbligatorio `+` (`BooleanClause.Occur.MUST`) |
| **10** | `contenuto +architettura +memoria` | `+contenuto:architettur +contenuto:memor` | `architettura_calcolatori.txt` | `1.8177` | Clausole obbligatorie e rimozione dell'elisione su `"L'architettura"` |
| **11** | `contenuto architettura dati` | `contenuto:architettur contenuto:dati` | 8 documenti (`IngegneriaDeiDati_Pipeline.txt`, `architettura_calcolatori.txt`, ...) | `1.1604` | Graduatoria multi-documento **BM25** e soglia di stemming (`len < 6` su `"dati"`) |
| **12** | `contenuto il dello nella` | *(vuota)* | Nessun documento (`0 risultati`) | — | Rimozione integrale delle stopword italiane da parte di `ItalianAnalyzer` |

