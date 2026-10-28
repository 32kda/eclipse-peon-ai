package org.sterl.llmpeon.session;

import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

/**
 * Builds a programmatically generated reminder that the model sees as a normal user message.
 *
 * <p>The flag lives in {@link UserMessage#attributes()}; langchain4j never sends attributes to the
 * model. The session transcript/UI uses {@link MessageKind#REMINDER} to render it differently.</p>
 */
public final class UserReminder {

    public static final String ATTRIBUTE = "peon.reminder";

    private UserReminder() {
    }

    public static UserMessage create(String text) {
        return UserMessage.builder()
                .addContent(TextContent.from(text))
                .attributes(Map.of(ATTRIBUTE, true))
                .build();
    }

    public static boolean isReminder(ChatMessage message) {
        return message instanceof UserMessage user
                && Boolean.TRUE.equals(user.attributes().get(ATTRIBUTE));
    }
}
