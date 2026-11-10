# Yoru (夜)

Organizador pessoal que roda no seu computador: trabalho, estudos, leituras,
faculdade e metas em um só lugar, com um app no celular que sincroniza quando
o servidor está ligado.

## Jeito fácil

- **Servidor:** dois cliques em `Iniciar Yoru.bat`. Na primeira vez ele cria o ambiente
  Python, instala as dependências e as áreas padrão; depois só liga o servidor (aberto
  para a rede da casa, para o celular sincronizar) e abre o Yoru no navegador.
  Se já estiver ligado, só abre o navegador. Para desligar, feche a janela.
- **Celular:** gere o APK com `gradle :app:assembleDebug` em `android/` e instale o arquivo gerado.
  O Android pede para permitir "instalar apps desconhecidos".

## Rodar pela primeira vez (Windows, PowerShell)

```powershell
cd $HOME\Documents\yoru
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python seed.py                      # cria as áreas padrão
uvicorn app.main:app --reload       # servidor em http://127.0.0.1:8000
```

Abra http://127.0.0.1:8000 para usar o Yoru, ou http://127.0.0.1:8000/docs para a API.

## Telas

- **Hoje** (`/`): saudação, onde você parou e o próximo passo, ensō do dia,
  prazos, tarefas de hoje e captura rápida.
- **Caixa de entrada** (`/caixa`): anote sem pensar; depois mova cada item para uma área.
- **Faculdade**: disciplinas com horário e média, provas e entregas com a
  fileira de luas até a próxima prova, tarefas da faculdade.
- **Leituras**: livro atual com o ensō das páginas, fila "quero ler",
  livros do ano contra a meta e constância (dias seguidos, páginas por dia).
- **Estudos**: assuntos com progresso, registro de sessões (minutos e avanço),
  meta semanal em horas e materiais.
- **Trabalho, Pessoal** e outras áreas: em andamento, a seguir, objetivo da
  área e cursos ou materiais em andamento.
- **Metas** (`/metas`): objetivos por área, cada um com metas medíveis.
  Metas em "livros" e em "horas por semana" se medem sozinhas.
- **Encerrar uma área**: no fim da página de cada área (e na tela da área no app),
  "Encerrar <área> por hoje" grava o que fez, onde parou e o próximo passo só
  daquela área, sem fechar o dia. Encerrar de novo no mesmo dia atualiza o resumo.
- **Começar o dia** (tela Hoje): o último resumo pendente de cada área; os
  próximos passos marcados viram tarefas de hoje, os outros são dispensados.
- **Encerrar o dia** (`/encerrar`): o mesmo resumo para uma área, mais a opção
  de levar as tarefas abertas para amanhã.
- **Diário** (`/diario`): todos os dias encerrados.
- **Foco** (`/foco`): Pomodoro com tempos ajustáveis (foco, pausa, pausa longa,
  a cada quantos focos), área e tarefa da sessão; as sessões entram no Progresso.
- **Hábitos** (`/habitos`): uma lua por dia, sequência, vezes por semana; os de
  hoje aparecem também na tela Hoje.
- **Progresso** (`/progresso`): Hoje, Esta semana, Este mês. Tarefas feitas,
  minutos de foco, minutos de estudo, páginas lidas e % dos hábitos, em barras.
- **IAs** (`/ias`): quanto já foi usado da sessão de 5 horas e da semana no Claude Code
  e no ChatGPT Plus (Codex), com o horário de cada reset, e o custo e os tokens da API da
  Anthropic. Linke cada conta pelo login do CLI neste computador (`claude`, `codex login`)
  ou colando um token; a API pede uma Admin API key (`sk-ant-admin…`). Os vínculos ficam em
  `data/ia_contas.json`, fora do git. Os limites das assinaturas vêm de endpoints internos
  dos próprios CLIs, que podem mudar sem aviso.
- **Sincronia** (`/sincronia`): endereço e token para ligar o celular, e o
  histórico das sincronias.

As interações sem recarregar a página vêm de `app/static/yoru.js`, um script
próprio e pequeno (sem dependências, funciona offline).

Nas próximas vezes, só `.\.venv\Scripts\Activate.ps1` e `uvicorn app.main:app --reload`.

## Sincronia com o celular

O servidor é passivo: quem inicia a sincronia é o app. Para o celular alcançar
este computador, ligue o servidor aberto para a rede da casa:

```powershell
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Abra `/sincronia` neste computador e use **Cadastrar aparelho**: dê um nome e uma
senha e clique em "Enviar solicitação". O servidor anuncia o convite na rede
local por broadcast UDP (porta 47890) durante 3 minutos; o app aberto no mesmo
Wi‑Fi mostra "Aprovar sincronização" e pede essa senha. Aprovado, o aparelho
recebe um token só dele e já sincroniza. A página lista os aparelhos e permite
revogar cada um. Se o anúncio não chegar (roteador que bloqueia broadcast), use
"Conexão manual": endereço e token geral digitados no app.

Com o servidor aberto para a rede, só `/api/sync` (com token ou pareamento) e
`/api/health` respondem a outros aparelhos; as páginas e o resto da API continuam
valendo apenas neste computador. Use só em rede de confiança.

Firewall: se o celular recebe o convite mas "não consegue falar com o servidor",
o Windows está barrando a porta. No PowerShell como administrador, uma vez:

```powershell
New-NetFirewallRule -DisplayName "Yoru 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private,Public
```

Teste no navegador do celular: `http://<ip-do-pc>:8000/api/health` deve responder `{"status":"ok"}`.

Pareamento: `POST /api/sync/pair` com `{"convite", "senha", "aparelho"}` devolve
`{"token", "aparelho"}`; senha errada 5 vezes ou convite vencido encerram o convite.

Protocolo (usado pelo app), sempre com o cabeçalho `X-Yoru-Token`:

```
GET  /api/sync?desde=<cursor>   baixa o que mudou (sem "desde": tudo)
POST /api/sync                  {"dispositivo": "celular", "desde": "<cursor>",
                                 "mudancas": {"task": [ {...}, ... ], "event": [ ... ]}}
```

A resposta traz `cursor` (guarde e envie como `desde` na vez seguinte),
`mudancas` (o que falta no aparelho), `aplicados`, `ignorados` e `rejeitados`
(com o motivo de cada um). Regras:

- Conflito: vence o `updated_at` mais recente; empate fica com o servidor.
- Exclusão é um registro com `deleted_at`; o app também deve enviar e guardar essas lápides.
- Reenviar o mesmo lote é seguro. Um registro com defeito é rejeitado sem derrubar o lote.
- Carimbos em UTC com 6 casas (`2026-10-31T18:45:12.123456Z`); horários marcados
  pelo usuário (prova às 19h) são sem fuso. Carimbo mais de 5 minutos no futuro
  vira "agora" do servidor.
- Áreas e tags têm nome único: o app deve baixar antes de criar. O `seed.py`
  usa ids fixos, mas um banco criado antes da Fase 3 mantém os ids que já tinha.

## App do celular (Android, Kotlin)

Fica em `android/`. Abra essa pasta no Android Studio (File → Open), espere o Gradle
sincronizar (na primeira vez ele baixa as dependências) e rode no celular ou no
emulador com o botão Run. O app guarda uma cópia local dos dados (Room/SQLite) e
sincroniza com este servidor pelo `/api/sync`: ao abrir e no botão "Atualizar agora"
da aba Sincronia, onde você informa o endereço e o token da página `/sincronia`.

Guia da linguagem para quem vem do Java: `android/GUIA-KOTLIN.md`.

## Estrutura

```
yoru/
├── app/
│   ├── main.py        # ponto de entrada FastAPI
│   ├── database.py    # conexão SQLite (data/yoru.db)
│   ├── models.py      # todas as tabelas (SQLModel)
│   ├── crud.py        # criar / listar / atualizar / excluir (suave)
│   ├── sync.py        # motor de sincronia (conflitos, lápides, token)
│   ├── pairing.py     # pareamento: convite por UDP, senha, token por aparelho
│   ├── services_track.py  # hábitos, sessões de foco, progresso por período
│   ├── routers/       # rotas da API por entidade
│   ├── templates/     # páginas HTML (fase 1)
│   └── static/        # CSS e JS (fase 1)
├── data/yoru.db       # seu banco; backup = copiar este arquivo
├── android/           # app do celular (Kotlin, Jetpack Compose, Room)
├── seed.py            # áreas padrão
└── requirements.txt
```

## Modelo de dados

```
Área → Objetivo → Meta → Tarefa
Recurso   livro, curso, disciplina (com progresso)
Evento    prova, entrega, reunião (data e hora)
Registro  diário de bordo: onde parei, próximo passo
Nota      texto livre ligado a qualquer item
```

Toda tabela tem `id` (UUID), `created_at`, `updated_at`, `deleted_at` e `synced_at`.
Esses campos sustentam a sincronização celular ↔ PC.

Quando um modelo ganha um campo opcional, a coluna é criada sozinha no banco
ao iniciar o servidor (`add_missing_columns` em `database.py`).

## Roadmap

0. Fundação: projeto, banco, modelos, API base — feito
1. Núcleo web: Hoje, Caixa de entrada, Tarefas, Diário de bordo — feito
2. Áreas: Faculdade, Leituras, Estudos, Trabalho, Metas — feito
3. Sincronização offline-first — servidor feito (`/api/sync`); o lado do aparelho vem na fase 4
4. App no celular (Android nativo, Kotlin) — Hoje, Caixa, Áreas, Foco, Hábitos, Progresso e Sincronia; faltam Leituras, Faculdade e Estudos
5. Extras: Pomodoro, hábitos e Progresso — feitos (site e app); revisão semanal e Contas e IAs — a seguir

## Estado do projeto

Projeto pessoal, em uso no dia a dia do autor. Não há versão publicada nem testes automatizados ainda. O banco de dados, os tokens e os registros ficam em `data/`, fora do repositório.

## Licença

MIT. Veja [LICENSE](LICENSE).
