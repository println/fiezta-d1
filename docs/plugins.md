# Plugins por URL

Referência para quem escreve um plugin do Fiezta D1. Histórico da decisão:
`docs/plans/35-plugins-por-url.md`.

## O que é um plugin

Uma pasta em `app/src/main/assets/plugins/<id>/` (plugin padrão, embutido no APK) ou em
`filesDir/plugins/<id>/` no aparelho (plugin instalado pelo usuário), contendo um
`manifest.json` e os scripts que ele referencia. A URL da página decide qual plugin (e qual
script dentro dele) roda — não há detecção de site por outro meio.

```
app/src/main/assets/plugins/youtube/
  manifest.json
  watch.events.js
  shorts.events.js
  voice-search.js
  ad-prune.tweak.js
  player-scroll.tweak.js
  unmute-player.tweak.js
```

Nomes de script são relativos à pasta do plugin: sem `/` inicial, sem `..`, sem `\`.

## `manifest.json`

```json
{
  "id": "youtube",
  "name": "YouTube",
  "version": 1,
  "rules": [
    {
      "match": ["https://youtube.com/shorts/*", "https://*.youtube.com/shorts/*"],
      "events": "shorts.events.js"
    },
    {
      "match": ["https://youtube.com/*", "https://*.youtube.com/*"],
      "tweaks": [
        { "script": "ad-prune.tweak.js", "runAt": "start", "requires": "adblock", "env": "both" },
        {
          "script": "player-scroll.tweak.js", "runAt": "loaded", "rerun": "urlChange", "env": "car",
          "option": { "key": "pref_youtube_scroll_to_player", "default": true }
        }
      ]
    }
  ]
}
```

| Campo | Tipo | Regra |
|---|---|---|
| `id` | string | casa `^[a-z0-9][a-z0-9_-]{0,31}$`; é o nome da pasta |
| `name` | string | não vazio |
| `version` | int | ≥ 1 |
| `rules` | lista | ≥ 1 regra |
| `fallback` | bool? | `false`. Só em plugin padrão. Marca o plugin de fallback: não pode ser desativado nem removido, e é mantido sempre **no fim** da pilha, para não passar na frente dos plugins de site |
| `voice` | objeto? | ausente. Candidata o plugin a ouvinte de voz do carro; exatamente um de `resource` ou `search` (`id` de um motor em `assets/search/`) |
| `voice.resource` | string? | caminho de busca do site, começando por `/` e com um `{searchTerms}`. O domínio vem das regras `match` do plugin, que têm de convergir para um só |
| `voice.script` | string? | script que o app injeta no documento que ele abriu para a busca por voz; exige `resource`. É o plugin que decide o que fazer ao chegar |
| `rules[].match` | lista de string | ≥ 1; padrão de URL, ver abaixo |
| `rules[].events` | string? | nome do script de eventos |
| `rules[].tweaks` | lista? | ver tabela abaixo |

Uma regra sem `events` e sem `tweaks` é inválida.

Item de `tweaks`:

| Campo | Tipo | Padrão | Regra |
|---|---|---|---|
| `script` | string | — | obrigatório |
| `runAt` | `"committed"` \| `"domReady"` \| `"loaded"` \| `"idle"` \| `"route"` | `"loaded"` | Estágio do documento em que o script roda, ver "Estágios de documento". São os estágios do `webviewex`, um para um; `"start"` e `"ready"` continuam aceitos como nomes antigos de `committed` e `loaded` |
| `rerun` | `"load"` \| `"urlChange"` | `"load"` | `load`: no máximo uma vez por carregamento de página. `urlChange`: de novo a cada mudança de URL que case e seja diferente da última em que rodou |
| `requires` | `"adblock"`? | ausente | pré-condição de recurso do app; hoje só existe `adblock` (`SettingsStorage.isAdBlockEnabledForHost`) |
| `env` | `"car"` \| `"phone"` \| `"both"` | `"both"` | onde o tweak roda |
| `option` | `{ "key": string?, "default": bool }`? | ausente | personaliza a chave e o padrão do liga/desliga do tweak |

- **Todo tweak tem liga/desliga**, com ou sem `option`. Sem `option`, a chave é
  `pref_plugin_<id>_<script>` e o padrão é ligado; `option` só serve para escolher outra
  chave (por exemplo uma já existente nas configurações) ou outro padrão.
- Um tweak com `requires` não atendido não roda e aparece **desabilitado** na tela: com o
  ad block desligado, não dá para ligar o `ad-prune.tweak.js`.
- `option.key` só é honrada em **plugin padrão**; em **plugin instalado** a chave
  efetiva é sempre `pref_plugin_<id>_<script sem .js, com . e - trocados por _>`
  (`InstalledOptionKey.of`) e o default é forçado para `false`.
- Campo desconhecido em qualquer nível é ignorado.
- Eventos são sempre do carro; não têm `env`.

### Estágios de documento

`assets/webviewex/bootstrap.js` é injetado uma vez por documento e publica os estágios reais da página, que não dependem dos callbacks do `WebViewClient` (esses mentem em SPA):

| `runAt` | Quando |
|---|---|
| `committed` | documento comitado, DOM ainda vazio |
| `domReady` | `document.readyState` chegou a `interactive` |
| `loaded` | a página terminou de carregar, e a cada mudança de URL na mesma página |
| `idle` | o DOM ficou 400 ms sem mutação depois de `domReady` (teto de 8 s); é onde um SPA como o YouTube está de fato pronto |
| `route` | `pushState`, `replaceState`, `popstate` ou `hashchange` |

O vocabulário é o do `webviewex`: o plugin declara o mesmo estágio que a camada publica, sem
tradução no meio. O motor de plugins ouve **só** o ciclo de vida do documento — cada estágio
chega com a URL daquele documento, e é `committed` que zera o que já rodou nesta carga. Em
`route`, além dos tweaks de `route`, voltam a rodar os de `loaded` que declaram
`rerun: "urlChange"`.

- Um script só é entregue ao documento para o qual foi pedido; se a página trocou no meio, ele é descartado.
- Um tweak só é marcado como executado quando a injeção de fato aconteceu (`rerun: "load"` não é consumido por uma passagem que caiu no vazio).
- O bootstrap oferece `window.__webviewex.waitFor(selector, timeoutMillis)` (Promise que resolve com o elemento ou `null`) e `window.__webviewex.once(key, fn)` (idempotência por documento).
- `fiezta.on('renderModeChanged', fn)` é opcional: recebe `'foreground'` ou `'background'` quando a tela do carro é anexada ou destacada. Um site com modo áudio de verdade pode usá-lo; quem não implementa usa o comportamento genérico da camada.

### Padrão de URL (`UrlMatch`)

- Casa a **URL inteira**, sem normalizar.
- `*` casa qualquer sequência, inclusive vazia. Nenhum outro caractere é especial (`?` é
  literal).
- Comparação **case-insensitive**.
- Um padrão **universal** (que casaria qualquer URL, ex.: `*`) só é aceito em **plugin
  padrão**; em plugin instalado é erro de validação — evita que um plugin de terceiros
  sequestre o app inteiro.

### Resolução

- **Destino da busca por voz**: o ouvinte manda sempre a URL de busca, esteja qual página
  estiver aberta. Com `resource`, o destino é o domínio do plugin mais o recurso, com os termos
  no lugar de `{searchTerms}`; com `search`, é a URL do motor. Sem ouvinte, vale o motor da
  página aberta. Chegando lá, o app injeta o `voice.script` daquele plugin no documento que ele
  próprio abriu — a injeção é o sinal, e por isso o script não precisa descobrir se aquela
  página é a busca dele. O que fazer ao chegar é do plugin: no YouTube, `voice-search.js` espera
  o primeiro resultado não patrocinado aparecer e o abre.
- **Ouvinte de voz**: entre os plugins ativos que declaram `voice`, vale o escolhido pelo
  usuário (`pref_voice_plugin`, tela "Voice listener" no menu de plugins). Escolha ausente,
  ou plugin escolhido que saiu da pilha, cai no **último candidato**, que é o de
  `fallback`. Não existe "nenhum".
- **Eventos**: dado a pilha de plugins ativos (ordem de ativação, do primeiro ao
  último), o primeiro plugin da pilha com uma regra de `events` cujo `match` case com a
  URL atual vence. **Não** desce para o próximo plugin por especificidade — o primeiro
  da pilha que tiver *alguma* regra de eventos casando já responde.
- **Tweaks**: todos os tweaks de todas as regras que casam, de todos os plugins da
  pilha, com `env` compatível, sem repetir o mesmo `(pluginId, script)`.

## Núcleo e barramento de eventos (Plano 66)

Uma regra do manifesto pode ter `core`: um script ou uma lista de scripts (`"core": ["core.common.js", "core.mobile.js"]`). O motor avalia o `core` da primeira regra de cada plugin que casa com a URL, uma vez por documento, nos dois ambientes, **antes** de qualquer tweak ou script de eventos. O núcleo só observa a página; quem age são os pedaços.

O barramento `window.fieztaPage` (`assets/plugins/fiezta-page.js`) é injetado antes dos núcleos:

- `emit(nome, detail, elements, { moment })` — o nome tem prefixo `<id do plugin>:`; sem prefixo, lança erro.
- `on(nome, (detail, elements) => …)` devolve a função de cancelamento. Evento de estado é pegajoso: quem assina tarde recebe o último valor. `moment: true` não é pegajoso. `emit` com `detail` igual ao anterior é descartado.
- `last(nome)` e `lastElements(nome)` leem o último valor. Em script de eventos, assinar com `fiezta.listen(nome, fn)` — o `fiezta.reset()` cancela.

Eventos do YouTube (`core.common.js` + `core.mobile.js`/`core.desktop.js`, mesmo vocabulário nos dois sites): `pageChanged`, `trackChanged`, `playerStateChanged`, `adChanged`, `navigationChanged`, `queueChanged`, `playerVisibilityChanged`, `searchResultsChanged`, `userInteracted` (momento). Seletor e evento nativo do site só aparecem nos núcleos. `dialogChanged` ("ainda está assistindo") ainda não existe: o diálogo não foi reproduzido.

## O contrato de controle remoto

Referência completa: `docs/plans/44-contrato-de-controle-remoto.md`. Duas listas fechadas — o
que o app precisa saber e o que ele sabe pedir — cada uma com uma base que o `WebView` entrega
sozinho e uma sobrescrita que o plugin pode declarar. Um plugin que não declara nada continua
funcionando: tudo cai na base.

### As dez informações

| # | Informação | Base, sem plugin | Plugin sobrescreve |
|---|---|---|---|
| 1 | está tocando | elemento ativo da página | raramente |
| 2 | posição | elemento ativo | não |
| 3 | duração | elemento ativo | não |
| 4 | velocidade | elemento ativo | não |
| 5 | identidade da faixa | `url + título + autor` | `id` do metadata |
| 6 | título | `navigator.mediaSession`, senão título da página | metadata |
| 7 | autor | `navigator.mediaSession`, senão o host | metadata |
| 8 | arte | `navigator.mediaSession` | metadata |
| 9 | há próximo, há anterior | handlers que o site registrou | `setAvailable` |
| 10 | fila | histórico do `WebView` | `setQueueProvider` |

### Os dez comandos

| # | Comando | Base, sem plugin | Plugin sobrescreve quando |
|---|---|---|---|
| 1 | tocar | `play()` no elemento ativo | o site exige o botão dele |
| 2 | pausar | `pause()` em todos os elementos | idem |
| 3 | parar | pausa e libera o player | nunca |
| 4 | próximo | handler que o site registrou | é DOM: botão do player, rolagem do feed |
| 5 | anterior | handler que o site registrou | idem |
| 6 | reiniciar a faixa | `currentTime = 0`; sem mídia, `reload()` | raramente |
| 7 | ir para uma posição | `currentTime` | raramente |
| 8 | avançar, recuar | ±10 s | o site tem gesto próprio |
| 9 | pular para um item | navegar no histórico | a lista é do site |
| 10 | tocar a partir de uma busca | busca do site atual | o site tem fluxo próprio |

Quem decide entre 5 e 6 é o app, pela posição: passados 3 s desde o início, "anterior" vira
"reiniciar" (`PreviousAction`, `features/domain/core/media/`). O plugin nunca precisa dessa
regra — para ele, anterior é só ir para a anterior.

### A fila da pagina e a fila de execucao

`queue` e `history` sao coisas diferentes. A **fila de execucao** e o que a pagina oferece a
volta da faixa atual — e so isso; os recem-tocados nunca entram nela, vivem na aba Historico.

- `list` e playlist de verdade: vira a fila **e** a aba Playlist.
- `stream` e fluxo: vira a fila, e a aba Playlist fica vazia. Declarar `{ shape: 'stream' }`
  sem `entries` basta — o runtime preenche a faixa atual a partir da metadata ja resolvida.
  E o que `watch.events.js` e `shorts.events.js` fazem, porque essas paginas nao listam
  vizinho: quem move entre faixas ali sao os botoes do player, pelo `setAvailable`.
- `none` (ou nenhum provider) deixa a fila para o historico do `WebView`, que e a base para
  navegar sem video.

### `setQueueProvider` e os três formatos

```js
fiezta.setQueueProvider(function () {
  return { shape, title, entries, cursor };
});
```

| Campo | |
|---|---|
| `shape` | `'list'`, `'stream'` ou `'none'`; ausente ou `null` vale `'none'` |
| `title` | nome da fila (a playlist, por exemplo); opcional |
| `entries` | `[{ title, subtitle, artwork }]`, na ordem de reprodução |
| `cursor` | índice do item atual em `entries`; `-1` se desconhecido |

| `shape` | Quando usar | O que o app publica |
|---|---|---|
| `'list'` | existe lista conhecida com posição (playlist, álbum) | a lista da página |
| `'stream'` | há próximo, mas não há lista adiante (feed) | o que já tocou (recém-tocados) + o atual |
| `'none'` | a página não tem fila própria | o histórico do navegador |

Num `'stream'`, `entries` traz só o item atual — o passado é do app. O `id` de cada item da fila
publicada é atribuído pelo app (o índice na lista completa), não pelo plugin; é esse índice que
volta no evento `queueItem`. A informação 9 (há próximo/anterior) é independente da fila: um feed
tem próximo sem ter lista, e isso é normal.

### `id` no metadata

```js
fiezta.setMetadataProvider(function () {
  return { id, title, artist, artwork };   // id é opcional
});
```

Sem `id`, a identidade da faixa (informação 5) é `url + título + autor`; um `id` estável do
próprio site é preferível quando título/autor podem se repetir entre faixas diferentes (por
exemplo, dois vídeos com o mesmo título).

### Eventos novos

Além dos já existentes (`nextClick`, `previousClick`, `nextLongPress`, `previousLongPress`,
`play`, `pause`):

- `restart` — comando 6; opcional. Sem handler, a base resolve (`currentTime = 0` com mídia,
  `reload()` sem).
- `seekTo` — recebe a posição em segundos, como string. Sem handler, a queda vai para o
  handler `seekto` que o próprio site registrou em `navigator.mediaSession` e, sem ele, para o
  `currentTime` do elemento eleito.
- `queueItem` — comando 9; recebe o índice do item em `entries` (o mesmo que o app atribuiu ao
  publicar a fila). Sem handler, o app tenta os recém-tocados e depois o histórico do `WebView`.

### De-para entre contextos

O vocabulário é fechado — play, pause, stop, próximo, anterior, seek, seekBy, restart, item da
fila — e cada site traduz o seu mundo para eles. Acrescentar um site não pode exigir mudança de
Kotlin.

| Site | faixa | próximo | fila | seek |
|---|---|---|---|---|
| YouTube playlist | vídeo | próximo da lista | a playlist, com cursor | na timeline |
| YouTube watch | vídeo | up-next | recém-tocados + atual + 1 | na timeline |
| Instagram reels | reel | rolar para o próximo | não existe adiante | dentro do reel |
| Twitter/X | vídeo do tweet | próximo vídeo da timeline | não existe adiante | dentro do vídeo |
| Notícia, sem mídia | a página | não tem | o histórico do navegador | n/a |

### Exemplos (não são plugins existentes)

Os três a seguir ilustram cada formato de fila com um site genérico (`meusite`) — não são
seletores de YouTube, Instagram ou Twitter, e não devem ser copiados como se fossem.

**`'list'` — uma playlist**

```js
(function () {
  function currentPlaylist() {
    return window.__meusitePlaylist || { items: [], index: -1 };
  }

  fiezta.setQueueProvider(function () {
    var playlist = currentPlaylist();
    return {
      shape: 'list',
      title: playlist.name,
      cursor: playlist.index,
      entries: playlist.items.map(function (item) {
        return { title: item.title, subtitle: item.author, artwork: item.thumbnail };
      })
    };
  });

  fiezta.on('queueItem', function (index) {
    var playlist = currentPlaylist();
    var item = playlist.items[Number(index)];
    if (item) { location.href = item.url; }
  });
})();
```

**`'stream'` — um feed**

```js
(function () {
  function currentItem() {
    return window.__meusiteFeedItem || null;
  }

  fiezta.setMetadataProvider(function () {
    var item = currentItem();
    if (!item) { return null; }
    return { id: item.id, title: item.title, artist: item.author, artwork: item.thumbnail };
  });

  fiezta.setQueueProvider(function () {
    var item = currentItem();
    if (!item) { return null; }
    return {
      shape: 'stream',
      entries: [{ title: item.title, subtitle: item.author, artwork: item.thumbnail }],
      cursor: 0
    };
  });

  fiezta.on('nextClick', function () { window.scrollBy(0, window.innerHeight); });
  fiezta.on('previousClick', function () { window.scrollBy(0, -window.innerHeight); });
})();
```

**`'none'` — um site comum**

Nada declarado: sem `setQueueProvider`, a fila publicada é o histórico do `WebView`; sem
`setMetadataProvider`, título e autor vêm de `navigator.mediaSession` ou do título da página e do
host; sem handlers de `nextClick`/`previousClick`, não há próximo/anterior. O app cobre os dez
comandos e as dez informações sozinho.

## Runtime JS (`fiezta`)

Todo script de eventos roda depois de `assets/plugins/fiezta-runtime.js`, que expõe
`window.fiezta` (`window.fiesta` continua existindo como alias do mesmo objeto, para plugins instalados antes da renomeação):

- `fiezta.on(nome, fn)` — nome é um dos eventos do carro:
  `nextClick`, `previousClick`, `nextLongPress`, `previousLongPress`, `play`, `pause`
  (também disparado pelo stop do painel). Sem handler para `play`/`pause`, o app toca ou pausa
  o `<video>` da página. Registrar de novo substitui o handler.
- `fiezta.setAvailable({ next, previous })` — reporta se cada tecla tem efeito agora. A queda
  para o site é por chave: a que o plugin informar vale, e a que ele **omitir** vem do que o
  site registrou em `navigator.mediaSession.setActionHandler('nexttrack'/'previoustrack', fn)`,
  capturado pelo runtime. Omitir não é o mesmo que responder `false`.
- `fiezta.setMetadataProvider(fn)` — `fn()` devolve `{ title, artist, artwork }` (ou `null`) e é
  consultada a cada segundo. A metadata da leitura é resolvida nesta ordem: o provider do
  plugin → `navigator.mediaSession.metadata` (título, artista e a maior `artwork`) → título da
  página e host, campo a campo: o que o provider deixar vazio vem do site, e só então da
  página. Aceita um `id` opcional (informação 5 do contrato de controle remoto, ver
  acima); sem ele a identidade da faixa é `url + título + autor`.
- `fiezta.setQueueProvider(fn)` — `fn()` devolve `{ shape, title, entries, cursor }` (ou `null`,
  equivalente a `shape: 'none'`); ver "O contrato de controle remoto" acima para os três
  formatos. Consultada a cada segundo, publicada via `mediacontrol.onQueue(json)` só quando o
  JSON muda. `MediaQueueJson` (`shared/media`) faz o parse do lado Kotlin.
- `fiezta.matchesVoiceQuery(texto)` — compara `texto` com a busca por voz pendente
  (`window.__fieztaVoiceSearch.query`) normalizando acentos, pontuação e caixa; usado por
  scripts de evento para não perder a busca quando o site corrige a ortografia do termo.
- Uma leitura completa da mídia (título, artista, capa, posição, duração, velocidade,
  tocando/pausado e as duas teclas de faixa) não precisa de plugin: o runtime elege o elemento
  de mídia ativo (`window.__fieztaMedia()`, exposto também para scripts de evento) e publica a
  leitura via `mediacontrol.onReading(json)` sempre que ela muda, e no mínimo a cada 5s enquanto
  há mídia. Sem elemento ativo mas com `navigator.mediaSession.playbackState === 'playing'`
  (vídeo dentro de iframe), a leitura reporta tocando sem posição - o card para de mentir
  "pausado" mesmo sem poder controlar. A leitura também traz `isTrack` (Plano 58): verdadeiro
  se o elemento eleito está audível agora, ou o site diz `playbackState === 'playing'`, ou algum
  elemento já foi audível nesta mesma `location.href` (pegajoso por URL, zerado só na troca de
  `href` — `fiezta.reset()` não mexe nisso). Trocar de `href` saindo de uma faixa mantém
  `isTrack` por até 15 s, para a troca de faixa do YouTube não virar página comum até o áudio
  do próximo vídeo começar. Ter `<video>`/`<audio>` na página não basta, nem
  `mediaSession.metadata`/handlers do site, nem `fiezta.on`/`setAvailable` do plugin `generic`:
  uma página sem nada tocando é `isTrack: false`, mesmo cheia de prévias mudas (o exemplo é a
  página de vídeos de um canal do YouTube). `MediaReadingJson` (`shared/media`) faz o parse do
  lado Kotlin; `core/media` (`NowPlaying`/`NowPlayingProjection`) é quem decide o que publicar.
- `fiezta.toast(texto)` — toast do Android.
- `fiezta.setInterval(fn, ms)` — como `window.setInterval`, mas o timer é limpo
  automaticamente por `fiezta.reset()` quando o plugin de eventos troca.
- `fiezta.dispatch(nome, argumento?)` — chamado pelo lado Kotlin (`PluginInjector.dispatch`), não
  pelo plugin.
- `window.__fieztaVoiceSearch` — contexto opcional de uma busca por voz pendente, com `id`,
  `generation` e `query`. O plugin confirma que a URL de resultados corresponde a `query` antes
  de selecionar qualquer resultado e confirma cada marco com
  `fieztaplugins.onVoiceSearchProgress(id, generation, stage)`, onde `stage` é `confirmed`,
  `searching`, `result`, `playRequested` ou `failed`. O contexto é invalidado em cancelamento,
  troca de documento e fim do pedido; confirmações de outro id ou geração são ignoradas. Plugin
  que não reporta nada apenas deixa a busca por voz expirar no tempo do app.
- `fiezta.reset()` — chamado pelo lado Kotlin ao trocar de script de eventos; limpa também o
  `queueProvider` e o último JSON de fila publicado.

Um script de tweak não usa `fiezta` — é JS livre, avaliado uma vez (ou a cada URL,
conforme `rerun`) na página.

## Onde ficam

| O quê | Onde |
|---|---|
| plugins padrão | `app/src/main/assets/plugins/<id>/` |
| runtime | `app/src/main/assets/plugins/fiezta-runtime.js` (não é um plugin) |
| ordem de primeiro contato dos padrão | `app/src/main/assets/plugins/defaults.json` |
| plugins instalados | `filesDir/plugins/<id>/` |
| índice dos instalados | `filesDir/plugins/index.json` |
| pilha de ativação | preferências `pref_plugin_activation_order`, `pref_plugin_seen_defaults`,
  `pref_removed_default_plugins` (ver `PreferencesPluginStateStore`/`PluginActivationStack`) |

## Código

- `support/plugins/` — lib autocontida, em subpacotes (`contract`, `model`, `manifest`,
  `archive`, `activation`, `injection`, `voice`; ver o README dela): o modelo puro
  (`PluginManifest`, `UrlMatch`, `PluginResolver`, `PluginActivationStack`,
  `PluginManifestValidator`, ...), com testes JUnit em `app/src/test/.../support/plugins/`, o
  `PluginManifestJson` (parse + validação), `PluginSource` (resolve a pilha, instala/remove, por
  portas), `PluginInjector` (decide o que injetar a cada evento do `WebView`) e
  `ZipPluginArchive` (extrai o pacote `.zip`).
- `shared/plugins/` — a cola com o app: `AppPlugins` (a instância única de `PluginSource`),
  `AndroidPluginFiles` (lê assets e `filesDir/plugins`, verifica SHA-256),
  `PreferencesPluginStateStore`, `AndroidPluginLog`, `PluginHandlersBridge` (ponte
  `fieztaplugins`, só no carro), `PluginZipInstaller` (abre o `.zip` escolhido pelo usuário e
  delega a extração ao `ZipPluginArchive`) e `PluginLifecycleAlignment`.
- `features/domain/phone/plugins/PluginsPhoneActivity` — tela de gerenciamento no
  celular (ativar/desativar, ligar/desligar cada tweak, apagar, instalar de `.zip`,
  restaurar padrões, escolher o ouvinte de voz).

## Testes

Os plugins e o runtime rodam em JUnit nenhum — eles sao JavaScript, e o que os testa e o
Node, contra um DOM de verdade (`jsdom`) e um fixture capturado da pagina real:

```bash
npm install
npm test
```

`tests/plugins/harness.mjs` sobe a pagina, injeta `fiezta-runtime.js` e o plugin pedido, e
captura o que iria para as pontes nativas (`mediacontrol`, `fieztaplugins`). Os fixtures em
`tests/plugins/fixtures/` sao HTML copiado do YouTube movel, com as classes e os `href`
originais — inclusive a miniatura sem `src`, que e o que obriga a arte a sair do id do video.

Os fixtures de `watch` e `shorts` guardam o que foi medido na pagina real: na watch page
desktop o `.ytp-next-button` vem `aria-disabled="false"` e o `.ytp-prev-button` vem `"true"`,
e a pagina de Shorts nao renderiza vizinho nenhum — nenhum link `/shorts/`, nenhum titulo, so
os dois botoes do carrossel. E por isso que a fila dessas duas paginas e a faixa atual
sozinha.

`tests/plugins/fiezta-runtime.test.mjs` cobre o contrato: a precedencia plugin sobre site
chave a chave, a metadata campo a campo, o `seekTo` chegando ao handler do site em segundos e
a leitura publicada so quando muda. `tests/plugins/youtube-playlist.test.mjs` cobre a fila da
playlist contra o DOM real.
