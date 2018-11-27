package com.sourcegraph.langserver.langservice.compiler;

import com.sourcegraph.lsp.domain.structures.Location;
import com.sourcegraph.lsp.domain.structures.Position;
import com.sourcegraph.lsp.domain.structures.Range;
import com.sourcegraph.utils.LanguageUtils;
import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;

public class PositionCalculator {

    private static Logger log = LoggerFactory.getLogger(PositionCalculator.class);

    private SourcePositions sourcePositions;

    private CompilationUnitTree compilationUnit;

    private String fileName;

    private String fileUri;

    private String content;

    private HashMap<Integer, Position> positions;

    public PositionCalculator(SourcePositions sourcePositions, CompilationUnitTree compilationUnit) {
        this.sourcePositions = sourcePositions;
        this.compilationUnit = compilationUnit;
        JavaFileObject file = compilationUnit.getSourceFile();
        this.fileName = file.getName();
        this.fileUri = LanguageUtils.pathToUri(Paths.get(fileName).toString());
        try {
            this.content = file.getCharContent(true).toString();
        } catch (IOException exception) {
            log.error("Unable to extract content of {}", fileName);
            throw new RuntimeException(exception);
        }
        this.positions = new HashMap<>();
    }

    public Range getRange(Tree tree) {
        int startOffset = getStartOffset(tree);
        int endOffset = getEndOffset(tree);
        if (endOffset < 0) endOffset = startOffset;
        return new Range()
                .withStart(positions.computeIfAbsent(startOffset, this::calculatePosition))
                .withEnd(positions.computeIfAbsent(endOffset, this::calculatePosition));
    }

    public Location getLocation(MethodTree tree, String name) {
        return new Location()
                .withUri(fileUri)
                .withRange(getRange(getBoundingBox(tree, name)));
    }

    public Location getLocation(Tree tree) {

        Range range;

        if (tree instanceof ClassTree) {
            range = getRange(getBoundingBox((ClassTree) tree));
        } else if (tree instanceof MethodTree) {
            range = getRange(getBoundingBox((MethodTree) tree));
        } else if (tree instanceof VariableTree) {
            range = getRange(getBoundingBox((VariableTree) tree));
        } else if (tree instanceof MemberSelectTree) {
            range = getRange(getBoundingBox((MemberSelectTree) tree));
        } else {
            range = getRange(tree);
        }

        return new Location()
                .withUri(fileUri)
                .withRange(range);
    }

    public Pair<Integer, Integer> getBoundingBox(ClassTree tree) {
        String name = tree.getSimpleName().toString();
        // the modifiers might contain the classname itself (e.g., if a class is self-annotated), so exclude the
        // modifiers when searching for the classname
        int offset = -1;
        if (tree.getModifiers() != null) {
            offset = getEndOffset(tree.getModifiers());
        }
        if (offset == -1) {
            // end offset of tree modifiers may be null too, see
            // https://github.com/sourcegraph/java-langserver/issues/148
            offset = getStartOffset(tree);
        }
        int startOffset = content.indexOf(name, offset);
        return Pair.of(startOffset, startOffset + name.length());
    }

    public Pair<Integer, Integer> getBoundingBox(MethodTree tree) {
        return getBoundingBox(tree, tree.getName().toString());
    }

    public Pair<Integer, Integer> getBoundingBox(MemberSelectTree tree) {
        String name = tree.getIdentifier().toString();
        int startOffset = content.indexOf(name, getEndOffset(tree.getExpression(), tree));
        return Pair.of(startOffset, startOffset + name.length());
    }

    public Pair<Integer, Integer> getBoundingBox(VariableTree tree) {
        String name = tree.getName().toString();
        int startOffset = content.indexOf(name, getEndOffset(tree.getType(), tree));
        return Pair.of(startOffset, startOffset + name.length());
    }

    public Pair<Integer, Integer> getBoundingBox(MethodTree tree, String name) {
        int startOffset;
        if (tree.getReturnType() != null) {
            startOffset = content.indexOf(name, getEndOffset(tree.getReturnType(), tree));
        } else if (tree.getModifiers() != null) {
            startOffset = content.indexOf(name, getEndOffset(tree.getModifiers(), tree));
        } else {
            startOffset = content.indexOf(name, getStartOffset(tree));
        }
        return Pair.of(startOffset, startOffset + name.length());
    }

    public JavaSourceRange getJavaSourceRange(Tree tree) {
        int startOffset = getStartOffset(tree);
        int endOffset = getEndOffset(tree);
        if (endOffset < 0) endOffset = startOffset;
        return new JavaSourceRange()
                .withFileName(fileName)
                .withStartOffset(startOffset)
                .withEndOffset(endOffset);
    }

    private Position calculatePosition(int rawOffset) {
        if (rawOffset < 0 || rawOffset > content.length()) {
            return new Position().withLine(-1).withCharacter(-1);
        }
        String precedingText = content.substring(0, rawOffset);
        int linesPreceding = StringUtils.countMatches(precedingText, '\n');
        int charsPreceding = StringUtils.substringAfterLast(precedingText, "\n").length();
        return new Position()
                .withLine(linesPreceding)
                .withCharacter(charsPreceding);
    }

    private int getStartOffset(Tree tree) {
        // would prefer not to cast, but all the string manipulation methods take ints
        return (int) sourcePositions.getStartPosition(compilationUnit, tree);
    }

    private int getEndOffset(Tree tree) {
        // would prefer not to cast, but all the string manipulation methods take ints
        return (int) sourcePositions.getEndPosition(compilationUnit, tree);
    }

    /**
     * @param tree tree
     * @param parent paremt tree
     * @return end offset of a given tree or start offset of a parent tree if the former is NOPOS
     */
    private int getEndOffset(Tree tree, Tree parent) {
        int ret = getEndOffset(tree);
        return ret == Diagnostic.NOPOS ? getStartOffset(parent) : ret;
    }

    public static int positionToOffset(CharSequence content, Position position) {

        int currentLine = 0;
        int offset = position.getCharacter();

        if (currentLine == position.getLine()) return offset;

        for (int i = 0; i < content.length(); ++i) {
            if (content.charAt(i) == '\n') {
                ++currentLine;
                if (currentLine == position.getLine()) return i + 1 + offset;
            }
        }

        return -1;
    }

    private Range getRange(Pair<Integer, Integer> offsets) {
        return new Range()
                .withStart(calculatePosition(offsets.getLeft()))
                .withEnd(calculatePosition(offsets.getRight()));
    }
}
