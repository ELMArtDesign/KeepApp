# Licenze delle Dipendenze e degli Asset (LICENSES)

Tutte le dipendenze utilizzate in **KeepApp (Fase 0 – Data Logger & Hardware Test Suite)** sono rilasciate con licenze open-source permissive compatibili (**Apache License 2.0**, **MIT** o **BSD**). Nessuna libreria con licenza GPL o LGPL è inclusa o collegata nell'applicazione Android.

---

## 1. Librerie Android e Kotlin (Modulo `:core-domain` e `:app`)

| Libreria / Artefatto | Versione | Licenza | Link Licenza |
| :--- | :--- | :--- | :--- |
| **Kotlin Standard Library** (`org.jetbrains.kotlin:kotlin-stdlib`) | `2.0.21` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Kotlinx Coroutines** (`org.jetbrains.kotlinx:kotlinx-coroutines-core`, `-android`) | `1.8.1` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Core KTX** (`androidx.core:core-ktx`) | `1.15.0` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Lifecycle** (`androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-service`) | `2.8.7` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Activity Compose** (`androidx.activity:activity-compose`) | `1.9.3` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Jetpack Compose** (`androidx.compose:compose-bom`, `ui`, `material3`) | `2024.10.01` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Media3 / ExoPlayer** *(usato da Milestone 4)* (`androidx.media3:media3-exoplayer`, `media3-session`) | `1.4.1` | Apache License 2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |

---

## 2. Librerie Python per lo Script di Analisi Offline (`tools/read_session.py` - Milestone 5)

Queste librerie vengono eseguite esclusivamente sul PC di sviluppo per leggere e graficare le sessioni registrate:

| Libreria Python | Licenza | Link Licenza |
| :--- | :--- | :--- |
| **NumPy** | BSD 3-Clause License | [BSD-3-Clause](https://numpy.org/doc/stable/license.html) |
| **SciPy** | BSD 3-Clause License | [BSD-3-Clause](https://scipy.org/scipylib/license.html) |
| **Matplotlib** | PSF-based / BSD-compatible License | [Matplotlib License](https://matplotlib.org/stable/users/project/license.html) |

---

## 3. Asset Audio di Prova

* Tutti i segnali acustici di test utilizzati nella Fase 0 (impulsi del metronomo a ~1 kHz, chirp di calibrazione latenza e tracce sintetiche per il test di velocità `Media3` e `DynamicsProcessing`) sono **generati matematicamente via codice a runtime** direttamente dall'applicazione (sintesi PCM in memoria) e sono pertanto rilasciati sotto la medesima licenza **Apache License 2.0** del progetto.
