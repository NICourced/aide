# Отчёт по задаче 23: T-1.17 «Чекпоинты полного автомата»

## Что сделано

Закрыт критерий задачи T-1.17: в режиме `FULL_AUTO_WITH_CHECKPOINTS` перед каждым изменяющим шагом ставится снапшот, обе ссылки связаны с прогоном, видны через существующий список отката и позволяют последовательно вернуть файл к промежуточному и исходному содержимому.

Основная доработка — устранение коллизии имён ссылок, которая раньше молча перезаписывала ранее поставленный чекпоинт, если два снапшота попадали на одну миллисекунду:

- `SnapshotPlacement.findFreeRef` теперь ищет первое свободное имя `refs/ai/snap/<millis>-<label>`, увеличивая метку времени, пока ссылка занята. Лимит поиска `SEARCH_LIMIT = 100` защищает от бесконечного цикла.
- `JGitRepository.writeRef` больше не считает `RefUpdate.Result.FORCED` успехом. Если ссылка уже существует на другой объект, запись возвращает `Refused` без перезаписи. Если ссылка указывает на тот же HEAD, возвращается `Created` (идемпотентность).

Добавлены тесты:

- `TaskSnapshotGuardTest`: два снапшота в одну миллисекунду получают разные имена и оба живы; перезапись существующей ссылки отклоняется.
- `SnapshotRollbackEndToEndTest`: два изменяющих шага в полном автомате дают два чекпоинта и последовательный откат к промежуточному и исходному состоянию.
- `AppNavigationTest`: UI-тест навигации к списку снапшотов, выбор второго чекпоинта открывает предпросмотр и показывает кнопку отката.

Поломочные проверки выполнены: убран инкремент в `findFreeRef` → unit-тест падает; временно возвращено старое поведение `isForceUpdate = true` → unit-тест падает из-за перезаписи.

## Изменённые файлы

- `host-core/src/main/kotlin/dev/aide/host/agent/SnapshotPlacement.kt`
- `host-core/src/main/kotlin/dev/aide/host/git/JGitRepository.kt`
- `host-core/src/test/kotlin/dev/aide/host/agent/TaskSnapshotGuardTest.kt`
- `host-core/src/test/kotlin/dev/aide/host/agent/SnapshotRollbackEndToEndTest.kt`
- `client-ui/src/jvmTest/kotlin/dev/aide/client/ui/AppNavigationTest.kt`
- `docs/tasks/02-mvp-review-cycle.md`

## Расхождения с планом

План в `docs/plans/2026-09-30-stage-1-mvp-review-cycle.md` (раздел «Задача 23») предлагал:

1. `SnapshotOperation.writeRef` должен был отказаться от `FORCED` и считать успехом только `NEW`/`NO_CHANGE`. В реализации используется `setExpectedOldObjectId(ObjectId.zeroId())` + `isForceUpdate = false`, что даёт `NEW` при создании, `NO_CHANGE` при идемпотентности и `REJECTED` при существующей ссылке на другой объект. Это эквивалентно требуемому поведению, но реализовано через expected-old-object-id, а не через фильтрацию результата `FORCED`.
2. План не упоминал UI-тест: он добавлен для подтверждения критерия «в инбоксе виден список созданных снапшотов и возможен откат к любому» через существующий экран отката T-1.6.
3. Функция `SnapshotPlacement.place` пришлось переписать в один `return` после детекта, чтобы уложиться в правило `ReturnCount ≤ 2`. Логика не изменилась.

## Результаты проверок

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :host-core:test :host-agent:test :host-tools:test :domain:jvmTest :protocol:jvmTest
./gradlew :client-ui:jvmTest :client-state:jvmTest :desktopApp:test
./gradlew -p build-logic test
./gradlew detekt
./gradlew verifyModuleBoundaries
./gradlew build
```

Все команды завершились успешно:

- Юнит- и UI-тесты зелёные.
- `detekt` без замечаний.
- `verifyModuleBoundaries` подтверждает соблюдение границ модулей.
- `./gradlew build` успешен.

## Что осталось непроверенным

- Живой Android/Desktop UI и реальный провайдер, как указано в плане (О-11).
- Вытеснение при нескольких незакрытых задачах одновременно: в этапе 1 хост ведёт один прогон за раз.
- Внешняя гонка с независимым git-процессом: защита реализована через отказ `writeRef`, но не нагружалась в интеграционных условиях.

## Исправления по контроллерному ревью T-1.17

Контроллерное ревью выявило два Important. Оба исправлены строго в объёме задачи, без изменения спецификации, плана и задачного чеклиста.

### Important 1: `JGitRepository.writeRef` поглощал ошибки чтения refs

**Root cause.** В `SnapshotOperation.writeRef` чтение существующей ссылки делалось через `runCatching { repository.exactRef(ref)?.objectId }.getOrNull()`. Это превращало `CancellationException` и любой сбой чтения refs в `null`, принимая их за «ссылки нет». В результате операция шла в `createNewRef`, что могло привести к перезаписи или к неправильному исходу прогона вместо безопасного отказа.

**Исправление.** `runCatching` заменён на явный `try/catch` вокруг `repository.exactRef(ref)?.objectId`:
- `CancellationException` пробрасывается дальше.
- Прочий `Exception` чтения превращается в безопасный `SnapshotOutcome.Refused` без попытки записи.
- Пути `existing == head` (идемпотентность), `existing != null` (защита от перезаписи) и `else -> createNewRef` сохранены.

**Файлы.**
- `host-core/src/main/kotlin/dev/aide/host/git/JGitRepository.kt` — переписан `writeRef`.
- `host-core/src/test/kotlin/dev/aide/host/git/JGitRepositoryTest.kt` — добавлены focused-тесты и фикстура `ThrowingRefRepository`/`ThrowingRefDatabase`.

**RED/GREEN.**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
# Старый код временно возвращён: runCatching поглощает IOException и CancellationException.
./gradlew :host-core:test \
  --tests "dev.aide.host.git.JGitRepositoryTest.сбой чтения ссылки даёт отказ без попытки записи" \
  --tests "dev.aide.host.git.JGitRepositoryTest.отмена при чтении ссылки пробрасывается"
```

RED: оба теста падают с `AssertionFailedError` — старый код поглощает исключение, идёт в `createNewRef`, успешно создаёт ссылку (`SnapshotOutcome.Created`) вместо ожидаемого `Refused`/`CancellationException`.

```bash
# Исправленный writeRef.
./gradlew :host-core:test \
  --tests "dev.aide.host.git.JGitRepositoryTest.сбой чтения ссылки даёт отказ без попытки записи" \
  --tests "dev.aide.host.git.JGitRepositoryTest.отмена при чтении ссылки пробрасывается"
```

GREEN: оба теста проходят.

### Important 2: `SnapshotRollbackEndToEndTest` использовал `object` для скриптованных моделей

**Root cause.** `WriteThenFinishModel` и `TwoWritesThenFinishModel` были объявлены как `object` с `@Volatile var calls`. Одиночки живут внутри JVM до перезапуска Gradle-демона, поэтому состояние `calls` переживало повторный запуск теста, retries Gradle и параллельные прогоны, что делало end-to-end тест недетерминированным.

**Исправление.** Оба `object` превращены в `class`, `@Volatile` убрано (экземпляр не разделяется между корутинами), и каждый тест теперь создаёт свой экземпляр (`WriteThenFinishModel()`, `TwoWritesThenFinishModel()`). Добавлен focused-тест `скриптованные модели не делят состояние между экземплярами`, который доказывает, что два экземпляра одной модели независимо начинают счётчик с нуля.

**Файлы.**
- `host-core/src/test/kotlin/dev/aide/host/agent/SnapshotRollbackEndToEndTest.kt` — `object -> class`, явное создание экземпляров, новый тест.

**RED/GREEN.**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :host-core:test --tests "dev.aide.host.agent.SnapshotRollbackEndToEndTest"
```

GREEN: все три теста (`клиент получает список снапшотов...`, `два изменяющих шага...`, `скриптованные модели не делят состояние между экземплярами`) проходят.

Дополнительная проверка независимости: тест `скриптованные модели не делят состояние между экземплярами` падает, если вернуть `object`, потому что второй экземпляр в действительности не создаётся и счётчик продолжает расти.

### Повторная сводная проверка

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :host-core:test \
  --tests "dev.aide.host.git.JGitRepositoryTest" \
  --tests "dev.aide.host.agent.TaskSnapshotGuardTest" \
  --tests "dev.aide.host.agent.SnapshotRollbackEndToEndTest"
./gradlew :host-core:test --rerun-tasks --max-workers=1
./gradlew :host-core:detekt
```

Все три команды завершились успешно.

### Что осталось непроверенным после правок

- Реальная отмена корутины внутри `writeRef` в полноценном прогоне: тест имитирует `CancellationException` синхронно, в продакшене отмена придёт извне suspend-границы.
- Внешняя гонка с независимым git-процессом при сбое чтения refs: защита реализована, но не нагружалась в интеграционных условиях.
