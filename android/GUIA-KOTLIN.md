# Kotlin para quem vem do Java, usando o código do Yoru

Cada seção aponta para um arquivo real deste projeto. Leia o trecho, depois abra o
arquivo: o código tem comentários nos mesmos pontos.

## 1. O básico que muda a cara do código

| Java | Kotlin | Onde ver |
|---|---|---|
| `final String x = "a";` | `val x = "a"` (tipo inferido; `val` = final, `var` = mutável) | em todo lugar |
| `String s = null;` compila | `val s: String? = null` — só tipos com `?` aceitam null | `data/Entities.kt` |
| `if (s != null) s.length()` | `s?.length` (null-safe) e `s ?: "padrão"` (Elvis) | `data/Time.kt`, `relative()` |
| getters/setters | propriedades: `task.title` lê, `task.title = x` grava | em todo lugar |
| `static` | `companion object` ou função no topo do arquivo | `data/YoruDatabase.kt`, `data/Settings.kt` |
| `switch` | `when`, que é expressão e devolve valor | `data/Time.kt`, `greeting()` |
| ponto e vírgula | opcional | — |
| `new Foo()` | `Foo()` | — |
| `foo instanceof Bar` + cast | `if (x is Bar) x.metodo()` (smart cast automático) | — |
| checked exceptions | não existem | `sync/SyncClient.kt` |

Strings: `"Olá, $nome"` e `"${tasks.size} coisas"` interpolam. Blocos `"""..."""`
são texto multilinha sem escape (os SQLs em `data/Daos.kt`).

## 2. `data class` — o Java record com `copy`

`data/Entities.kt`. Uma `data class` ganha `equals`, `hashCode`, `toString` e **`copy`**.
Todo campo é `val` (imutável): para "alterar" uma tarefa, criamos outra com `copy`:

```kotlin
task.copy(status = "done", completedAt = Time.now(), dirty = true)
```

Parâmetros com valor padrão (`val position: Int = 0`) e argumentos nomeados
(`Task(id = ..., title = ...)`) substituem builders e construtores sobrecarregados.

Anotações em parâmetros de construtor (`@ColumnInfo(name = "due_date") val dueDate`)
valem para o campo gerado: o Room lê a coluna `due_date`, o código usa `dueDate`.

## 3. Interfaces com propriedades e extensões

- `interface Synced { val id: String ... }` (`Entities.kt`): interface pode exigir
  propriedades; a data class as implementa com `override val`.
- **Função/propriedade de extensão**: `val Task.isDone get() = status == "done"`
  acrescenta `task.isDone` sem mexer na classe. Em Java seria `TaskUtils.isDone(task)`.
  Repare nos `import app.yoru.data.isDone` nos arquivos de UI: extensões se importam.
- `String.toMediaType()` e `String.toRequestBody()` do OkHttp (`SyncClient.kt`) são
  extensões também.

## 4. Lambdas e funções como valor

`sync/SyncEngine.kt`. `TableSpec` recebe `clean: (T) -> T`, uma função que vira
parâmetro. Na chamada, a lambda vai fora dos parênteses quando é o último argumento:

```kotlin
TableSpec("task", Task.serializer(), db.tasks()) { it.copy(dirty = false) }
```

`it` é o nome implícito do único parâmetro. Coleções têm `map`, `filter`,
`associate`, `groupBy`, `firstOrNull`, `joinToString`, todos com lambdas, sem
`stream()` nem `collect(Collectors...)`:

```kotlin
areas.associate { it.id to it.name }      // Map<String, String>; `to` cria um Pair
response.rejeitados.groupBy({ it.tabela }, { it.id })
```

Funções de escopo que aparecem no projeto:
- `x?.let { ... }`: executa só se `x` não for null, com `it` = x (`SincroniaScreen.kt`).
- `response.use { ... }`: fecha ao sair, como try-with-resources (`SyncClient.kt`).
- `runCatching { ... }.getOrNull()`: try/catch em uma linha (`Time.kt`).
- `buildJsonObject { put("desde", cursor) }`: lambda com receptor, o `this` dentro
  do bloco é o builder (`SyncEngine.kt`). É assim que DSLs como o Compose funcionam.

## 5. Genéricos e `*`

`SyncDao<T : Synced>` (`Daos.kt`) é um `interface SyncDao<T extends Synced>`.
`List<TableSpec<*>>` (`SyncEngine.kt`) é o `List<TableSpec<?>>`: o motor não sabe
qual `T` cada tabela tem, por isso tudo que precisa de `T` fica dentro de `TableSpec`.

## 6. Coroutines: o `async/await` do Kotlin

Em vez de threads e callbacks, funções `suspend`: podem pausar (esperando a rede ou o
banco) sem travar a thread, e o código continua parecendo sequencial.

- `suspend fun sync(): SyncReport` (`SyncEngine.kt`) é chamada como função comum,
  mas só de dentro de outra `suspend` ou de uma coroutine.
- `withContext(Dispatchers.IO) { ... }` executa o bloco numa thread de I/O e volta.
- `viewModelScope.launch { ... }` (`ui/YoruViewModel.kt`) inicia uma coroutine ligada
  ao ciclo de vida do ViewModel; se ele morrer, ela é cancelada.
- `Mutex().withLock { }` evita duas sincronias simultâneas sem `synchronized`.
- O Room gera DAOs `suspend` sozinho: `suspend fun upsert(row: T)`.

**Flow** (`Daos.kt`, `YoruViewModel.kt`) é um fluxo de valores ao longo do tempo,
parecido com um `Stream` que nunca termina ou um Observable do RxJava. Uma consulta
Room que devolve `Flow<List<Task>>` emite uma lista nova toda vez que a tabela muda.
A tela "coleta" o Flow com `collectAsStateWithLifecycle` e se redesenha sozinha.
`StateFlow` é um Flow que sempre tem um valor atual (`settings.address.value`).

## 7. Propriedades delegadas: `by`

- `val db by lazy { ... }` (`YoruApp.kt`): criado no primeiro acesso.
- `var texto by rememberSaveable { mutableStateOf("") }` (`ui/Components.kt`): `texto` lê
  e grava num `State` do Compose; toda escrita redesenha quem leu. Precisa dos imports
  `getValue`/`setValue`, por isso eles aparecem no topo dos arquivos de UI.
- `val tasks by vm.todayTasks.collectAsStateWithLifecycle(emptyList())`: idem, vindo de um Flow.

## 8. Jetpack Compose em cinco linhas

- UI é função: `@Composable fun LinhaTarefa(task: Task, onDone: () -> Unit)`. Nada de XML.
- Estado muda, função roda de novo ("recomposição") só onde precisa.
- `Modifier` é a corrente de configuração: `Modifier.fillMaxWidth().padding(20.dp).clickable { }`.
  A ordem importa (padding antes de background é diferente de depois).
- `Column`, `Row`, `Box` e `LazyColumn` (lista eficiente) são os layouts. Dentro de
  `LazyColumn`, `item { }` e `items(lista) { }`.
- `remember`/`rememberSaveable` guardam estado entre recomposições (saveable sobrevive à rotação).
  `LaunchedEffect(Unit) { }` roda uma coroutine quando o composable entra na tela
  (é como a sincronia automática dispara em `MainActivity.kt`).

## 9. Room em duas linhas

`@Entity` marca a tabela, `@Dao` a interface de consultas, `@Database` junta tudo.
O KSP gera as implementações na compilação (como o Lombok/MapStruct, mas oficial).
Erro de SQL aparece em tempo de compilação, não em runtime.

## 10. Vocabulário Gradle

`settings.gradle.kts` lista os módulos; `build.gradle.kts` da raiz declara plugins;
`app/build.gradle.kts` configura o app; `gradle/libs.versions.toml` centraliza as
versões (o "BOM" do Compose alinha as versões das bibliotecas de UI entre si).
O `.kts` é Kotlin: as mesmas regras acima valem nos scripts de build.

## Por onde ler o código, nesta ordem

1. `data/Entities.kt`, `data/Time.kt` — a linguagem, sem Android.
2. `sync/SyncClient.kt`, `sync/SyncEngine.kt` — coroutines, lambdas, genéricos.
3. `data/Daos.kt`, `data/YoruDatabase.kt` — Room e Flow.
4. `ui/YoruViewModel.kt` — a ponte entre dados e tela.
5. `ui/Components.kt`, `ui/screens/*.kt`, `MainActivity.kt` — Compose.
