package org.sterl.llmpeon.parts.mention;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.sterl.llmpeon.context.ContextItem;
import org.sterl.llmpeon.context.SimpleContextItem;
import org.sterl.llmpeon.mention.MentionContentCapper;
import org.sterl.llmpeon.mention.MentionReference;
import org.sterl.llmpeon.mention.MentionReferenceParser;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.parts.shared.IoUtils;
import org.sterl.llmpeon.shared.FileLines;

/**
 * Turns {@code @path} / {@code #Type} references in a chat message into bounded context items.
 *
 * <p>{@code @} resolves a workspace file first, then an absolute system file (drag-and-drop / paste
 * of a file outside the workspace). {@code #} resolves a Java type and attaches its source range.</p>
 *
 * <p>Hard caps (never silent): per attachment {@value #MAX_LINES_PER_ATTACHMENT} lines, at most
 * {@value #MAX_ATTACHMENTS} attachments and {@value #MAX_TOTAL_CHARS} characters per turn, and
 * {@value #MAX_FILE_BYTES} bytes read from disk. Every cap is disclosed — in the item header
 * ({@code showing N of M lines}) and in a final {@code Mention limits} item that names what was not
 * attached and why.</p>
 */
public class MentionContextResolver {

    public static final int MAX_ATTACHMENTS = 5;
    public static final int MAX_LINES_PER_ATTACHMENT = 200;
    public static final int MAX_TOTAL_CHARS = 64_000;
    public static final long MAX_FILE_BYTES = 2_000_000;

    private static final Set<Character> TRIGGERS = Set.of('@', '#');
    private static final Pattern FILE_EXTENSION = Pattern.compile(".*\\.[A-Za-z][A-Za-z0-9]{0,7}");
    private static final ILog LOG = Platform.getLog(MentionContextResolver.class);

    private final Supplier<IProject> project;

    /** Read result: either content, or a precise reason why nothing was attached. */
    private record ReadResult(String content, String problem) {
        static ReadResult ok(String content) {
            return new ReadResult(content, null);
        }

        static ReadResult missing() {
            return new ReadResult(null, "not found");
        }

        static ReadResult problem(String problem) {
            return new ReadResult(null, problem);
        }
    }

    public MentionContextResolver(Supplier<IProject> project) {
        this.project = project;
    }

    public List<ContextItem> resolve(String text) {
        // '@' is also Java syntax (@Override, @Test). Only path-shaped targets are file mentions,
        // so pasted code does not produce bogus "not found" notes.
        var references = MentionReferenceParser.parse(text, TRIGGERS).stream()
                .filter(reference -> reference.trigger() != '@' || looksLikeFile(reference.target()))
                .toList();
        if (references.isEmpty()) return List.of();

        var result = new ArrayList<ContextItem>();
        var limits = new ArrayList<String>();
        int attached = 0;
        int totalChars = 0;

        for (var reference : references) {
            if (attached >= MAX_ATTACHMENTS) {
                limits.add(label(reference) + " (attachment limit)");
                continue;
            }

            ReadResult read;
            try {
                read = reference.trigger() == '@'
                        ? readFile(reference.target())
                        : readType(reference.target());
            } catch (RuntimeException e) {
                LOG.error("Failed to read mention " + label(reference), e);
                read = ReadResult.problem("unreadable");
            }
            if (read.content() == null || read.content().isBlank()) {
                limits.add(label(reference) + " (" + read.problem() + ")");
                continue;
            }

            var capped = MentionContentCapper.cap(read.content(), MAX_LINES_PER_ATTACHMENT);
            String rendered = render(reference, capped);
            if (totalChars + rendered.length() > MAX_TOTAL_CHARS) {
                limits.add(label(reference) + " (context budget)");
                continue;
            }

            result.add(new SimpleContextItem(label(reference), rendered));
            totalChars += rendered.length();
            attached++;
        }

        if (!limits.isEmpty()) {
            result.add(new SimpleContextItem("Mention limits",
                    "Attached " + attached + " of " + references.size() + " mention(s). "
                    + "Not attached: " + String.join(", ", limits)));
        }
        return result;
    }

    private static String label(MentionReference reference) {
        return reference.trigger() + reference.target();
    }

    /** A file reference contains a separator or a file extension — {@code @Override} does not. */
    private static boolean looksLikeFile(String target) {
        return target.indexOf('/') >= 0
                || target.indexOf('\\') >= 0
                || FILE_EXTENSION.matcher(target).matches();
    }

    private static String render(MentionReference reference, MentionContentCapper.Capped capped) {
        var header = new StringBuilder()
                .append("=== ")
                .append(reference.trigger() == '@' ? "FILE" : "TYPE")
                .append(": ")
                .append(reference.target());
        if (capped.truncated()) {
            header.append(" (showing ")
                    .append(capped.shownLines())
                    .append(" of ")
                    .append(capped.totalLines())
                    .append(" lines)");
        }
        header.append(" ===").append(System.lineSeparator());

        var footer = new StringBuilder();
        if (capped.truncated()) {
            footer.append(System.lineSeparator())
                    .append("=== truncated: ")
                    .append(capped.totalLines() - capped.shownLines())
                    .append(" more line(s) not shown ===");
        }
        return header + FileLines.format(capped.content(), 1) + footer;
    }

    private ReadResult readFile(String path) {
        var resource = EclipseUtil.resolveInEclipse(path);
        if (resource.isPresent() && resource.get() instanceof IFile file && file.exists()) {
            var location = file.getLocation();
            if (location != null && location.toFile().length() > MAX_FILE_BYTES) {
                return ReadResult.problem("file too large");
            }
            return ReadResult.ok(IoUtils.readString(file));
        }

        // Drag-and-drop / paste of a system file outside the workspace — the user referenced it
        // explicitly, so reading it is the requested behaviour (still size-capped).
        try {
            Path diskPath = Path.of(path);
            if (!diskPath.isAbsolute() || !Files.isRegularFile(diskPath)) return ReadResult.missing();
            if (Files.size(diskPath) > MAX_FILE_BYTES) return ReadResult.problem("file too large");
            try (var reader = Files.newBufferedReader(diskPath, StandardCharsets.UTF_8)) {
                // All lines are collected, but MAX_FILE_BYTES bounds the input.
                return ReadResult.ok(reader.lines().collect(Collectors.joining(System.lineSeparator())));
            }
        } catch (InvalidPathException e) {
            return ReadResult.missing();
        } catch (IOException e) {
            LOG.error("Failed to read disk mention " + path, e);
            return ReadResult.problem("unreadable");
        }
    }

    private ReadResult readType(String fullyQualifiedName) {
        var activeProject = project == null ? null : project.get();
        if (activeProject == null) return ReadResult.problem("no active project");
        IJavaProject javaProject = JavaCore.create(activeProject);
        if (javaProject == null || !javaProject.exists()) return ReadResult.problem("not a Java project");

        IProgressMonitor monitor = new NullProgressMonitor();
        try {
            IType type = javaProject.findType(fullyQualifiedName, monitor);
            if (type == null || !type.exists()) return ReadResult.missing();

            var compilationUnit = type.getCompilationUnit();
            if (compilationUnit == null) {
                return ReadResult.ok("Type " + fullyQualifiedName + System.lineSeparator()
                        + "(binary type — source not available in the workspace)");
            }

            String source = compilationUnit.getSource();
            if (source == null) return ReadResult.missing();

            ISourceRange range = type.getSourceRange();
            if (range == null || range.getOffset() < 0 || range.getOffset() > source.length()) {
                return ReadResult.ok(source);
            }
            int start = range.getOffset();
            int end = Math.min(source.length(), start + range.getLength());
            return ReadResult.ok(source.substring(start, end));
        } catch (JavaModelException e) {
            LOG.error("Failed to read Java type " + fullyQualifiedName, e);
            return ReadResult.problem("unreadable");
        }
    }
}
