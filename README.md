# Löpar

Enkel löpar-app för Android: Start, Stop, Clear values, TIME (mm:ss) och DISTANCE (meter via GPS).

## Ladda ner
Gå till **Releases** (https://github.com/seijokeseijoke-dev/lopar/releases), ladda ner `lopar.apk` på telefonen och installera.
Android frågar om du vill tillåta installation från okänd källa första gången. Tillåt för Chrome/Filer.

## Hur den byggs
`index.html` är appen. Vid varje push till `main` bygger GitHub Actions en APK (WebView-skal) och lägger den som en ny release.
