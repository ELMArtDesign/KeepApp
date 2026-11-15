# Licenze delle Dipendenze e degli Asset (LICENSES)

Tutte le dipendenze utilizzate in **KeepApp (Fase 0 & Fase 1A)** sono rilasciate con licenze open-source permissive compatibili (**Apache License 2.0**, **MIT** o **BSD**). Nessuna libreria con licenza GPL, LGPL o EPL è inclusa o collegata nell'applicazione Android o nei moduli JVM.

---

## 1. Librerie Android, Kotlin e Test JVM (Moduli `:core-domain`, `:platform-android`, `:logger`, `:app`, `:replay`)

| Libreria / Artefatto | Versione | Modulo | Licenza | Link Licenza |
| :--- | :--- | :--- | :--- | :--- |
| **Kotlin Standard Library** (`org.jetbrains.kotlin:kotlin-stdlib`) | `2.2.20` | Tutti | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Kotlinx Coroutines** (`org.jetbrains.kotlinx:kotlinx-coroutines-core`, `-android`) | `1.8.1` | Tutti | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Kotlin Test + TestNG** (`org.jetbrains.kotlin:kotlin-test-testng`) | `2.2.20` | `:core-domain` (test) | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **TestNG** (`org.testng:testng`) | `7.10.2` | `:core-domain` (test) | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Core KTX** (`androidx.core:core-ktx`) | `1.15.0` | `:platform-android`, `:logger`, `:app` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Lifecycle** (`androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-service`) | `2.8.7` | `:logger`, `:app` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Activity Compose** (`androidx.activity:activity-compose`) | `1.9.3` | `:logger`, `:app` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Jetpack Compose** (`androidx.compose:compose-bom`, `ui`, `material3`) | `2024.10.01` | `:logger`, `:app` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Media3 / ExoPlayer** *(usato solo in `:logger` Fase 0 per il test F7; assente in `:app` Fase 1A)* (`androidx.media3:media3-exoplayer`, `media3-common`) | `1.4.1` | `:logger` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |

---

## 2. Librerie Python per lo Script di Analisi Offline (`tools/read_session.py`)

Queste librerie vengono eseguite esclusivamente sul PC di sviluppo per leggere e graficare le sessioni registrate:

| Libreria Python | Licenza | Link Licenza |
| :--- | :--- | :--- |
| **NumPy** | BSD 3-Clause License | [BSD-3-Clause](https://numpy.org/doc/stable/license.html) |
| **SciPy** | BSD 3-Clause License | [BSD-3-Clause](https://scipy.org/scipylib/license.html) |
| **Matplotlib** | PSF-based / BSD-compatible License | [Matplotlib License](https://matplotlib.org/stable/users/project/license.html) |

---

## 3. Asset Audio e Pattern Ritmici

* Tutti i segnali acustici e i timbri percussivi utilizzati nella Fase 0 e nella Fase 1A (impulsi del metronomo, cassa ottimizzata per conduzione ossea, rullante, charleston, accento di rinforzo e chirp di calibrazione latenza) sono **generati matematicamente via codice a runtime** direttamente dall'applicazione (sintesi PCM in memoria) e sono pertanto rilasciati sotto la medesima licenza **Apache License 2.0** del progetto.
* I pattern ritmici in formato JSON (`assets/patterns/*.json`) sono opere originali create per il progetto KeepApp e rilasciate sotto licenza **Apache License 2.0**.
