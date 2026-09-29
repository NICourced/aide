package dev.aide.client.state

/**
 * Состояние экрана (§ 6.1).
 *
 * Один тип вместо набора флагов: нельзя одновременно оказаться в «загрузке»
 * и в «ошибке», а компилятор требует обработать все случаи в `when`.
 */
sealed interface ScreenState<out T> {

    /** Данных ещё нет и запрос не начат. */
    data object Empty : ScreenState<Nothing>

    /** Идёт загрузка; UI показывает скелетон структуры, а не пустой экран. */
    data object Loading : ScreenState<Nothing>

    /** Данные получены. */
    data class Loaded<T>(val data: T) : ScreenState<T>

    /** Запрос завершился ошибкой. */
    data class Failed(
        /** Вид ошибки — от него зависит текст и действие. */
        val kind: ErrorKind,
        /** Что именно случилось, для показа пользователю. */
        val detail: String,
        /** Техническая деталь; показывается по запросу. */
        val technical: String? = null,
    ) : ScreenState<Nothing>

    /** Связь с хостом потеряна; показываются данные из кэша с явной пометкой (§ 3.5). */
    data class Offline<T>(val cached: T) : ScreenState<T>

    /** Доступ к объекту закрыт (§ 10.1). */
    data class NoPermission(
        /** К чему нет доступа. */
        val path: String,
        /** Почему отказано. */
        val reason: String,
    ) : ScreenState<Nothing>

    /** Виды ошибок, различаемые интерфейсом. */
    enum class ErrorKind {
        /** Путь не существует. */
        PATH_MISSING,

        /** Каталог есть, но это не git-репозиторий. */
        NOT_A_REPOSITORY,

        /** Версии протокола несовместимы. */
        INCOMPATIBLE,

        /** Прочая ошибка, в том числе внутренняя ошибка хоста. */
        OTHER,
    }
}
