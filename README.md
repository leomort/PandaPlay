# PandaPlay 🎮

Emulador multiplataforma baseado em cores **libretro**. Esta primeira versão é o
MVP Android com o core **mGBA**: roda **Game Boy, Game Boy Color e Game Boy Advance**
em celular, tablet e Android TV / Google TV.

## O que já funciona

- Biblioteca de jogos (importa `.gba`, `.gb`, `.gbc` pelo seletor de arquivos, sem pedir permissão)
- Save do jogo (SRAM) automático: a cada 15 s e ao sair
- Importar/exportar save `.sav` (segure o dedo no jogo, ou aperte e segure OK na TV)
- Save state rápido (💾 salvar / 📂 carregar)
- Acelerar 3x (⏩)
- Relógio em tempo real para Ruby/Sapphire/Emerald e Gold/Silver/Crystal (o mGBA usa a hora do aparelho)
- Controle virtual na tela no formato GBA; **some sozinho** quando um controle físico conecta
- Controle Bluetooth/USB (Xbox, DualSense, 8BitDo...) com direcional ou analógico
- Teclado e controle remoto da TV
- Layout retrato (jogo em cima, controle embaixo) e paisagem (tela cheia)
- Aparece no menu da Android TV (banner próprio)

## Como rodar

1. Instale o **Android Studio** (versão recente, com JDK 17).
2. Baixe o core mGBA (uma vez só):
   - Windows (PowerShell): `.\scripts\baixar-cores.ps1`
   - Linux/macOS/Git Bash: `./scripts/baixar-cores.sh`
3. Abra a pasta `PandaPlay` no Android Studio. Na primeira abertura ele baixa o Gradle
   e as dependências (o arquivo `gradle-wrapper.jar` é gerado automaticamente; se ele
   pedir, aceite "Use Gradle wrapper" ou rode `gradle wrapper` uma vez).
4. Conecte o celular (depuração USB) ou a TV Android (depuração por rede) e clique em ▶ Run.
5. No app: **+ Importar jogo** → escolha o arquivo do seu cartucho.

## Compilar sem PC (GitHub Actions)

Ao enviar o projeto para um repositório no GitHub, o arquivo
`.github/workflows/build-apk.yml` baixa o core, compila e publica o APK em
**Releases**. Pelo celular: abra o repositório → Releases → baixe o `.apk` → instale.

## Comandos

| Ação | Controle Bluetooth | Teclado | Controle remoto TV |
|---|---|---|---|
| Direcional | D-pad ou analógico esquerdo | Setas | Setas |
| A / B | A / B (posição estilo Nintendo) | X / Z | OK = A |
| L / R | LB / RB | A / S | — |
| START / SELECT | Start / Select | Enter / Backspace | — |
| Acelerar (liga/desliga) | RT (R2) | Tab | — |
| Salvar estado | LT (L2) | F1 | — |
| Carregar estado | L3 (apertar analógico) | F4 | — |
| Sair do jogo | Voltar / botão Home do controle | Esc | Voltar |

## Estrutura

```
app/src/main/java/com/pandaplay/emu/
  MainActivity.kt   biblioteca, importar jogos e saves
  GameActivity.kt   tela do jogo, input, saves, acelerar
  GameStorage.kt    onde ficam jogos, saves e states
  InputMapper.kt    teclado/TV -> botões e atalhos
  GbaPadConfig.kt   layout do controle virtual
```

## Próximos passos sugeridos

1. Testar em aparelho real e ajustar o tamanho do controle virtual
2. Capas dos jogos (ScreenScraper / libretro-thumbnails)
3. Core **melonDS** (Nintendo DS) com layout de tela dupla
4. Tela de mapeamento de botões por controle
5. Troca de Pokémon entre dois aparelhos (link cable do mGBA pela rede)
6. Backup de saves na nuvem (Supabase)

## Aviso legal

O app **não inclui jogos nem BIOS**. Use apenas arquivos extraídos dos seus próprios
cartuchos (ex.: com um leitor como o GB Operator). Não use nomes, logos ou imagens de
marcas registradas no nome, ícone ou divulgação do app.

**Licença:** LibretroDroid é GPLv3 e o mGBA é MPL 2.0, então este projeto deve ser
distribuído como **GPLv3** (código aberto).
