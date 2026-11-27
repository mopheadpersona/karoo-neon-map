# Fonts for the data fields

Free look-alikes, because the game's own fonts are off limits. All under the SIL Open Font
Licence (the `OFL-*.txt` files), fetched from https://github.com/google/fonts on 27.11.2026. The font files are not in git; fetch them again with:

    cd extension/fonts
    for f in barlowcondensed/BarlowCondensed-SemiBold.ttf barlowcondensed/BarlowCondensed-Bold.ttf \
             bebasneue/BebasNeue-Regular.ttf bungee/Bungee-Regular.ttf russoone/RussoOne-Regular.ttf; do
        curl -sLO "https://raw.githubusercontent.com/google/fonts/main/ofl/$f"
    done

| File | For | Size |
|---|---|---|
| BarlowCondensed-Bold.ttf, -SemiBold.ttf | labels and numbers | 110 KB each |
| BebasNeue-Regular.ttf | labels and numbers, the other candidate | 61 KB |
| Bungee-Regular.ttf | the cash-style numbers | 119 KB |
| RussoOne-Regular.ttf | the cash-style numbers, the other candidate | 39 KB |

The build puts this whole folder into the app (`app/build.gradle.kts`), licences included.
