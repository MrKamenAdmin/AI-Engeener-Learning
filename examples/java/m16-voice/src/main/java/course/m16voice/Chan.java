package course.m16voice;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Закрываемая очередь между этапами каскада — аналог канала Go. Один читатель.
 * Блокирующие операции реагируют на {@link Thread#interrupt()}: так работает отмена.
 * ponytail: очередь без границы (send не блокируется); backpressure — ArrayBlockingQueue + отдельный флаг закрытия.
 */
public final class Chan<T> implements Voice.Sink<T> {
    private static final Object END = new Object();
    private final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();

    @Override
    public void send(T v) throws InterruptedException {
        q.put(v);
    }

    public void close() {
        q.add(END);
    }

    /** Следующий элемент или null, если канал закрыт и пуст. */
    @SuppressWarnings("unchecked")
    public T receive() throws InterruptedException {
        Object o = q.take();
        if (o == END) {
            q.add(END); // повторный receive тоже увидит закрытие
            return null;
        }
        return (T) o;
    }
}
