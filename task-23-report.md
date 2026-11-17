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
