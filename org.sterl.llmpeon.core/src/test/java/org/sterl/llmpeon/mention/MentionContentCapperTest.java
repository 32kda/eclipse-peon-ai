package org.sterl.llmpeon.mention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MentionContentCapperTest {

    @Test
    void keepsContentWhenWithinLimit() {
        var capped = MentionContentCapper.cap("a\nb\nc", 5);

        assertThat(capped.truncated()).isFalse();
        assertThat(capped.content()).isEqualTo("a\nb\nc");
        assertThat(capped.shownLines()).isEqualTo(3);
        assertThat(capped.totalLines()).isEqualTo(3);
    }

    @Test
    void capsToFirstLinesAndDisclosesTotal() {
        var capped = MentionContentCapper.cap("1\n2\n3\n4\n5", 3);

        assertThat(capped.truncated()).isTrue();
        assertThat(capped.content()).isEqualTo("1\n2\n3");
        assertThat(capped.shownLines()).isEqualTo(3);
        assertThat(capped.totalLines()).isEqualTo(5);
    }

    @Test
    void zeroOrNegativeLimitMeansNoCap() {
        var capped = MentionContentCapper.cap("1\n2\n3", 0);
        assertThat(capped.truncated()).isFalse();
        assertThat(capped.content()).isEqualTo("1\n2\n3");

        assertThat(MentionContentCapper.cap("1\n2\n3", -1).truncated()).isFalse();
    }

    @Test
    void nullIsEmpty() {
        var capped = MentionContentCapper.cap(null, 10);

        assertThat(capped.content()).isEmpty();
        assertThat(capped.truncated()).isFalse();
        assertThat(capped.shownLines()).isZero();
    }
}
