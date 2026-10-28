package org.sterl.llmpeon.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MentionFilterTest {

    private static final List<MentionCandidate> FILES = List.of(
            MentionCandidate.of("/project/src/main/Foo.java"),
            MentionCandidate.of("/project/src/test/Bar.java"),
            MentionCandidate.of("/project/docs/guide.md"),
            MentionCandidate.of("/project/src/main/FooHelper.java"));

    @Test
    void basenameMatchRanksFirst() {
        var result = MentionFilter.find(FILES, "Foo", 0);

        assertThat(result).extracting(MentionCandidate::insertText)
                .containsExactly(
                        "/project/src/main/Foo.java",
                        "/project/src/main/FooHelper.java");
    }

    @Test
    void pathMatchIsCaseInsensitive() {
        var result = MentionFilter.find(FILES, "SRC/MAIN", 0);

        assertThat(result).extracting(MentionCandidate::insertText)
                .containsExactly(
                        "/project/src/main/Foo.java",
                        "/project/src/main/FooHelper.java");
    }

    @Test
    void substringMatchRanksLast() {
        var result = MentionFilter.find(FILES, "guide", 0);

        assertThat(result).extracting(MentionCandidate::insertText)
                .containsExactly("/project/docs/guide.md");
    }

    @Test
    void noMatchIsEmpty() {
        assertThat(MentionFilter.find(FILES, "zzz", 0)).isEmpty();
    }

    @Test
    void limitCapsTheResult() {
        assertThat(MentionFilter.find(FILES, "Foo", 1)).hasSize(1);
        assertThat(MentionFilter.find(FILES, "Foo", 1).getFirst().insertText())
                .isEqualTo("/project/src/main/Foo.java");
    }

    @Test
    void emptyPrefixReturnsAllSorted() {
        var result = MentionFilter.find(FILES, "", 0);

        assertThat(result).hasSize(4);
        assertThat(result.getFirst().insertText()).isEqualTo("/project/docs/guide.md");
    }

    @Test
    void rankOfLowerUsesTheSameOrderAsFind() {
        assertThat(MentionFilter.rankOfLower("/project/src/main/foo.java", "foo")).isEqualTo(0);
        assertThat(MentionFilter.rankOfLower("/project/src/main/foo.java", "/project/src")).isEqualTo(1);
        assertThat(MentionFilter.rankOfLower("/project/src/main/foo.java", "src/main")).isEqualTo(2);
        assertThat(MentionFilter.rankOfLower("/project/src/main/foo.java", "zzz")).isEqualTo(-1);
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(MentionFilter.find(null, "x", 10)).isEmpty();
        assertThat(MentionFilter.find(List.of(), "x", 10)).isEmpty();
        assertThat(MentionFilter.find(FILES, null, 10)).hasSize(4);
    }
}
