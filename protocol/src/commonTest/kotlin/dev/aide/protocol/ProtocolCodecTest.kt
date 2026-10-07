package dev.aide.protocol

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolCodecTest {

    private val workspaceId = WorkspaceId("ws-1")
    private val requestId = RequestId("req-1")
    private val sessionId = SessionId("s-1")

    /** Момент вызова журнала: общий для курсора, записи и сводки. */
    private val toolCallAt: Instant = Instant.fromEpochMilliseconds(1_758_535_200_000)

    private fun hostMessage(bytes: ByteArray): HostMessage =
        assertIs<DecodeResult.Message<HostMessage>>(ProtocolCodec.decodeHostMessage(bytes)).message

    private fun clientMessage(bytes: ByteArray): ClientMessage =
        assertIs<DecodeResult.Message<ClientMessage>>(ProtocolCodec.decodeClientMessage(bytes)).message

    @Test
    fun `сообщение клиента переживает round-trip`() {
        val message = ClientMessage.FileContent(
            requestId = requestId,
            workspaceId = workspaceId,
            path = "src/auth/Login.kt",
        )
        val decoded = ProtocolCodec.decodeClientMessage(ProtocolCodec.encode(message))
        assertEquals(message, assertIs<DecodeResult.Message<ClientMessage>>(decoded).message)
    }

    @Test
    fun `дерево файлов переживает round-trip целиком`() {
        val message = HostMessage.Tree(
            requestId = requestId,
            tree = FileTreePayload(
                workspaceId = workspaceId,
                rootPath = "/projects/aide",
                entries = listOf(
                    FileTreeEntry(path = "src", isDirectory = true, sizeBytes = null),
                    FileTreeEntry(path = "src/auth", isDirectory = true, sizeBytes = null),
                    FileTreeEntry(path = "src/auth/Login.kt", isDirectory = false, sizeBytes = 2048),
                ),
                truncated = true,
                skippedEntries = 17,
            ),
        )
        val decoded = hostMessage(ProtocolCodec.encode(message))
        assertEquals(message, decoded)

        // Ни одно поле нагрузки не потерялось при кодировании: обрезка, счётчик и null у директории.
        val tree = assertIs<HostMessage.Tree>(decoded).tree
        assertEquals(3, tree.entries.size)
        assertEquals(17, tree.skippedEntries)
        assertTrue(tree.truncated)
        assertNull(tree.entries[0].sizeBytes)
        assertEquals(2048L, tree.entries[2].sizeBytes)
        assertTrue(tree.entries[2].isDirectory.not())
    }

    @Test
    fun `типизированная ошибка доступа переживает round-trip`() {
        val message = HostMessage.Failure(
            requestId = requestId,
            error = ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"),
        )
        assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
    }

    @Test
    fun `событие хоста переживает round-trip`() {
        val message = HostMessage.Event(HostEvent.WorkspaceChanged(workspaceId))
        assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
    }

    @Test
    fun `неизвестный тип сообщения не роняет разбор, а возвращается как Ignored`() {
        val unknown = envelope(
            type = "quantumTeleport",
            serializer = ClientMessage.Hello.serializer(),
            value = ClientMessage.Hello(ProtocolVersion.CURRENT),
        )
        val result = ProtocolCodec.decodeClientMessage(unknown)
        val ignored = assertIs<DecodeResult.Ignored>(result)
        assertEquals("quantumTeleport", ignored.rawType)
        assertTrue(ignored.reason.contains("неизвестный тип"))
    }

    @Test
    fun `три неизвестных типа подряд разбираются как три Ignored и работа продолжается`() {
        val unknowns = listOf("a", "b", "c").map { name ->
            envelope(
                type = name,
                serializer = HostMessage.Hello.serializer(),
                value = HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s")),
            )
        }
        val results = unknowns.map { ProtocolCodec.decodeHostMessage(it) }
        assertEquals(listOf("a", "b", "c"), results.map { assertIs<DecodeResult.Ignored>(it).rawType })
        // Ни одно из неизвестных сообщений не превратилось в разобранное.
        results.forEach { assertIs<DecodeResult.Ignored>(it) }

        // …и следующее известное сообщение после них по-прежнему разбирается.
        val known = ProtocolCodec.encode(HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s-2")))
        assertEquals(
            HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s-2")),
            hostMessage(known),
        )
    }

    @Test
    fun `битые байты дают Ignored, а не исключение`() {
        val result = ProtocolCodec.decodeClientMessage(byteArrayOf(0x00, 0x01, 0x02))
        assertIs<DecodeResult.Ignored>(result)
    }

    @Test
    fun `нагрузка чужого типа пропускается, а не падает`() {
        // Имя типа совпадает («hello»), но нагрузка — приветствие хоста: обязательного
        // clientVersion в ней нет, поэтому сообщение пропускается с указанием типа.
        val hostHello = ProtocolCodec.encode(HostMessage.Hello(ProtocolVersion.CURRENT, sessionId))
        val result = ProtocolCodec.decodeClientMessage(hostHello)
        val ignored = assertIs<DecodeResult.Ignored>(result)
        assertEquals(ClientMessageType.HELLO, ignored.rawType)
        assertTrue(ignored.reason.contains("полезная нагрузка не разобрана"))
    }

    @Test
    fun `имя типа можно прочитать без разбора нагрузки`() {
        val bytes = ProtocolCodec.encode(ClientMessage.HostState(requestId, workspaceId))
        assertEquals(ClientMessageType.HOST_STATE, ProtocolCodec.peekType(bytes))
        assertNull(ProtocolCodec.peekType(byteArrayOf(0x7f)))
    }

    @Test
    fun `имя типа читается и у неизвестного сообщения`() {
        val unknown = envelope(
            type = "quantumTeleport",
            serializer = ClientMessage.Hello.serializer(),
            value = ClientMessage.Hello(ProtocolVersion.CURRENT),
        )
        assertEquals("quantumTeleport", ProtocolCodec.peekType(unknown))
    }

    @Test
    fun `кодирование одного сообщения дважды даёт одинаковые байты`() {
        val message = ClientMessage.OpenWorkspace(requestId, "/projects/aide")
        assertContentEquals(ProtocolCodec.encode(message), ProtocolCodec.encode(message))
    }

    @Test
    fun `кодек пишет CBOR, а не текст`() {
        val bytes = ProtocolCodec.encode(ClientMessage.HostState(requestId, workspaceId))
        // Первый байт — заголовок карты CBOR (major type 5), а не '{' текстового формата.
        assertEquals(5, (bytes[0].toInt() shr 5) and 0x07)
        // Внутри есть байты вне печатаемого ASCII: строковая сериализация их не порождает.
        assertTrue(bytes.any { it < 0x20 || it > 0x7E }, "конверт должен быть бинарным: ${bytes.toList()}")
    }

    @Test
    fun `конверт сравнивается по содержимому, а не по ссылке`() {
        val first = WireEnvelope(type = "hello", payload = byteArrayOf(1, 2, 3))
        val same = WireEnvelope(type = "hello", payload = byteArrayOf(1, 2, 3))
        val otherPayload = WireEnvelope(type = "hello", payload = byteArrayOf(1, 2, 4))
        val otherType = WireEnvelope(type = "tree", payload = byteArrayOf(1, 2, 3))

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, otherPayload)
        assertNotEquals(first, otherType)
    }

    @Test
    fun `версия протокола участвует в приветствии`() {
        val hello = ClientMessage.Hello(clientVersion = ProtocolVersion.CURRENT, lastEventSeq = 7)
        val decoded = clientMessage(ProtocolCodec.encode(hello))
        assertEquals(ProtocolVersion.CURRENT, assertIs<ClientMessage.Hello>(decoded).clientVersion)
        assertEquals(7, assertIs<ClientMessage.Hello>(decoded).lastEventSeq)
    }

    @Test
    fun `версия печатается как major,minor`() {
        assertEquals("1.1", ProtocolVersion.CURRENT.toString())
        assertEquals("2.5", ProtocolVersion(2, 5).toString())
    }

    @Test
    fun `все варианты ClientMessage переживают round-trip`() {
        val messages = listOf(
            ClientMessage.Hello(clientVersion = ProtocolVersion.CURRENT),
            ClientMessage.Hello(clientVersion = ProtocolVersion.CURRENT, lastEventSeq = 42),
            ClientMessage.OpenWorkspace(requestId, "/projects/aide"),
            ClientMessage.FileTree(requestId, workspaceId),
            ClientMessage.FileContent(requestId, workspaceId, "src/auth/Login.kt"),
            ClientMessage.HostState(requestId, workspaceId),
            ClientMessage.PostTask(requestId, "Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES),
            ClientMessage.PostTask(requestId, "Только предложи", AutonomyMode.SUGGEST_ONLY),
            ClientMessage.AgentStatus(requestId),
            ClientMessage.RunControl(requestId, RunId("r-1"), RunCommand.PAUSE),
            ClientMessage.RunControl(requestId, RunId("r-1"), RunCommand.RESUME),
            ClientMessage.RunControl(requestId, RunId("r-1"), RunCommand.STOP),
            ClientMessage.AgentConfigRequest(requestId),
            ClientMessage.SaveAgentConfig(requestId, agentConfig()),
            ClientMessage.SaveAgentConfig(requestId, AgentConfig()),
            ClientMessage.CheckModel(requestId, "deepseek/deepseek-chat"),
            ClientMessage.SetModelSecret(requestId, "deepseek", "ключ"),
            ClientMessage.DeleteModelSecret(requestId, "deepseek"),
            ClientMessage.ModelSecrets(requestId),
            ClientMessage.ToolCalls(requestId, RunId("r-1")),
            ClientMessage.ToolCalls(requestId, RunId("r-1"), ToolCallCursor(toolCallAt, ToolCallId("tc-1")), 50),
            ClientMessage.ToolCallDetail(requestId, ToolCallId("tc-1")),
        )
        messages.forEach { message ->
            assertEquals(message, clientMessage(ProtocolCodec.encode(message)))
        }
    }

    @Test
    fun `все варианты HostMessage переживают round-trip`() {
        val tree = FileTreePayload(
            workspaceId = workspaceId,
            rootPath = "/projects/aide",
            entries = listOf(FileTreeEntry("src", isDirectory = true)),
            truncated = false,
        )
        val content = FileContentPayload(
            workspaceId = workspaceId,
            path = "src/Main.kt",
            text = "fun main() {}",
            sizeBytes = 13,
            truncated = false,
            language = "kotlin",
        )
        val state = HostStatePayload(
            workspaceId = workspaceId,
            rootPath = "/projects/aide",
            branch = "main",
            headCommit = "abc1234",
            uptimeMillis = 1000,
            mode = HostMode.LOCAL,
        )
        val messages: List<HostMessage> = listOf(
            HostMessage.Hello(ProtocolVersion.CURRENT, sessionId),
            HostMessage.WorkspaceOpened(requestId, workspaceId),
            HostMessage.Tree(requestId, tree),
            HostMessage.Content(requestId, content),
            HostMessage.State(requestId, state),
            HostMessage.State(requestId, state.copy(mode = HostMode.REMOTE)),
            HostMessage.Failure(requestId, ProtocolError.NotFound("src/Main.kt")),
            HostMessage.Incompatible(IncompatibilityReason.HOST_OUTDATED, ProtocolVersion(2, 0)),
            HostMessage.TaskPosted(requestId, TaskId("t-1")),
            HostMessage.AgentSnapshot(requestId, listOf(agentRun()), listOf(task())),
            HostMessage.RunControlled(requestId, RunId("r-1")),
            HostMessage.AgentConfigSnapshot(requestId, agentConfig(), listOf(catalogEntry())),
            HostMessage.AgentConfigSnapshot(requestId, AgentConfig(), emptyList()),
            HostMessage.AgentConfigSaved(requestId, agentConfig()),
            HostMessage.ModelCheckResult(requestId, ok = true, failure = null),
            HostMessage.ModelCheckResult(requestId, ok = false, failure = ModelCheckFailure.MissingKey("K")),
            HostMessage.ModelCheckResult(requestId, ok = false, failure = ModelCheckFailure.Unsupported),
            HostMessage.ModelCheckResult(
                requestId,
                ok = false,
                failure = ModelCheckFailure.RequestFailed("таймаут"),
            ),
            HostMessage.Event(HostEvent.WorkspaceChanged(workspaceId)),
            HostMessage.Event(HostEvent.RunStateChanged(agentRun())),
            HostMessage.Event(HostEvent.TaskStateChanged(task())),
            HostMessage.Event(HostEvent.ToolCallRecorded(toolCall())),
            HostMessage.Event(HostEvent.HostShuttingDown),
            HostMessage.ToolCallPage(
                requestId = requestId,
                runId = RunId("r-1"),
                calls = listOf(ToolCallSummary.of(toolCall())),
                nextCursor = ToolCallCursor(toolCallAt, ToolCallId("tc-1")),
                hasMore = true,
            ),
            HostMessage.ToolCallPage(requestId, RunId("r-1"), emptyList(), null, false),
            HostMessage.ToolCallContent(requestId, toolCall()),
        )
        messages.forEach { message ->
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
    }

    /** Задача для проверки round-trip: статус, причина отказа и ветка. */
    private fun task(): Task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Сделай выдачу токена",
        branch = "ai/t-1",
        status = TaskStatus.FAILED,
        failureReason = "NOT_CONFIGURED",
        createdAt = Instant.fromEpochMilliseconds(1_758_535_200_000),
    )

    /** Прогон для проверки round-trip: состояние, план, стоимость и момент старта. */
    private fun agentRun(): AgentRun = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.RUNNING,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        startedAt = Instant.fromEpochMilliseconds(1_758_535_200_000),
        elapsedMillis = 250,
        cost = Cost(amountMicros = 120, known = true),
        modelAlias = "deepseek/deepseek-chat",
        interruptReason = "host_restart",
    )

    /**
     * Конфигурация моделей для round-trip: две таблицы, имя переменной окружения
     * и ставки. Ключа здесь нет — его и не может быть в этом типе (NFR-8).
     */
    private fun agentConfig(): AgentConfig = AgentConfig(
        defaultModel = "deepseek/deepseek-chat",
        providers = listOf(
            ProviderProfile(
                id = "deepseek",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1",
                apiKeyEnv = "DEEPSEEK_API_KEY",
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = "deepseek/deepseek-chat",
                provider = "deepseek",
                model = "deepseek-chat",
                displayName = "DeepSeek Chat",
                contextWindow = 64_000,
                maxOutputTokens = 8_192,
                toolUse = true,
                pricePerMillionInMicros = 270_000,
                pricePerMillionOutMicros = 1_100_000,
            ),
        ),
    )

    /** Заготовка каталога для round-trip. */
    private fun catalogEntry(): ProviderCatalogEntry {
        val config = agentConfig()
        return ProviderCatalogEntry(config.providers.first(), config.models)
    }

    /** Вызов журнала для round-trip: страница, полное содержимое и событие. */
    private fun toolCall(): ToolCall = ToolCall(
        id = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "read_file",
        arguments = """{"path":"src/App.kt"}""",
        result = "содержимое",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 42,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = toolCallAt,
    )

    @Test
    fun `нагрузка чужого типа для нового сообщения журнала пропускается`() {
        // Имя типа известно, но нагрузка — приветствие: обязательных полей запроса журнала
        // в ней нет, и разбор обязан вернуть Ignored, а не исключение (О-9).
        val wrongPayload = envelope(
            type = ClientMessageType.TOOL_CALLS,
            serializer = ClientMessage.Hello.serializer(),
            value = ClientMessage.Hello(ProtocolVersion.CURRENT),
        )
        val ignored = assertIs<DecodeResult.Ignored>(ProtocolCodec.decodeClientMessage(wrongPayload))
        assertEquals(ClientMessageType.TOOL_CALLS, ignored.rawType)
        assertTrue(ignored.reason.contains("полезная нагрузка не разобрана"))
    }

    @Test
    fun `каждый вариант ProtocolError переживает round-trip`() {
        val internalWithoutDetail = ProtocolError.Internal(message = "внутренняя ошибка")
        val errors = listOf(
            ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"),
            ProtocolError.NotFound(what = "src/Main.kt"),
            ProtocolError.NotAGitRepository(path = "/projects/aide"),
            ProtocolError.WorkspaceClosed(workspaceId = workspaceId),
            ProtocolError.NotImplemented(what = "поиск по символам"),
            internalWithoutDetail,
            ProtocolError.Internal(message = "внутренняя ошибка", detail = "trace: …"),
        )
        errors.forEach { error ->
            val message = HostMessage.Failure(requestId, error)
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }

        // Ошибка без detail остаётся без detail: клиент различает «детали нет» и «деталь пуста».
        val decoded = hostMessage(ProtocolCodec.encode(HostMessage.Failure(requestId, internalWithoutDetail)))
        assertNull(assertIs<ProtocolError.Internal>(assertIs<HostMessage.Failure>(decoded).error).detail)
    }

    @Test
    fun `каждый вариант IncompatibilityReason переживает round-trip`() {
        IncompatibilityReason.entries.forEach { reason ->
            val message = HostMessage.Incompatible(reason, ProtocolVersion(2, 5))
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
    }

    @Test
    fun `каждый режим хоста и каждое событие переживают round-trip`() {
        HostMode.entries.forEach { mode ->
            val message = HostMessage.State(requestId, statePayload(mode))
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
        val events: List<HostEvent> = listOf(
            HostEvent.WorkspaceChanged(workspaceId),
            HostEvent.RunStateChanged(agentRun().copy(state = RunState.PAUSED)),
            HostEvent.HostShuttingDown,
        )
        events.forEach { event ->
            val message = HostMessage.Event(event)
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
    }

    private fun statePayload(mode: HostMode) = HostStatePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        branch = "main",
        headCommit = "",
        uptimeMillis = 1,
        mode = mode,
    )
}
