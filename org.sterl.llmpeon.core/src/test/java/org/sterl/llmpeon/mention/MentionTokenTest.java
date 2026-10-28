package org.sterl.llmpeon.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class MentionTokenTest {

    private static final Set<Character> TRIGGERS = Set.of('@', '#');

    @Test
    void parsesTokenAtStart() {
        var token = MentionToken.at("@src/Fo", 7, TRIGGERS).orElseThrow();

        assertThat(token.trigger()).isEqualTo('@');
        assertThat(token.prefix()).isEqualTo("src/Fo");
        assertThat(token.start()).isZero();
        assertThat(token.length()).isEqualTo(7);
    }

    @Test
    void parsesTokenAfterWhitespace() {
        var token = MentionToken.at("hello @docs/in", 14, TRIGGERS).orElseThrow();

        assertThat(token.trigger()).isEqualTo('@');
        assertThat(token.prefix()).isEqualTo("docs/in");
        assertThat(token.start()).isEqualTo(6);
        assertThat(token.length()).isEqualTo(8);
    }

    @Test
    void parsesPrefixUpToCaret() {
        var token = MentionToken.at("hello @src/Fo bar", 10, TRIGGERS).orElseThrow();

        assertThat(token.prefix()).isEqualTo("src");
        assertThat(token.start()).isEqualTo(6);
        assertThat(token.length()).isEqualTo(4);
    }

    @Test
    void bareTriggerHasEmptyPrefix() {
        var token = MentionToken.at("@", 1, TRIGGERS).orElseThrow();

        assertThat(token.trigger()).isEqualTo('@');
        assertThat(token.prefix()).isEmpty();
        assertThat(token.length()).isEqualTo(1);
    }

    @Test
    void ignoresTriggerInsideWord() {
        assertThat(MentionToken.at("mail@example.com", 16, TRIGGERS)).isEmpty();
    }

    @Test
    void ignoresTriggerAfterPunctuation() {
        assertThat(MentionToken.at("(@foo", 5, TRIGGERS)).isEmpty();
    }

    @Test
    void ignoresCaretOnWhitespace() {
        assertThat(MentionToken.at("hello @foo ", 11, TRIGGERS)).isEmpty();
    }

    @Test
    void ignoresUnknownTrigger() {
        assertThat(MentionToken.at("!foo", 4, TRIGGERS)).isEmpty();
    }

    @Test
    void handlesOutOfBoundsAndNull() {
        assertThat(MentionToken.at(null, 0, TRIGGERS)).isEmpty();
        assertThat(MentionToken.at("@foo", -1, TRIGGERS)).isEmpty();
        assertThat(MentionToken.at("@foo", 5, TRIGGERS)).isEmpty();
        assertThat(MentionToken.at("@foo", 4, Set.of())).isEmpty();
    }
}
