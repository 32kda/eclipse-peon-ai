package org.sterl.llmpeon.test;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.sterl.llmpeon.context.ContextItem;
import org.sterl.llmpeon.parts.mention.MentionContextResolver;

/**
 * Integration tests for turning {@code @path}/{@code #Type} references into bounded context.
 * Runs against the dedicated {@code test_project} fixture.
 */
public class MentionContextResolverTest extends AbstractIntegrationTest {

    private MentionContextResolver resolver() {
        return new MentionContextResolver(() -> project);
    }

    private static String render(List<ContextItem> items) {
        return items.stream()
                .map(item -> item.label() + "\n" + item.render())
                .collect(Collectors.joining("\n---\n"));
    }

    @Test
    public void resolvesWorkspaceFileReference() {
        var items = resolver().resolve("look at @/test_project/src/org/sterl/fixture/Alpha.java");
        var rendered = render(items);

        assertContains(rendered, "=== FILE: /test_project/src/org/sterl/fixture/Alpha.java ===");
        assertContains(rendered, "public class Alpha");
    }

    @Test
    public void capsLongFileAndDisclosesLineCounts() {
        var content = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            if (i > 1) content.append('\n');
            content.append("line ").append(i);
        }
        eclipseWriteFile("data/mention-long.txt", content.toString());

        var items = resolver().resolve("@/test_project/data/mention-long.txt");
        var rendered = render(items);

        assertContains(rendered, "showing 200 of 300 lines");
        assertContains(rendered, "100 more line(s) not shown");
        assertTrue("line 201 must not be attached", !rendered.contains("line 201"));
    }

    @Test
    public void resolvesJavaTypeReference() {
        var items = resolver().resolve("explain #org.sterl.fixture.Alpha");
        var rendered = render(items);

        assertContains(rendered, "=== TYPE: org.sterl.fixture.Alpha ===");
        assertContains(rendered, "public class Alpha");
    }

    @Test
    public void resolvesSystemFileOutsideWorkspace() throws Exception {
        // Drag-and-drop / paste of a file outside the workspace inserts an absolute disk path.
        var tmp = Files.createTempFile("peon-mention-", ".txt");
        try {
            Files.writeString(tmp, "system file content\nsecond line");

            var items = resolver().resolve("@" + tmp.toAbsolutePath());
            var rendered = render(items);

            assertContains(rendered, "system file content");
            assertContains(rendered, "second line");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void ignoresPastedJavaAnnotations() {
        // '@Override' is Java syntax, not a file mention — no bogus "not found" note.
        var items = resolver().resolve("@Override\npublic void x() {}");

        assertTrue(items.isEmpty());
    }

    @Test
    public void unresolvedReferenceIsDisclosed() {
        var items = resolver().resolve("@/test_project/does-not-exist.txt");
        var rendered = render(items);

        assertContains(rendered, "Mention limits");
        assertContains(rendered, "(not found)");
    }

    @Test
    public void attachmentLimitIsDisclosed() {
        for (int i = 0; i < 6; i++) {
            eclipseWriteFile("data/mention-cap-" + i + ".txt", "x");
        }
        var text = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            text.append("@/test_project/data/mention-cap-").append(i).append(".txt ");
        }

        var items = resolver().resolve(text.toString());
        var rendered = render(items);

        assertContains(rendered, "Attached 5 of 6 mention(s)");
        assertContains(rendered, "(attachment limit)");
    }
}
