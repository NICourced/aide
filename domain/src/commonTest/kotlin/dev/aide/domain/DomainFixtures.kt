package dev.aide.domain

import kotlinx.datetime.Instant

object DomainFixtures {

    private val t0 = Instant.parse("2026-09-22T10:00:00Z")
    private val t1 = Instant.parse("2026-09-22T10:01:00Z")

    val hunkLineAdded = HunkLine(kind = LineKind.ADDED, oldNumber = null, newNumber = 4, text = "val token = issue()")
    val hunkLineRemoved = HunkLine(kind = LineKind.REMOVED, oldNumber = 4, newNumber = null, text = "val token = null")
    val hunkLineContext = HunkLine(kind = LineKind.CONTEXT, oldNumber = 3, newNumber = 3, text = "fun login() {")

    val hunk = Hunk(
        id = HunkId("h-1"),
        filePath = "src/auth/Login.kt",
        startLine = 3,
        kind = HunkKind.REPLACE,
        risk = RiskLevel.SAFE,
        lines = listOf(hunkLineContext, hunkLineRemoved, hunkLineAdded),
    )

    val fileChange = FileChange(
        path = "src/auth/Login.kt",
        changeKind = FileChangeKind.MODIFIED,
        hunks = listOf(hunk),
        addedLines = 1,
        removedLines = 1,
    )

    val fileChangeRenamed = FileChange(
        path = "src/auth/Session.kt",
        changeKind = FileChangeKind.RENAMED,
        hunks = listOf(hunk.copy(id = HunkId("h-2"), filePath = "src/auth/Session.kt")),
        addedLines = 1,
        removedLines = 1,
        previousPath = "src/auth/Login.kt",
    )

    val packet = ChangePacket(
        id = PacketId("p-1"),
        taskId = TaskId("t-1"),
        revision = 1,
        title = "Авторизация: выдача токена",
        summary = "Заменил заглушку выдачи токена на вызов сервиса.",
        files = listOf(fileChange, fileChangeRenamed),
        risk = RiskLevel.SAFE,
        tests = TestStatus(state = TestState.GREEN, failed = emptyList(), lintFindings = 0),
        source = ChangeSource.AGENT,
        status = PacketStatus.AWAITING_REVIEW,
        branch = "ai/t-1",
        snapshotRef = SnapshotRef("refs/ai/snap/1758535200-before-agent-step"),
        createdAt = t1,
    )

    val task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Сделай выдачу токена через сервис",
        branch = "ai/t-1",
        status = TaskStatus.REVIEW,
        createdAt = t0,
        runIds = listOf(RunId("r-1")),
    )

    val run = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.FINISHED,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        plan = listOf(PlanStep(index = 0, summary = "Разобрать Login.kt", status = StepStatus.DONE)),
        toolCallIds = listOf(ToolCallId("tc-1")),
        snapshots = listOf(SnapshotRef("refs/ai/snap/1758535200000-before-agent-step")),
        startedAt = t0,
        finishedAt = t1,
        elapsedMillis = 60_000,
        cost = Cost(amountMicros = 12_500, known = true),
        modelAlias = "openai/gpt-4o",
        interruptReason = null,
    )

    val toolCall = ToolCall(
        id = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "fs.write",
        arguments = """{"path":"src/auth/Login.kt"}""",
        result = "ok",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 42,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = true,
        approval = ApprovalDecision.ALLOW_ONCE,
        at = t1,
    )

    val toolPermission = ToolPermission(tool = "fs.write", read = Permission.ALLOW, write = Permission.ASK)

    val decision = ReviewDecision(
        packetId = PacketId("p-1"),
        packetRevision = 1,
        scope = DecisionScope.HUNK,
        targetHunkId = HunkId("h-1"),
        value = DecisionValue.CHANGES_REQUESTED,
        comment = "Вынеси выдачу токена в отдельную функцию",
        clientPlatform = ClientPlatform.ANDROID,
        decidedAt = t1,
    )

    val snapshot = Snapshot(
        ref = SnapshotRef("refs/ai/snap/1758535200-before-agent-step"),
        label = "before-agent-step",
        commit = "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736",
        trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
        taskId = TaskId("t-1"),
        createdAt = t0,
    )

    /** Конфигурация моделей: два провайдера и две модели, одна из них — по умолчанию (T-1.56). */
    val agentConfig = AgentConfig(
        defaultModel = "deepseek/deepseek-chat",
        providers = listOf(
            ProviderProfile(
                id = "deepseek",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1",
                apiKeyEnv = "DEEPSEEK_API_KEY",
            ),
            ProviderProfile(
                id = "local",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKeyEnv = null,
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
            ModelProfile(
                alias = "local/llama",
                provider = "local",
                model = "llama3.1",
                contextWindow = 8_192,
                maxOutputTokens = 2_048,
                toolUse = false,
            ),
        ),
    )

    /** Заготовка каталога: провайдер со своими моделями. */
    val catalogEntry = ProviderCatalogEntry(
        provider = agentConfig.providers.first(),
        models = listOf(agentConfig.models.first()),
    )

    /** Образцы «пусто и null»: проверяют, что кодек не теряет пустые списки и незаполненные поля. */
    val emptyRun = AgentRun(
        id = RunId("r-empty"),
        taskId = TaskId("t-empty"),
        state = RunState.PLANNED,
        mode = AutonomyMode.SUGGEST_ONLY,
        plan = emptyList(),
        toolCallIds = emptyList(),
        startedAt = t0,
        finishedAt = null,
        elapsedMillis = 0,
        cost = Cost(amountMicros = 0, known = false),
        interruptReason = null,
    )

    val emptyPacket = packet.copy(
        id = PacketId("p-empty"),
        revision = 1,
        files = emptyList(),
        tests = TestStatus(state = TestState.NOT_RUN),
        summary = "",
        snapshotRef = null,
    )

    val packetLevelDecision = decision.copy(scope = DecisionScope.PACKET, targetHunkId = null, comment = null)
}
