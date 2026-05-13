# CUCo Scanner

App Android que lê uma foto do ecrã de bloqueio CUCo (Inforlandia), extrai
os 3 campos hexadecimais (`Machine Serial Number`, `Certified Time`,
`Usage Counter`) via OCR on-device, e abre
[cuco.inforlandia.pt/ucode/](https://cuco.inforlandia.pt/ucode/) numa
WebView com os campos já preenchidos. Os zeros à esquerda do Usage
Counter são removidos (ex. `00000001` → `1`).

## Como funciona

1. **MainActivity** — dois botões: *Tirar foto* (câmara) ou *Escolher da
   galeria*.
2. **ScanActivity** — corre [ML Kit Text
   Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
   on-device sobre a imagem e usa `CucoOcrParser` para isolar os 3
   valores hex.
3. **WebViewActivity** — carrega o site CUCo e injecta JavaScript que
   procura os 3 inputs por keywords (`serial`, `certified`, `usage`) em
   `name`/`id`/`placeholder`/`aria-label`/label-associado/sibling e
   atribui os valores, disparando eventos `input`/`change`.

## Build

Requer Android Studio (Hedgehog ou mais recente) ou um ambiente com
acesso a `dl.google.com` / `maven.google.com`.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para os testes do parser (JVM, não precisa de dispositivo):

```bash
./gradlew :app:testDebugUnitTest
```

## Notas

- minSdk 24, targetSdk 34.
- Permissões: `INTERNET` (WebView) e `CAMERA` (pedida a runtime antes
  de tirar foto).
- Se o OCR não conseguir identificar os 3 campos, a app mostra um Toast
  a pedir nova foto e volta ao ecrã inicial.
- Se a estrutura do form em `cuco.inforlandia.pt/ucode/` mudar e a
  injecção JS deixar de encontrar algum input, ajusta as keywords ou
  selectores em `FillFormJs.kt`.
