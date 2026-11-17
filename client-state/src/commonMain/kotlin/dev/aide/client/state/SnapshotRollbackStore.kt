package dev.aide.client.state

import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.protocol.ProtocolError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние отката к снапшоту и переходы между ним (T-1.6).
 *
 * Отдельный объект, а не методы [AppStateStore]: у отката своя пара «данные и переходы"
 * (список снапшотов, выбранный снапшот, предпросмотр изменений, сам откат), и складывать её
 * в общий держатель экранов значило бы держать его на пороге сложности линтера.
 */
class SnapshotRollbackStore {

    internal val mutableState = MutableStateFlow(SnapshotRollbackState())

    /** Состояние отката: список, предпросмотр и признак выполняемого отката. */
    val state: StateFlow<SnapshotRollbackState> = mutableState.asStateFlow()

    /** Открывает список: сбрасывает предпросмотр и показывает загрузку. */
    fun open() {
        mutableState.update { SnapshotRollbackState(list = ScreenState.Loading) }
    }

    /** Принимает загруженный список снапшотов. */
    fun loaded(snapshots: List<Snapshot>) {
        mutableState.update {
            it.copy(list = if (snapshots.isEmpty()) ScreenState.Empty else ScreenState.Loaded(snapshots))
        }
    }

    /** Переводит ошибку загрузки списка в состояние экрана. */
    fun failed(error: ProtocolError) {
        mutableState.update { it.copy(list = error.toScreenState()) }
    }

    /**
     * Выбирает снапшот и показывает загрузку предпросмотра.
     *
     * Сбрасывает состояние прежнего отката: смена выбора инвалидирует поздний успех
     * или ошибку по другому снапшоту.
     */
    fun select(ref: SnapshotRef) {
        mutableState.update {
            it.copy(
                selected = ref,
                preview = ScreenState.Loading,
                reverting = null,
                revertError = null,
                completedCommit = null,
            )
        }
    }

    /** Принимает предпросмотр выбранного снапшота. */
    fun previewLoaded(preview: SnapshotPreview) {
        mutableState.update { state ->
            if (state.selected != preview.snapshot.ref) return@update state
            state.copy(preview = ScreenState.Loaded(preview))
        }
    }

    /**
     * Переводит ошибку предпросмотра в состояние экрана.
     *
     * Игнорирует ответ, если с момента запроса пользователь выбрал другой снапшот:
     * иначе поздняя ошибка по старому снапшоту затрёт актуальный предпросмотр.
     */
    fun previewFailed(error: ProtocolError, ref: SnapshotRef) {
        mutableState.update { state ->
            if (state.selected != ref) return@update state
            state.copy(preview = error.toScreenState())
        }
    }

    /** Показывает, что откат для выбранного снапшота выполняется. */
    fun revert(ref: SnapshotRef) {
        mutableState.update { it.copy(reverting = ref, revertError = null, completedCommit = null) }
    }

    /**
     * Откат завершился успешно: сохраняем хеш обратного коммита и сбрасываем признак выполнения.
     *
     * Игнорирует ответ, если с момента запроса пользователь выбрал другой снапшот,
     * закрыл шторку или начал новый откат: так поздний успех не портит актуальное состояние.
     * Bottom sheet остаётся открытым, чтобы пользователь увидел хеш и сам закрыл шторку.
     */
    fun revertSucceeded(ref: SnapshotRef, commit: String) {
        mutableState.update { state ->
            if (state.reverting != ref) return@update state
            state.copy(reverting = null, revertError = null, completedCommit = commit)
        }
    }

    /**
     * Закрывает bottom sheet и сбрасывает всё состояние отката.
     *
     * Нужно, чтобы поздний ответ по уже закрытому экрану не проявился при следующем открытии.
     */
    fun dismiss() {
        mutableState.update { SnapshotRollbackState(list = it.list) }
    }

    /**
     * Переводит ошибку отката в состояние экрана.
     *
     * Игнорирует ответ, если с момента запроса пользователь выбрал другой снапшот
     * или начал новый откат: так поздняя ошибка не портит актуальное состояние.
     */
    fun revertFailed(error: ProtocolError, ref: SnapshotRef) {
        mutableState.update { state ->
            if (state.reverting != ref) return@update state
            state.copy(reverting = null, revertError = error.toScreenState())
        }
    }

}

/** Пометка «нет связи»: true — связь потеряна, false — восстановлена. */
fun SnapshotRollbackStore.setOffline(offline: Boolean) {
    mutableState.update { state ->
        val list = when (val list = state.list) {
            is ScreenState.Loaded -> if (offline) ScreenState.Offline(list.data) else list
            is ScreenState.Offline -> if (offline) list else ScreenState.Loaded(list.cached)
            else -> list
        }
        state.copy(list = list)
    }
}

/**
 * Состояние экрана отката к снапшоту.
 *
 * [list] — список снапшотов; [preview] — предпросмотр выбранного снапшота; [reverting] —
 * снапшот, к которому сейчас выполняется откат; [revertError] — ошибка последнего отката.
 */
data class SnapshotRollbackState(
    /** Список снапшотов. */
    val list: ScreenState<List<Snapshot>> = ScreenState.Empty,
    /** Выбранный снапшот; null, если пользователь ещё ничего не выбрал. */
    val selected: SnapshotRef? = null,
    /** Предпросмотр выбранного снапшота. */
    val preview: ScreenState<SnapshotPreview>? = null,
    /** Снапшот, к которому сейчас выполняется откат. */
    val reverting: SnapshotRef? = null,
    /** Ошибка последнего отката. */
    val revertError: ScreenState<Nothing>? = null,
    /** Хеш созданного обратного коммита после успешного отката. */
    val completedCommit: String? = null,
)
