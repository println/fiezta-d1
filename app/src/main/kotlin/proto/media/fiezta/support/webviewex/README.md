# support/webviewex

Wrapper de `android.webkit.WebView` com o contrato de navegador estendido. Quem usa a lib chama
nela o que chamaria numa `WebView` (`loadUrl`, `reload`, `goBack`, `evaluateJavascript`, `url`,
`title`, histórico...) mais os esteroides (media session, zoom, teclado do carro, captura de
página) — e **nunca recebe a `WebView` crua**. Zero `androidx`, zero `R`, zero import do app.

## `WebViewEx` é o único ponto de entrada

```kotlin
class WebViewEx<W : VideoWebView>(
    context: Context,
    config: WebViewExConfig,
    host: WebViewExHost,
    texts: WebViewExTexts,
    delegate: WebViewExDelegate,
    webViewFactory: (Context) -> W,
    chromeClientFactory: ...,
    schemeMemory: SchemeMemory,
    recoveryPolicy: RecoveryPolicy,
    gearhead: WebViewExGearhead? = null,
)
```

`create()`/`attach(container, windowContext)`/`detach()`/`destroy()` põem e tiram o wrapper da
tela; a única concessão de tipo é `screenView: View?` (nunca `WebView`) — o que só a tela real
resolve (cadeia de foco por id, listeners de toque/tecla, `layoutParams`). `navigate(input)`
passa pela navegação segura (https antes, queda única para http lembrada por host, ciente de
motor de busca); `open(url)`/`loadUrl(url)` não. `observe(listener)` assina eventos de navegação,
loading, estágio de documento, eventos do elemento de mídia, veredito de reprodução e render mode.
`playback` e `observeCommand(kind, origin)` repassam ao gearhead; sem gearhead são `null` e `true`.

## O ponto de extensão: `WebViewExGearhead`

Nenhum código deste pacote sabe que "carro" existe. Todo comportamento que muda por ambiente
passa por uma interface com corpo padrão vazio/neutro, injetada opcionalmente no construtor:

| Gancho | Para quê |
|---|---|
| `creationContext()` | em que `Context` a `WebView` nasce — o Chromium lê o display do contexto de criação |
| `configure(settings)` | ajustes extras de `WebSettings` |
| `hostWithoutScreen(view)` / `onAttachedToScreen(view, container)` / `onViewDestroyed(view)` | mover a `WebView` entre tela real e uma janela própria quando não há tela |
| `onRenderModeChanged(view, mode)` | reagir a `FOREGROUND`/`BACKGROUND` |
| `playback` / `observePlayback(listener)` / `admits(kind, origin)` | veredito de reprodução e filtro de comandos, só onde o ambiente precisa deles |
| `onMediaElementEvent(event, position, documentId)` | devolve uma `GuardAction`; a `WebViewEx` executa a retomada pelo `ScriptHost` do documento |
| `onDpadCenterOrTap`, `enterKeyboardText`, `sendKeyboardEnter`, `scrollActiveElementIntoView`, `setAspectRatio`, `requestFullScreen` | entrada que só existe fora de toque de tela cheia |
| `allowsExternalApps`, `errorPageStyle` | propriedades que mudam a UI sem a lib decidir por que |

Uma implementação concreta é injetada de fora (o app tem a que dá conta do carro); sem `gearhead`
o comportamento é o de um navegador comum — o celular não roda veredito nem guard. Sinais do
sistema entram por porta (`AudioOutputSignal`, `CarConnectionSignal`, `CarModeExitSignal`),
implementadas em `shared/webviewex`. A composição é por delegação, não por herança — não
existe uma segunda classe `WebViewEx` para o carro.

## Regras que não dá para descobrir lendo por cima

- **Sem tela, a `WebView` não pode ficar sem janela.** O Chromium marca uma `WebView` já anexada
  como `hidden` ao desanexar (`IsClientVisible`), e a página pausa. Por isso `detach()` sozinho
  não é suficiente para manter uma página tocando em segundo plano — é preciso um
  `hostWithoutScreen` que dê outra janela real ao view.
- **Erro é navegação virtual.** A tela de erro é tratada como uma transação pendente
  (`errorDocumentPending`); o próximo `onPageStarted` é marcado como documento de erro em vez de
  começar uma transação nova. Entradas de erro no histórico levam um marcador de título e são
  puladas por `goBack()`/`goForward()` — nunca dá para "voltar" para uma tela de erro.
  Popup (`onCreateWindow`) cria uma `WebView` descartável só para farejar a primeira URL que ela
  tenta carregar, destrói o popup e reabre essa URL pelo caminho normal — não existe uma segunda
  `WebView` de verdade por trás de "abrir em nova aba".
- **User agent é por destino, não por sessão.** `host.prefersDesktop(pageHost)` é reavaliado a
  cada navegação, inclusive indo para trás/frente, porque hosts diferentes podem pedir UA
  diferente.
- **`RenderProcessGone` não resume sozinho.** A `WebView` é destruída e, se a política de
  recuperação permitir (com limite de taxa), recriada do zero e reabre a URL que perdeu (ou a
  `initialUrl()` do delegate, se perdeu uma tela de erro) — não há resumo transparente de sessão.
- **O bootstrap é por documento, não por página.** `assets/webviewex/bootstrap.js` é injetado a
  cada `onPageStarted` com um id de documento embutido; estágios reportados por um documento que
  já morreu (SPA que trocou de rota no meio) são descartados, não aplicados por engano ao
  documento novo.

## Testes

`app/src/test/kotlin/proto/media/fiezta/support/webviewex/` — políticas e máquinas de estado
puras (`SchemePolicy`, `RecoveryPolicy`, `NavigationTracker`, `DocumentLifecycle`,
`AutoHostMachine`, `PlaybackVerdictRules`, `PlaybackVerdictObserver`, `ScreenTransitionGuard`,
`MediaElementTracker`, `MediaSessionCommands`, `AddressResolver`, `ExternalSchemePolicy`). Não há
teste de `WebViewEx` em si — depende de `android.webkit.WebView` de verdade, fora do alcance do
JUnit puro.
