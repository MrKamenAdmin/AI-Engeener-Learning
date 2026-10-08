package course.m06agents;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Всё, что циклу нужно от клиента. Ему удовлетворяет {@code client.messages()::create},
 * декоратор {@link Budgeted} и fake в тестах.
 */
@FunctionalInterface
public interface Model {
    Message create(MessageCreateParams params);
}
