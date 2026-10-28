package org.sterl.llmpeon.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class MentionReferenceParserTest {

    private static final Set<Character> TRIGGERS = Set.of('@', '#');

    @Test
    void parsesFileAndTypeReferences() {
        var result = MentionReferenceParser.parse("look at @src/Foo.java and #org.example.Bar", TRIGGERS);

        assertThat(result).extracting(MentionReference::trigger).containsExactly('@', '#');
        assertThat(result).extracting(MentionReference::target)
                .containsExactly("src/Foo.java", "org.example.Bar");
    }

    @Test
    void keepsOffsets() {
        var text = "hi @src/Foo.java";
        var ref = MentionReferenceParser.parse(text, TRIGGERS).getFirst();

        assertThat(ref.start()).isEqualTo(3);
        assertThat(ref.length()).isEqualTo(13);
        assertThat(text.substring(ref.start(), ref.start() + ref.length())).isEqualTo("@src/Foo.java");
    }

    @Test
    void deduplicatesByTriggerAndTarget() {
        var result = MentionReferenceParser.parse("@src/Foo.java then @src/Foo.java", TRIGGERS);

        assertThat(result).hasSize(1);
    }

    @Test
    void sameTargetWithDifferentTriggersIsNotDeduplicated() {
        var result = MentionReferenceParser.parse("@Foo #Foo", TRIGGERS);

        assertThat(result).hasSize(2);
    }

    @Test
    void ignoresEmailLikeAtSigns() {
        assertThat(MentionReferenceParser.parse("mail@example.com", TRIGGERS)).isEmpty();
    }

    @Test
    void ignoresTypeReferenceAfterPunctuation() {
        assertThat(MentionReferenceParser.parse("(#Foo", TRIGGERS)).isEmpty();
    }

    @Test
    void stripsTrailingPathPunctuation() {
        var result = MentionReferenceParser.parse("see @src/Foo.java, and @docs/x.md).", TRIGGERS);

        assertThat(result).extracting(MentionReference::target)
                .containsExactly("src/Foo.java", "docs/x.md");
    }

    @Test
    void stripsTrailingTypePunctuation() {
        assertThat(MentionReferenceParser.parse("#org.example.Bar)", TRIGGERS))
                .extracting(MentionReference::target)
                .containsExactly("org.example.Bar");
        assertThat(MentionReferenceParser.parse("#org.example.", TRIGGERS))
                .extracting(MentionReference::target)
                .containsExactly("org.example");
    }

    @Test
    void acceptsNestedTypeSeparator() {
        assertThat(MentionReferenceParser.parse("#a.b.C$D", TRIGGERS))
                .extracting(MentionReference::target)
                .containsExactly("a.b.C$D");
    }

    @Test
    void referencesAtStartAndAfterNewlineAreFound() {
        assertThat(MentionReferenceParser.parse("@a\n#b", TRIGGERS)).hasSize(2);
    }

    @Test
    void bareTriggerIsIgnored() {
        assertThat(MentionReferenceParser.parse("just @ and #", TRIGGERS)).isEmpty();
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertThat(MentionReferenceParser.parse(null, TRIGGERS)).isEmpty();
        assertThat(MentionReferenceParser.parse("", TRIGGERS)).isEmpty();
        assertThat(MentionReferenceParser.parse("@a", null)).isEmpty();
        assertThat(MentionReferenceParser.parse("@a", Set.of())).isEmpty();
    }
}
