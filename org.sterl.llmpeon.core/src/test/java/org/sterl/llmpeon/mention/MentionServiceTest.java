package org.sterl.llmpeon.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MentionServiceTest {

    private static MentionProvider provider(char trigger, String value) {
        return new MentionProvider() {
            @Override
            public char trigger() {
                return trigger;
            }

            @Override
            public List<MentionCandidate> find(String prefix, int limit) {
                return List.of(MentionCandidate.of(value + ":" + prefix));
            }
        };
    }

    @Test
    void routesByTrigger() {
        var service = new MentionService(List.of(provider('@', "file"), provider('#', "type")));

        assertThat(service.find('@', "src", 10))
                .extracting(MentionCandidate::insertText)
                .containsExactly("file:src");
        assertThat(service.find('#', "List", 10))
                .extracting(MentionCandidate::insertText)
                .containsExactly("type:List");
    }

    @Test
    void unknownTriggerReturnsEmpty() {
        var service = new MentionService(List.of(provider('@', "file")));

        assertThat(service.find('!', "x", 10)).isEmpty();
        assertThat(service.providerFor('!')).isEmpty();
    }

    @Test
    void triggersAreDeduplicatedAndOrdered() {
        var service = new MentionService(List.of(
                provider('@', "a"), provider('#', "b"), provider('@', "c")));

        assertThat(service.triggers()).containsExactly('@', '#');
    }

    @Test
    void firstProviderWinsOnTriggerCollision() {
        var service = new MentionService(List.of(provider('@', "first"), provider('@', "second")));

        assertThat(service.find('@', "x", 10))
                .extracting(MentionCandidate::insertText)
                .containsExactly("first:x");
    }

    @Test
    void nullPrefixBecomesEmpty() {
        var service = new MentionService(List.of(provider('@', "file")));

        assertThat(service.find('@', null, 10))
                .extracting(MentionCandidate::insertText)
                .containsExactly("file:");
    }

    @Test
    void emptyProviderListIsSafe() {
        var service = new MentionService(null);

        assertThat(service.triggers()).isEmpty();
        assertThat(service.find('@', "x", 10)).isEmpty();
    }
}
