package com.sourcegraph.utils;

import com.sourcegraph.lsp.domain.structures.SymbolKind;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LanguageUtils {

    public static Element getTopLevelClass(Element element) {
        Element highestClass = null;
        for (; element != null; element = element.getEnclosingElement()) {
            ElementKind kind = element.getKind();
            if (isTopLevel(kind)) {
                highestClass = element;
            }
        }
        return highestClass;
    }

    public static boolean isTopLevel(ElementKind kind) {
        return kind.isClass() || kind.isInterface();
    }

    public static String getParentUri(String uri) {
        return pathToUri(uriToPath(uri).getParent().toString());
    }

    public static Path uriToPath(String uri) {
        if (uri.startsWith("file://")) {
            String hostAndPath = uri.substring("file://".length());
            String components[] = StringUtils.split(hostAndPath, '/');
            for (int i = 0; i < components.length; i++) {
                try {
                    components[i] = URLDecoder.decode(components[i], StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) {
                    // does not happen
                }
            }
            uri = '/' + StringUtils.join(components, '/');
            return Paths.get(uri);
        }
        return Paths.get(URI.create(uri));
    }

    public static String pathToUri(String path) {
        path = FilenameUtils.separatorsToUnix(path);
        String components[] = StringUtils.split(path, '/');
        for (int i = 0; i < components.length; i++) {
            try {
                components[i] = URLEncoder.encode(components[i], StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                // does not happen
            }
        }
        return "file:///" + StringUtils.join(components, '/');
    }

    public static String vfsPath(Path p) {
        return FilenameUtils.separatorsToUnix(p.toString()).replaceAll("\\/+", "/");
    }

    /**
     * Joins path-like strings in a semantically correct manner (i.e., joining an absolute path will just return
     * the absolute path).
     * @param root     root element
     * @param elements optional following elements
     * @return elements concatenated to form a POSIX-style path
     */
    public static String joinPath(String root, String... elements) {
        StringBuilder ret = new StringBuilder(trailingSlash(FilenameUtils.separatorsToUnix(root)));
        for (String element : elements) {
            if (element.isEmpty()) {
                continue;
            }
            element = trailingSlash(FilenameUtils.separatorsToUnix(element));
            if (element.startsWith("/")) {
                ret = new StringBuilder(element);
            } else {
                ret.append(element);
            }
        }
        // remove trailing slash
        ret.setLength(ret.length() - 1);
        return ret.toString();
    }

    /**
     * Joins two path-like strings by simply concatenating them regardless of the semantics of the path. Tries not to
     * add more slashes between path elements if they're not necessary.
     * @param left left path
     * @param right right path
     * @return the joined path
     */
    public static String concatPath(String left, String right) {
        if (!left.endsWith("/") && !right.startsWith("/")) {
            return left + "/" + right;
        } else if (left.equals("file:///") && right.startsWith("/")) {
            return left;
        } else {
            return left + right;
        }
    }

    private static String trailingSlash(String s) {
        return s.endsWith("/") ? s : s + '/';
    }

    public static boolean startsWith(Path p, Collection<Path> prefixes) {
        String vfsP = vfsPath(p);
        for (Path prefix : prefixes) {
            if (vfsP.startsWith(vfsPath(prefix))) {
                return true;
            }
        }
        return false;
    }

    public static boolean startsWith(Path p, Path q) {
        return vfsPath(p).startsWith(vfsPath(q));
    }

    public static boolean uriContainsOrEquals(String parent, String child) {
        if (parent.equals(child)) {
            return true;
        }
        if (!parent.endsWith("/")) {
            parent = parent + "/";
        }
        return child.startsWith(parent);
    }

    public static String getPackageName(Element element) {
        for (; element != null; element = element.getEnclosingElement()) {
            if (element instanceof PackageElement) {
                return ((PackageElement) element).getQualifiedName().toString();
            }
        }
        return null;
    }

    public static String getQualifiedName(Element element) {
        return getQualifiedName(element, false);
    }

    public static String getCrossRepoQualifiedName(Element element) {
        return getQualifiedName(element, true);
    }

    private static String getQualifiedName(Element element, boolean crossRepo) {
        List<String> names = new ArrayList<>();
        for (; element != null; element = element.getEnclosingElement()) {
            if (element instanceof QualifiedNameable) {
                names.add(((QualifiedNameable) element).getQualifiedName().toString());
                break;
            }
            // construct a simpler signature for cross-repo method lookups -- see comment for getCrossRepoMethodName
            if (crossRepo && element instanceof ExecutableElement) {
                names.add(getCrossRepoMethodName((ExecutableElement) element));
            } else {
                names.add(element.toString());
            }
        }
        Collections.reverse(names);
        return String.join(".", names);
    }

    /**
     * The signatures returned for workspace/symbol and textDocument/xDefinition don't quite match because the
     * former are constructed from raw parse trees, while the latter are constructed after a full type-check. This
     * will cause cross-repo lookups to fail, so we need xDefinition to return simplified method signatures that
     * match what workspace/symbol returns, in the case where an externally-defined method is requested.
     *
     * @return simplified method signature matching what would be returned by SymbolVisitor::getMethodString
     */
    private static String getCrossRepoMethodName(ExecutableElement element) {

        StringBuilder methodSig = new StringBuilder();

        String typeParams = element.getTypeParameters().stream()
                .map(TypeParameterElement::getSimpleName)
                .map(Name::toString)
                .reduce((l, r) -> l + "," + r)
                .orElse("");
        if (!typeParams.isEmpty()) {
            methodSig.append('<').append(typeParams).append('>');
        }

        String name = element.getSimpleName().toString();
        if ("<init>".equals(name)) {
            name = element.getEnclosingElement().getSimpleName().toString();
        }
        methodSig.append(name);

        String params = element.getParameters().stream()
                .map(VariableElement::asType)
                .map(TypeMirror::toString)
                .map(param -> param.contains(".") ? StringUtils.substringAfterLast(param, ".") : param)
                .reduce((l, r) -> l + "," + r)
                .orElse("");
        methodSig.append('(').append(params).append(')');

        // KLUDGE -- turning a type into a string sometimes puts spaces after commas, sometimes not -- this seems
        // to depend mostly on whether we're serializing Trees or TypeMirrors. Just normalize it here so that it'll
        // match what workspace/symbol returns via SymbolVisitor::getMethodString
        return methodSig.toString().replace(", ", ",");
    }

    public static String getElementSignature(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.PACKAGE) {
            return "package " + element.toString();
        } else if (kind == ElementKind.ANNOTATION_TYPE) {
            return getModifiersPrefix(element) + "@interface " + element.asType();
        } else if (kind == ElementKind.CLASS) {
            return getModifiersPrefix(element) + "class " + element.asType();
        } else if (kind == ElementKind.INTERFACE) {
            return getModifiersPrefix(element) + "interface " + element.asType();
        } else if (kind == ElementKind.CONSTRUCTOR) {
            return getMethodSignature(element);
        } else if (kind == ElementKind.ENUM) {
            return getModifiersPrefix(element) + "enum " + element.asType();
        } else if (kind == ElementKind.ENUM_CONSTANT ||
                kind == ElementKind.EXCEPTION_PARAMETER ||
                kind == ElementKind.FIELD ||
                kind == ElementKind.LOCAL_VARIABLE ||
                kind == ElementKind.PARAMETER) {
            return getModifiersPrefix(element) + element.asType().toString() + ' ' + element.toString();

        } else if (kind == ElementKind.METHOD) {
            return getMethodSignature(element);
        }
        return element.toString();
    }

    public static String getMethodSignature(Element element) {

        String name = element.getSimpleName().toString();
        boolean ctor = name.equals("<init>");
        StringBuilder sig = new StringBuilder(getModifiersPrefix(element));
        TypeMirror typeMirror = element.asType();
        ExecutableType methodType = (ExecutableType) typeMirror;
        ExecutableElement executableElement = (ExecutableElement) element;
        if (!ctor) {
            sig.append(getTypeSignature(methodType.getReturnType()));
        }
        ArrayList<String> typeVarSigs = executableElement.getTypeParameters().stream()
                .map(LanguageUtils::getElementSignature)
                .map(String::trim)
                .collect(Collectors.toCollection(ArrayList::new));
        if (!typeVarSigs.isEmpty()) {
            sig.append(" <");
            sig.append(String.join(", ", typeVarSigs));
            sig.append(">");
        }
        if (!ctor) {
            sig.append(' ').append(name);
        } else {
            sig.append(element.getEnclosingElement().getSimpleName());
        }
        sig.append('(');
        ArrayList<String> paramSigs = executableElement.getParameters().stream()
                .map(LanguageUtils::getElementSignature)
                .map(String::trim)
                .collect(Collectors.toCollection(ArrayList::new));
        sig.append(String.join(", ", paramSigs));
        sig.append(")");
        ArrayList<String> thrownSigs = executableElement.getThrownTypes().stream()
                .map(LanguageUtils::getTypeSignature)
                .collect(Collectors.toCollection(ArrayList::new));
        if (!thrownSigs.isEmpty()) {
            sig.append(" throws ");
            sig.append(String.join(", ", thrownSigs));
        }
        return sig.toString();
    }

    public static String getTypeSignature(TypeMirror typeMirror) {

        if (typeMirror == null) return "";

        StringBuilder sig = new StringBuilder();
        switch (typeMirror.getKind()) {

            case DECLARED:
                // TODO: treat generic classes differently? We might need to use the TypeElement instead.
                sig.append(typeMirror.toString());
                break;

            case EXECUTABLE:
                ExecutableType methodType = (ExecutableType) typeMirror;
                ArrayList<String> typeVarSigs = methodType.getTypeVariables().stream()
                        .map(LanguageUtils::getTypeSignature)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!typeVarSigs.isEmpty()) {
                    sig.append("<");
                    sig.append(String.join(", ", typeVarSigs));
                    sig.append("> ");
                }
                TypeMirror receiverType = methodType.getReceiverType();
                if (receiverType != null && receiverType.getKind() != TypeKind.NONE) {
                    sig.append(getTypeSignature(receiverType));
                    sig.append("::");
                }
                sig.append("(");
                ArrayList<String> paramSigs = methodType.getParameterTypes().stream()
                        .map(LanguageUtils::getTypeSignature)
                        .collect(Collectors.toCollection(ArrayList::new));
                sig.append(String.join(", ", paramSigs));
                sig.append(") -> ");
                sig.append(getTypeSignature(methodType.getReturnType()));
                ArrayList<String> thrownSigs = methodType.getThrownTypes().stream()
                        .map(LanguageUtils::getTypeSignature)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!thrownSigs.isEmpty()) {
                    sig.append(" throws ");
                    sig.append(String.join(", ", thrownSigs));
                }
                break;

            default:
                sig.append(typeMirror.toString());
                break;
        }
        return sig.toString();
    }

    public static SymbolKind toSymbolKind(ElementKind elementKind) {
        if (elementKind == null) return null;
        switch (elementKind) {
            case INTERFACE:
                return SymbolKind.INTERFACE;
            case CLASS:
                return SymbolKind.CLASS;
            case PACKAGE:
                return SymbolKind.PACKAGE;
            case METHOD:
                return SymbolKind.METHOD;
            case CONSTRUCTOR:
                return SymbolKind.CONSTRUCTOR;
            case FIELD:
                return SymbolKind.FIELD;
            case ENUM:
                return SymbolKind.ENUM;
            default:
                return null;
        }
    }

    private static String getModifiersPrefix(Element element) {
        Collection<Modifier> modifiers = element.getModifiers();
        if (CollectionUtils.isEmpty(modifiers)) {
            return StringUtils.EMPTY;
        }
        return StringUtils.join(modifiers, " ") + ' ';
    }


    private static final String[] INCLUDE_SUFFIXES = {
            ".java",
            "/pom.xml",
            ".gradle",
            ".properties",
            "/javaconfig.json",
            "/AndroidManifest.xml", // sometimes needed by Gradle scripts
            ".groovy" // sometimes needed if Gradle plugin are run directly from the included source files
    };

    private static final String[] INCLUDE_SUBSTRINGS = {
            "gradle", // keep anything in a Gradle folder ... figure out how to tighten up this filter
            "/buildSrc/" // sometimes needed for the Gradle build process
    };

    private static final String[] EXCLUDE_SUBSTRINGS = {
            "/src/main/resources/",
            "/src/test/resources/",
            "/META-INF/"
    };

    /**
     * getRelevantFiles filters the input allUris down to just the files that are relevant to the language server's
     * analysis. E.g., we exclude most non-Java files (with exceptions made for files relevant to extracting
     * build information).
     */
    public static HashSet<String> getRelevantFiles(Stream<String> allUris) {
        return allUris.filter(LanguageUtils::isRelevantFile).collect(Collectors.toCollection(HashSet::new));
    }

    public static boolean isRelevantFile(String uri) {
        return (StringUtils.endsWithAny(uri, INCLUDE_SUFFIXES) || StringUtils.containsAny(uri, INCLUDE_SUBSTRINGS)) &&
                (uri.contains("/buildSrc/") || !StringUtils.containsAny(uri, EXCLUDE_SUBSTRINGS)) &&
                !uri.endsWith(".jar");
    }
}
