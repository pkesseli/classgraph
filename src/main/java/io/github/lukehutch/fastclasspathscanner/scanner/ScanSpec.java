package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.ClasspathResourceProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.EndOfClasspathResourceQueueProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

public class ScanSpec {
    /** Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package paths with "/" appended. */
    private final ArrayList<String> blacklistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package names with "." appended. */
    private final ArrayList<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    private final HashSet<String> specificallyWhitelistedClassRelativePaths = new HashSet<>();

    /** Path prefixes of whitelisted classes, or the empty list if none. */
    private final HashSet<String> specificallyWhitelistedClassParentRelativePaths = new HashSet<>();

    /** Blacklisted class relative paths. */
    private final HashSet<String> specificallyBlacklistedClassRelativePaths = new HashSet<>();

    /** Blacklisted class names. */
    private final HashSet<String> specificallyBlacklistedClassNames = new HashSet<>();

    /** Whitelisted jarfile names. (Leaf filename only.) */
    private final HashSet<String> whitelistedJars = new HashSet<>();

    /** Blacklisted jarfile names. (Leaf filename only.) */
    private final HashSet<String> blacklistedJars = new HashSet<>();

    /** Whitelisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    private final ArrayList<Pattern> whitelistedJarPatterns = new ArrayList<>();

    /** Blacklisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    private final ArrayList<Pattern> blacklistedJarPatterns = new ArrayList<>();

    /** True if jarfiles should be scanned. */
    public final boolean scanJars;

    /** True if non-jarfiles (directories) should be scanned. */
    public final boolean scanNonJars;

    /** If non-null, specifies a classpath to override the default one. */
    public String overrideClasspath;

    /** Manually-registered ClassLoaderHandlers. */
    public final HashSet<ClassLoaderHandler> extraClassLoaderHandlers = new HashSet<>();

    /**
     * A map from (className + "." + staticFinalFieldName) to StaticFinalFieldMatchProcessor(s) that should be
     * called if that class name and static final field name is encountered with a static constant initializer
     * during scan.
     */
    private Map<String, ArrayList<StaticFinalFieldMatchProcessor>> //
    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;

    /** A map from className to a list of static final fields to match with StaticFinalFieldMatchProcessors. */
    private Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch;

    public Map<String, HashSet<String>> getClassNameToStaticFinalFieldsToMatch() {
        return classNameToStaticFinalFieldsToMatch;
    }

    /** An interface used for testing if a class matches specified criteria. */
    private static interface ClassMatcher {
        public abstract void lookForMatches(ScanResult scanResult, ThreadLog log);
    }

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private ArrayList<ClassMatcher> classMatchers;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FilePathTesterAndMatchProcessorWrapper> filePathTestersAndMatchProcessorWrappers = //
            new ArrayList<>();

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJars = true;

    /**
     * By default, blacklist all java.* and sun.* packages. This means for example that you can't use
     * java.lang.Comparable as a match criterion. This can be overridden by including "!!" in the scan spec.
     * Disabling this blacklisting may increase the time or memory required to scan the classpath.
     */
    public boolean blacklistSystemPackages = true;

    /**
     * If true, ignore field visibility (affects finding classes with fields of a given type, and matching of static
     * final fields with constant initializers). If false, fields must be public to be indexed/matched.
     */
    public boolean ignoreFieldVisibility = false;

    private final List<String> constructorLogs = new ArrayList<>();

    // -------------------------------------------------------------------------------------------------------------

    public ScanSpec(final String[] scanSpec) {
        final HashSet<String> uniqueWhitelistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathPrefixes = new HashSet<>();
        boolean scanJars = true, scanNonJars = true;
        for (final String scanSpecEntry : scanSpec) {
            String spec = scanSpecEntry;
            if ("!".equals(scanSpecEntry)) {
                blacklistSystemPackages = false;
            } else if ("!!".equals(scanSpecEntry)) {
                blacklistSystemJars = false;
                blacklistSystemPackages = false;
            } else {
                final boolean blacklisted = spec.startsWith("-");
                if (blacklisted) {
                    // Strip off "-"
                    spec = spec.substring(1);
                }
                if (spec.startsWith("jar:")) {
                    // Strip off "jar:"
                    spec = spec.substring(4);
                    if (spec.indexOf('/') >= 0) {
                        constructorLogs.add("Only a leaf filename may be used with a \"jar:\" entry in the "
                                + "scan spec, got \"" + spec + "\" -- ignoring");
                    } else {
                        if (spec.isEmpty()) {
                            if (blacklisted) {
                                // Specifying "-jar:" blacklists all jars for scanning
                                scanJars = false;
                            } else {
                                // Specifying "jar:" causes only jarfiles to be scanned,
                                // while whitelisting all jarfiles
                                scanNonJars = false;
                            }
                        } else {
                            if (blacklisted) {
                                if (spec.contains("*")) {
                                    blacklistedJarPatterns.add(specToPattern(spec));
                                } else {
                                    blacklistedJars.add(spec);
                                }
                            } else {
                                if (spec.contains("*")) {
                                    whitelistedJarPatterns.add(specToPattern(spec));
                                } else {
                                    whitelistedJars.add(spec);
                                }
                            }
                        }
                    }
                } else {
                    // Convert classname format to relative path
                    final String specPath = spec.replace('.', '/');
                    // See if a class name was specified, rather than a package name. Relies on the Java convention
                    // that package names should be lower case and class names should be upper case.
                    boolean isClassName = false;
                    final int lastSlashIdx = specPath.lastIndexOf('/');
                    if (lastSlashIdx < specPath.length() - 1) {
                        isClassName = Character.isUpperCase(specPath.charAt(lastSlashIdx + 1));
                    }
                    if (isClassName) {
                        // Convert class name to classfile filename
                        if (blacklisted) {
                            specificallyBlacklistedClassNames.add(spec);
                            specificallyBlacklistedClassRelativePaths.add(specPath + ".class");
                        } else {
                            specificallyWhitelistedClassRelativePaths.add(specPath + ".class");
                        }
                    } else {
                        // This is a package name:
                        // convert into a prefix by adding '.', and also convert to path prefix
                        if (blacklisted) {
                            uniqueBlacklistedPathPrefixes.add(specPath + "/");
                        } else {
                            uniqueWhitelistedPathPrefixes.add(specPath + "/");
                        }
                    }
                }
            }
        }
        if (uniqueBlacklistedPathPrefixes.contains("/")) {
            constructorLogs.add("Ignoring blacklist of root package, it would prevent all scanning");
            uniqueBlacklistedPathPrefixes.remove("/");
        }
        uniqueWhitelistedPathPrefixes.removeAll(uniqueBlacklistedPathPrefixes);
        whitelistedJars.removeAll(blacklistedJars);
        if (!(whitelistedJars.isEmpty() && whitelistedJarPatterns.isEmpty())) {
            // Specifying "jar:somejar.jar" causes only the specified jarfile to be scanned
            scanNonJars = false;
        }
        if (!scanJars && !scanNonJars) {
            // Can't disable scanning of everything, so if specified, arbitrarily pick one to re-enable.
            constructorLogs
                    .add("Scanning of jars and non-jars are both disabled -- re-enabling scanning of non-jars");
            scanNonJars = true;
        }
        if (uniqueWhitelistedPathPrefixes.isEmpty() || uniqueWhitelistedPathPrefixes.contains("/")) {
            // Scan all packages
            whitelistedPathPrefixes.add("");
        } else {
            whitelistedPathPrefixes.addAll(uniqueWhitelistedPathPrefixes);
        }

        if (blacklistSystemPackages) {
            // Blacklist Java types by default
            uniqueBlacklistedPathPrefixes.add("java/");
            uniqueBlacklistedPathPrefixes.add("sun/");
        }
        blacklistedPathPrefixes.addAll(uniqueBlacklistedPathPrefixes);

        // Convert blacklisted path prefixes into package prefixes
        for (final String prefix : blacklistedPathPrefixes) {
            blacklistedPackagePrefixes.add(prefix.replace('/', '.'));
        }

        specificallyWhitelistedClassRelativePaths.removeAll(specificallyBlacklistedClassRelativePaths);
        for (final String whitelistedClass : specificallyWhitelistedClassRelativePaths) {
            final int lastSlashIdx = whitelistedClass.lastIndexOf('/');
            specificallyWhitelistedClassParentRelativePaths.add(whitelistedClass.substring(0, lastSlashIdx + 1));
        }

        this.scanJars = scanJars;
        this.scanNonJars = scanNonJars;
    }

    public void log(final ThreadLog log) {
        if (FastClasspathScanner.verbose) {
            for (final String msg : constructorLogs) {
                log.log(msg);
            }
            log.log("Whitelisted relative path prefixes:  " + whitelistedPathPrefixes);
            if (!blacklistedPathPrefixes.isEmpty()) {
                log.log("Blacklisted relative path prefixes:  " + blacklistedPathPrefixes);
            }
            if (!whitelistedJars.isEmpty()) {
                log.log("Whitelisted jars:  " + whitelistedJars);
            }
            if (!whitelistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + whitelistedJarPatterns);
            }
            if (!blacklistedJars.isEmpty()) {
                log.log("Blacklisted jars:  " + blacklistedJars);
            }
            if (!blacklistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + blacklistedJarPatterns);
            }
            if (!specificallyWhitelistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-whitelisted classfiles: " + specificallyWhitelistedClassRelativePaths);
            }
            if (!specificallyBlacklistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-blacklisted classfiles: " + specificallyBlacklistedClassRelativePaths);
            }
            if (!scanJars) {
                log.log("Scanning of jarfiles is disabled");
            }
            if (!scanNonJars) {
                log.log("Scanning of directories (i.e. non-jarfiles) is disabled");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Run the MatchProcessors after a scan has completed.
     */
    public void callMatchProcessors(final ScanResult scanResult,
            final LinkedBlockingQueue<ClasspathResource> matchingFiles,
            final Map<String, ClassInfo> classNameToClassInfo, final ThreadLog log) throws InterruptedException {
        // Call any FileMatchProcessors
        ClasspathResourceQueueProcessor.processClasspathResourceQueue(matchingFiles,
                new ClasspathResourceProcessor() {
                    @Override
                    public void processClasspathResource(final ClasspathResource classpathResource,
                            final InputStream inputStream, final long inputStreamLength) {
                        try {
                            classpathResource.fileMatchProcessorWrapper.processMatch(classpathResource.classpathElt,
                                    classpathResource.relativePath, inputStream, inputStreamLength);
                        } catch (final Exception e) {
                            if (FastClasspathScanner.verbose) {
                                log.log(4, "Exception while calling FileMatchProcessor for file "
                                        + classpathResource.relativePath + " : " + e);
                            }
                        }
                    }
                }, new EndOfClasspathResourceQueueProcessor() {
                    @Override
                    public void processEndOfQueue() {
                    }
                }, log);
        log.flush();

        // Call any class, interface or annotation MatchProcessors
        if (classMatchers != null) {
            for (final ClassMatcher classMatcher : classMatchers) {
                try {
                    classMatcher.lookForMatches(scanResult, log);
                } catch (final Exception e) {
                    if (FastClasspathScanner.verbose) {
                        log.log(4, "Exception while calling ClassMatchProcessor: " + e);
                    }
                }
            }
        }

        // Call any static final field match processors
        if (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors != null) {
            for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                if (classInfo.fieldValues != null) {
                    for (final Entry<String, Object> ent : classInfo.fieldValues.entrySet()) {
                        final String fieldName = ent.getKey();
                        final Object constValue = ent.getValue();
                        final String fullyQualifiedFieldName = classInfo.className + "." + fieldName;
                        final ArrayList<StaticFinalFieldMatchProcessor> staticFinalFieldMatchProcessors = //
                                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors
                                        .get(fullyQualifiedFieldName);
                        if (staticFinalFieldMatchProcessors != null) {
                            if (FastClasspathScanner.verbose) {
                                log.log(1, "Calling MatchProcessor for static final field " + classInfo.className
                                        + "." + fieldName + " = "
                                        + ((constValue instanceof Character)
                                                ? '\'' + constValue.toString().replace("'", "\\'") + '\''
                                                : (constValue instanceof String)
                                                        ? '"' + constValue.toString().replace("\"", "\\\"") + '"'
                                                        : constValue.toString()));
                            }
                            for (final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor : //
                            staticFinalFieldMatchProcessors) {
                                try {
                                    staticFinalFieldMatchProcessor.processMatch(classInfo.className, fieldName,
                                            constValue);
                                } catch (final Exception e) {
                                    if (FastClasspathScanner.verbose) {
                                        log.log(4, "Exception while calling StaticFinalFieldMatchProcessor: " + e);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert a spec with a '*' glob character into a regular expression. Replaces "." with "\." and "*" with ".*",
     * then compiles a regulare expression.
     */
    private static Pattern specToPattern(final String spec) {
        return Pattern.compile("^" + spec.replace(".", "\\.").replace("*", ".*") + "$");
    }

    /** Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path. */
    public enum ScanSpecPathMatch {
        WITHIN_BLACKLISTED_PATH, WITHIN_WHITELISTED_PATH, ANCESTOR_OF_WHITELISTED_PATH, //
        AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH, WHITELISTED_FILE, NON_WHITELISTED_FILE;
    }

    /**
     * Returns true if the given directory path is a descendant of a blacklisted path, or an ancestor or descendant
     * of a whitelisted path. The path should end in "/".
     */
    public ScanSpecPathMatch pathWhitelistMatchStatus(final String relativePath) {
        for (final String blacklistedPath : blacklistedPathPrefixes) {
            if (relativePath.startsWith(blacklistedPath)) {
                // The directory or its ancestor is blacklisted.
                return ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH;
            }
        }
        for (final String whitelistedPath : whitelistedPathPrefixes) {
            if (relativePath.startsWith(whitelistedPath)) {
                // The directory is a whitelisted path or its ancestor is a whitelisted path.
                return ScanSpecPathMatch.WITHIN_WHITELISTED_PATH;
            } else if (whitelistedPath.startsWith(relativePath) || "/".equals(relativePath)) {
                // The directory the ancestor is a whitelisted path (so need to keep scanning deeper into hierarchy)
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        if (specificallyWhitelistedClassParentRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath)) {
            return ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE;
        }
        for (final String whitelistedClassPathPrefix : specificallyWhitelistedClassParentRelativePaths) {
            if (whitelistedClassPathPrefix.startsWith(relativePath) || "/".equals(relativePath)) {
                // The directory is an ancestor of a non-whitelisted package containing a whitelisted class 
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given relative path (for a classfile name, including ".class") matches a
     * specifically-whitelisted (and non-blacklisted) classfile's relative path.
     */
    public boolean isSpecificallyWhitelistedClass(final String relativePath) {
        return (specificallyWhitelistedClassRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath));
    }

    /** Returns true if the class is not specifically blacklisted, and is not within a blacklisted package. */
    public boolean classIsNotBlacklisted(final String className) {
        if (specificallyBlacklistedClassNames.contains(className)) {
            return false;
        }
        for (final String blacklistedPackagePrefix : blacklistedPackagePrefixes) {
            if (className.startsWith(blacklistedPackagePrefix)) {
                return false;
            }
        }
        return true;
    }

    /** Checks that the named class is not blacklisted. Throws IllegalArgumentException otherwise. */
    public synchronized void checkClassIsNotBlacklisted(final String className) {
        if (!classIsNotBlacklisted(className)) {
            final boolean isSystemPackage = className.startsWith("java.") || className.startsWith("sun.");
            throw new IllegalArgumentException("Can't scan for " + className + ", it is in a blacklisted "
                    + (!isSystemPackage ? "package"
                            : "system package -- you can override this by adding \"!\" or \"!!\" to the "
                                    + "scan spec to disable system package blacklisting or system jar "
                                    + "blacklisting respectively (see the docs)"));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Test if a list of jar names contains the requested name, allowing for globs. */
    private static boolean containsJarName(final HashSet<String> jarNames, final ArrayList<Pattern> jarNamePatterns,
            final String jarName) {
        if (jarNames.contains(jarName)) {
            return true;
        }
        for (final Pattern jarNamePattern : jarNamePatterns) {
            if (jarNamePattern.matcher(jarName).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if a jarfile is whitelisted and not blacklisted. */
    public boolean jarIsWhitelisted(final String jarName) {
        return ((whitelistedJars.isEmpty() && whitelistedJarPatterns.isEmpty())
                || containsJarName(whitelistedJars, whitelistedJarPatterns, jarName))
                && !containsJarName(blacklistedJars, blacklistedJarPatterns, jarName);
    }

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJars() {
        return blacklistSystemJars;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    private synchronized <T> Class<? extends T> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> cls = (Class<? extends T>) Class.forName(className);
            return cls;
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new RuntimeException("Exception while loading or initializing class " + className, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check a class is an annotation, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the annotation.
     */
    public synchronized String getAnnotationName(final Class<?> annotation) {
        final String annotationName = annotation.getName();
        checkClassIsNotBlacklisted(annotationName);
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException(annotationName + " is not an annotation");
        }
        return annotation.getName();
    }

    /**
     * Check each element of an array of classes is an annotation, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    public synchronized String[] getAnnotationNames(final Class<?>[] annotations) {
        final String[] annotationNames = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            annotationNames[i] = getAnnotationName(annotations[i]);
        }
        return annotationNames;
    }

    /**
     * Check a class is an interface, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the interface.
     */
    public synchronized String getInterfaceName(final Class<?> iface) {
        final String ifaceName = iface.getName();
        checkClassIsNotBlacklisted(ifaceName);
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(ifaceName + " is not an interface");
        }
        return iface.getName();
    }

    /**
     * Check each element of an array of classes is an interface, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    public synchronized String[] getInterfaceNames(final Class<?>[] interfaces) {
        final String[] interfaceNames = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaceNames[i] = getInterfaceName(interfaces[i]);
        }
        return interfaceNames;
    }

    /**
     * Check a class is a regular class or interface and not an annotation, and that it is in a whitelisted package.
     * Throws IllegalArgumentException otherwise. Returns the name of the class or interface.
     */
    public synchronized String getClassOrInterfaceName(final Class<?> classOrInterface) {
        final String classOrIfaceName = classOrInterface.getName();
        checkClassIsNotBlacklisted(classOrIfaceName);
        if (classOrInterface.isAnnotation()) {
            throw new IllegalArgumentException(
                    classOrIfaceName + " is an annotation, not a regular class or interface");
        }
        return classOrInterface.getName();
    }

    /**
     * Check a class is a standard class (not an interface or annotation), and that it is in a whitelisted package.
     * Returns the name of the class if it is a standard class and it is in a whitelisted package, otherwise throws
     * an IllegalArgumentException.
     */
    public synchronized String getStandardClassName(final Class<?> cls) {
        final String className = cls.getName();
        checkClassIsNotBlacklisted(className);
        if (cls.isAnnotation()) {
            throw new IllegalArgumentException(className + " is an annotation, not a standard class");
        } else if (cls.isInterface()) {
            throw new IllegalArgumentException(cls.getName() + " is an interface, not a standard class");
        }
        return className;
    }

    /**
     * Check a class is in a whitelisted package. Returns the name of the class if it is in a whitelisted package,
     * otherwise throws an IllegalArgumentException.
     */
    public synchronized String getClassName(final Class<?> cls) {
        final String className = cls.getName();
        checkClassIsNotBlacklisted(className);
        return className;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for all standard classes, interfaces and annotations found in
     * whitelisted packages on the classpath. Calls the class loader on each matching class (using Class.forName())
     * before calling the ClassMatchProcessor.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized void matchAllClasses(final ClassMatchProcessor classMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                for (final String className : scanResult.getNamesOfAllClasses()) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all standard classes (i.e. non-interface, non-annotation classes)
     * found in whitelisted packages on the classpath. Calls the class loader on each matching class (using
     * Class.forName()) before calling the ClassMatchProcessor.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized void matchAllStandardClasses(final ClassMatchProcessor classMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                for (final String className : scanResult.getNamesOfAllStandardClasses()) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched standard class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all interface classes (interface definitions) found in whitelisted
     * packages on the classpath. Calls the class loader on each matching interface class (using Class.forName())
     * before calling the ClassMatchProcessor.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized void matchAllInterfaceClasses(final ClassMatchProcessor ClassMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                for (final String className : scanResult.getNamesOfAllInterfaceClasses()) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched interface class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    ClassMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all annotation classes (annotation definitions) found in
     * whitelisted packages on the classpath. Calls the class loader on each matching annotation class (using
     * Class.forName()) before calling the ClassMatchProcessor.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized void matchAllAnnotationClasses(final ClassMatchProcessor ClassMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                for (final String className : scanResult.getNamesOfAllAnnotationClasses()) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched annotation class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    ClassMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass. Calls the class loader on each matching class (using Class.forName()) before calling the
     * SubclassMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public synchronized <T> void matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                final String superclassName = getStandardClassName(superclass);
                for (final String subclassName : scanResult.getNamesOfSubclassesOf(superclassName)) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched subclass of " + superclassName + ": " + subclassName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subclassName);
                    // Process match
                    subclassMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath. Will call the class loader on each matching interface (using Class.forName()) before calling
     * the SubinterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     */
    public synchronized <T> void matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                final String superinterfaceName = getInterfaceName(superinterface);
                for (final String subinterfaceName : scanResult.getNamesOfSubinterfacesOf(superinterfaceName)) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched subinterface of " + superinterfaceName + ": " + subinterfaceName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subinterfaceName);
                    // Process match
                    subinterfaceMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface. Will
     * call the class loader on each matching interface (using Class.forName()) before calling the
     * InterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized <T> void matchClassesImplementing(final Class<T> implementedInterface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                final String implementedInterfaceName = getInterfaceName(implementedInterface);
                for (final String implClass : scanResult.getNamesOfClassesImplementing(implementedInterfaceName)) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched class implementing interface " + implementedInterfaceName + ": "
                                + implClass);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(implClass);
                    // Process match
                    interfaceMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for classes on the classpath that have a field of the given type.
     * Matches classes that have fields of the given type, array fields with an element type of the given type, and
     * fields of parameterized type that have a type parameter of the given type. (Does not call the classloader on
     * non-matching classes.) The field type must be declared in a package that is whitelisted (and not
     * blacklisted).
     * 
     * @param fieldType
     *            The type of the field to match..
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized <T> void matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                final String fieldTypeName = getClassName(fieldType);
                for (final String klass : scanResult.getNamesOfClassesWithFieldOfType(fieldTypeName)) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched class with field of type " + fieldTypeName + ": " + klass);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(klass);
                    // Process match
                    classMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor if classes are found on the classpath that have the specified
     * annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     */
    public synchronized void matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final ThreadLog log) {
                final String annotationName = getAnnotationName(annotation);
                for (final String classWithAnnotation : scanResult
                        .getNamesOfClassesWithAnnotation(annotationName)) {
                    if (FastClasspathScanner.verbose) {
                        log.log(3, "Matched class with annotation " + annotationName + ": " + classWithAnnotation);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(classWithAnnotation);
                    // Process match
                    classAnnotationMatchProcessor.processMatch(cls);
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered with a constant initializer value while reading a classfile header.
     */
    private synchronized void addStaticFinalFieldProcessor(final String className, final String fieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final String fullyQualifiedFieldName = className + "." + fieldName;
        if (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors == null) {
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors = new HashMap<>();
            classNameToStaticFinalFieldsToMatch = new HashMap<>();
        }
        ArrayList<StaticFinalFieldMatchProcessor> matchProcessorList = //
                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.get(fullyQualifiedFieldName);
        if (matchProcessorList == null) {
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.put(fullyQualifiedFieldName,
                    matchProcessorList = new ArrayList<>(4));
        }
        matchProcessorList.add(staticFinalFieldMatchProcessor);
        HashSet<String> staticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch.get(className);
        if (staticFinalFieldsToMatch == null) {
            classNameToStaticFinalFieldsToMatch.put(className, staticFinalFieldsToMatch = new HashSet<>());
        }
        staticFinalFieldsToMatch.add(fieldName);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a set of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public synchronized void matchStaticFinalFieldNames(final Set<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                addStaticFinalFieldProcessor(className, fieldName, staticFinalFieldMatchProcessor);
            }
        }
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match a fully-qualified field name, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public synchronized void matchStaticFinalFieldNames(final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedStaticFinalFieldName);
        matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a list of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public synchronized void matchStaticFinalFieldNames(final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedFieldName);
        }
        matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    public static class FilePathTesterAndMatchProcessorWrapper {
        FilePathTester filePathTester;
        FileMatchProcessorWrapper fileMatchProcessorWrapper;

        public FilePathTesterAndMatchProcessorWrapper(final FilePathTester filePathTester,
                final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
        }
    }

    public void addFilePathMatcher(final FilePathTester filePathTester,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        filePathTestersAndMatchProcessorWrappers
                .add(new FilePathTesterAndMatchProcessorWrapper(filePathTester, fileMatchProcessorWrapper));
    }

    List<FilePathTesterAndMatchProcessorWrapper> getFilePathTestersAndMatchProcessorWrappers() {
        return filePathTestersAndMatchProcessorWrappers;
    }
    // -------------------------------------------------------------------------------------------------------------

    private static byte[] readAllBytes(final InputStream inputStream, final long fileSize) throws IOException {
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("File larger that 2GB, cannot read contents into a Java array");
        }
        final byte[] bytes = new byte[(int) fileSize];
        final int bytesRead = inputStream.read(bytes);
        if (bytesRead < fileSize) {
            throw new IOException("Could not read whole file");
        }
        return bytes;
    }

    private static FileMatchProcessorWrapper makeFileMatchProcessorWrapper(
            final FileMatchProcessor fileMatchProcessor) {
        return new FileMatchProcessorWrapper() {
            @Override
            public void processMatch(final File classpathElt, final String relativePath,
                    final InputStream inputStream, final long fileSize) throws IOException {
                fileMatchProcessor.processMatch(relativePath, inputStream, fileSize);
            }
        };
    }

    private static FileMatchProcessorWrapper makeFileMatchProcessorWrapper(
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        return new FileMatchProcessorWrapper() {
            @Override
            public void processMatch(final File classpathElt, final String relativePath,
                    final InputStream inputStream, final long fileSize) throws IOException {
                fileMatchProcessorWithContext.processMatch(classpathElt, relativePath, inputStream, fileSize);
            }
        };
    }

    private static FileMatchProcessorWrapper makeFileMatchProcessorWrapper(
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return new FileMatchProcessorWrapper() {
            @Override
            public void processMatch(final File classpathElt, final String relativePath,
                    final InputStream inputStream, final long fileSize) throws IOException {
                fileMatchContentsProcessor.processMatch(relativePath, readAllBytes(inputStream, fileSize));
            }
        };
    }

    private static FileMatchProcessorWrapper makeFileMatchProcessorWrapper(
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return new FileMatchProcessorWrapper() {
            @Override
            public void processMatch(final File classpathElt, final String relativePath,
                    final InputStream inputStream, final long fileSize) throws IOException {
                fileMatchContentsProcessorWithContext.processMatch(classpathElt, relativePath,
                        readAllBytes(inputStream, fileSize));
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    private FilePathTester makeFilePathTesterMatchingRegexp(final String pathRegexp) {
        return new FilePathTester() {
            private final Pattern pattern = Pattern.compile(pathRegexp);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final ThreadLog log) {
                final boolean matched = pattern.matcher(relativePathStr).matches();
                if (matched && FastClasspathScanner.verbose) {
                    log.log(3, "File " + relativePathStr + " matched filename pattern " + pathRegexp);
                }
                return matched;
            }
        };
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath with the given regexp
     * pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingRegexp(pathRegexp),
                makeFileMatchProcessorWrapper(fileMatchProcessorWithContext));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingRegexp(pathRegexp),
                makeFileMatchProcessorWrapper(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath with the given
     * regexp pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingRegexp(pathRegexp),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessorWithContext));
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath with the given regexp pattern
     * in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingRegexp(pathRegexp),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    private FilePathTester makeFilePathTesterMatchingRelativePath(final String relativePathToMatch) {
        return new FilePathTester() {
            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final ThreadLog log) {
                final boolean matched = relativePathStr.equals(relativePathToMatch);
                if (matched && FastClasspathScanner.verbose) {
                    log.log(3, "Matched filename path " + relativePathToMatch);
                }
                return matched;
            }
        };
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingRelativePath(relativePathToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessorWithContext));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingRelativePath(relativePathToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingRelativePath(relativePathToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessorWithContext));
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingRelativePath(relativePathToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    private FilePathTester makeFilePathTesterMatchingPathLeaf(final String pathLeafToMatch) {
        return new FilePathTester() {
            private final String leafToMatch = pathLeafToMatch.substring(pathLeafToMatch.lastIndexOf('/') + 1);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final ThreadLog log) {
                final String relativePathLeaf = relativePathStr.substring(relativePathStr.lastIndexOf('/') + 1);
                final boolean matched = relativePathLeaf.equals(leafToMatch);
                if (matched && FastClasspathScanner.verbose) {
                    log.log(3, "File " + relativePathStr + " matched path leaf " + pathLeafToMatch);
                }
                return matched;
            }
        };
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingPathLeaf(pathLeafToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessorWithContext));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingPathLeaf(pathLeafToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingPathLeaf(pathLeafToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessorWithContext));
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public synchronized void matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingPathLeaf(pathLeafToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    private FilePathTester makeFilePathTesterMatchingFilenameExtension(final String extensionToMatch) {
        return new FilePathTester() {
            private final String suffixToMatch = "." + extensionToMatch.toLowerCase();

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath,
                    final ThreadLog log) {
                final boolean matched = relativePath.endsWith(suffixToMatch)
                        || relativePath.toLowerCase().endsWith(suffixToMatch);
                if (matched && FastClasspathScanner.verbose) {
                    log.log(3, "File " + relativePath + " matched extension ." + extensionToMatch);
                }
                return matched;
            }
        };
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingFilenameExtension(extensionToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessorWithContext));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public synchronized void matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingFilenameExtension(extensionToMatch),
                makeFileMatchProcessorWrapper(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public synchronized void matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        addFilePathMatcher(makeFilePathTesterMatchingFilenameExtension(extensionToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessorWithContext));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public synchronized void matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        addFilePathMatcher(makeFilePathTesterMatchingFilenameExtension(extensionToMatch),
                makeFileMatchProcessorWrapper(fileMatchContentsProcessor));
    }

}
