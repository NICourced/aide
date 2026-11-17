package dev.aide.client.ui.screens

import dev.aide.domain.SnapshotRef

/**
 * Колбэки экрана отката к снапшоту (T-1.6).
 *
 * Отдельный объект, а не четыре параметра [SnapshotRollbackScreen]: линтер считает параметры
 * функции и ругается на длинный список (`LongParameterList`). Группировка по смыслу не меняет
 * поведения, но позволяет добавить действия без превышения порога.
 */
data class SnapshotRollbackActions(
    /** Закрыть bottom sheet. */
    val onDismiss: () -> Unit,
    /** Перезагрузить список снапшотов. */
    val onLoadList: () -> Unit,
    /** Выбрать снапшот для предпросмотра. */
    val onSelect: (SnapshotRef) -> Unit,
    /** Подтвердить откат к выбранному снапшоту. */
    val onRevert: (SnapshotRef, String) -> Unit,
)
