package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Событие удаления: по [token] операцию можно вернуть, пока не пришло следующее удаление или отказ от отмены. */
data class DeletionEvent(val token: Long)

/**
 * Последняя удалённая операция в памяти. Отмена работает на уровне приложения, а не конкретного экрана
 * (design.md, решение 5). Срок отмены (5 секунд) отсчитывает интерфейс и затем вызывает [discard].
 */
@Singleton
class UndoDeletionBuffer @Inject constructor() {
    private val _events = MutableSharedFlow<DeletionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DeletionEvent> = _events.asSharedFlow()

    private var nextToken = 1L
    private var pending: Pair<Long, Operation>? = null

    @Synchronized
    fun push(operation: Operation): DeletionEvent {
        val token = nextToken++
        pending = token to operation
        return DeletionEvent(token).also { _events.tryEmit(it) }
    }

    /** Забирает операцию для восстановления, если [token] ещё актуален. */
    @Synchronized
    fun take(token: Long): Operation? = pending?.takeIf { it.first == token }?.second?.also { pending = null }

    @Synchronized
    fun discard(token: Long) {
        if (pending?.first == token) pending = null
    }
}

class DeleteOperation @Inject constructor(
    private val operations: OperationRepository,
    private val buffer: UndoDeletionBuffer,
) {
    /** Удаляет операцию сразу и даёт возможность отменить удаление. `false`, если операции уже нет. */
    suspend operator fun invoke(id: Long): Boolean {
        val operation = operations.get(id) ?: return false
        operations.delete(id)
        buffer.push(operation)
        return true
    }
}

class RestoreDeletedOperation @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
    private val buffer: UndoDeletionBuffer,
) {
    /**
     * Возвращает операцию с прежними полями и id. `false`, если срок отмены прошёл или категорию операции
     * успели удалить за эти 5 секунд (вставка нарушила бы внешний ключ).
     */
    suspend operator fun invoke(token: Long): Boolean {
        val operation = buffer.take(token) ?: return false
        if (categories.get(operation.categoryId) == null) return false
        operations.restore(operation)
        return true
    }
}
