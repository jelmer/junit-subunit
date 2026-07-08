package io.github.jelmer.junitsubunit;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Main {

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] argv, java.io.OutputStream stdout) throws IOException {
        return run(argv, stdout, new PrintStream(java.io.OutputStream.nullOutputStream(), true));
    }

    static int run(String[] argv, java.io.OutputStream stdout, PrintStream err) throws IOException {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            err.println("junit-subunit: " + e.getMessage());
            usage(err);
            return 2;
        }
        if (args.help) {
            usage(new PrintStream(stdout, true));
            return 0;
        }

        List<DiscoverySelector> selectors = buildSelectors(args);
        if (selectors.isEmpty()) {
            err.println("junit-subunit: no selectors; supply --scan-classpath, "
                    + "--select-class, --select-package, or positional test ids");
            return 2;
        }

        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader extraLoader = null;
        if (!args.extraClasspath.isEmpty()) {
            URL[] urls = new URL[args.extraClasspath.size()];
            for (int j = 0; j < urls.length; j++) {
                urls[j] = args.extraClasspath.get(j).toUri().toURL();
            }
            extraLoader = new URLClassLoader(urls, previousLoader);
            Thread.currentThread().setContextClassLoader(extraLoader);
        }
        try {
            return runWithSelectors(args, selectors, stdout);
        } finally {
            if (extraLoader != null) {
                Thread.currentThread().setContextClassLoader(previousLoader);
                try { extraLoader.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static int runWithSelectors(Args args, List<DiscoverySelector> selectors,
                                        java.io.OutputStream stdout) throws IOException {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors);
        if (!args.includeClassname.isEmpty()) {
            builder.filters(ClassNameFilter.includeClassNamePatterns(
                    args.includeClassname.toArray(new String[0])));
        }
        if (!args.excludeClassname.isEmpty()) {
            builder.filters(ClassNameFilter.excludeClassNamePatterns(
                    args.excludeClassname.toArray(new String[0])));
        }
        if (!args.includePackage.isEmpty()) {
            builder.filters(PackageNameFilter.includePackageNames(args.includePackage));
        }
        if (!args.excludePackage.isEmpty()) {
            builder.filters(PackageNameFilter.excludePackageNames(args.excludePackage));
        }
        if (!args.includeTag.isEmpty()) {
            builder.filters(TagFilter.includeTags(args.includeTag));
        }
        if (!args.excludeTag.isEmpty()) {
            builder.filters(TagFilter.excludeTags(args.excludeTag));
        }
        if (!args.includeEngine.isEmpty()) {
            builder.filters(EngineFilter.includeEngines(args.includeEngine));
        }
        if (!args.excludeEngine.isEmpty()) {
            builder.filters(EngineFilter.excludeEngines(args.excludeEngine));
        }
        LauncherDiscoveryRequest request = builder.build();

        Launcher launcher = LauncherFactory.create();
        SubunitV2Writer writer = new SubunitV2Writer(new BufferedOutputStream(stdout));

        if (args.list) {
            TestPlan plan = launcher.discover(request);
            emitEnumeration(plan, writer);
        } else {
            launcher.registerTestExecutionListeners(new SubunitTestExecutionListener(writer));
            launcher.execute(request);
        }
        return 0;
    }

    private static void emitEnumeration(TestPlan plan, SubunitV2Writer writer) throws IOException {
        for (TestIdentifier root : plan.getRoots()) {
            walk(plan, root, writer);
        }
    }

    private static void walk(TestPlan plan, TestIdentifier id, SubunitV2Writer writer) throws IOException {
        if (id.isTest()) {
            writer.exists(id.getUniqueId());
        }
        for (TestIdentifier child : plan.getChildren(id)) {
            walk(plan, child, writer);
        }
    }

    static List<DiscoverySelector> buildSelectors(Args args) throws IOException {
        List<DiscoverySelector> selectors = new ArrayList<>();

        for (String c : args.selectClass) {
            selectors.add(DiscoverySelectors.selectClass(c));
        }
        for (String p : args.selectPackage) {
            selectors.add(DiscoverySelectors.selectPackage(p));
        }
        for (String m : args.selectMethod) {
            selectors.add(DiscoverySelectors.selectMethod(m));
        }
        for (String u : args.selectUniqueId) {
            selectors.add(DiscoverySelectors.selectUniqueId(u));
        }
        for (String u : args.selectUri) {
            selectors.add(DiscoverySelectors.selectUri(URI.create(u)));
        }
        for (String f : args.selectFile) {
            selectors.add(DiscoverySelectors.selectFile(f));
        }
        for (String d : args.selectDirectory) {
            selectors.add(DiscoverySelectors.selectDirectory(d));
        }
        for (String o : args.selectModule) {
            selectors.add(DiscoverySelectors.selectModule(o));
        }
        for (String r : args.selectResource) {
            selectors.add(DiscoverySelectors.selectClasspathResource(r));
        }
        for (String iter : args.selectIteration) {
            String id = iter.startsWith("iteration:") ? iter : "iteration:" + iter;
            selectors.add(DiscoverySelectors.parse(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "cannot parse iteration selector: " + iter)));
        }
        for (String s : args.selectIdentifier) {
            selectors.add(DiscoverySelectors.parse(s)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "cannot parse selector: " + s)));
        }
        if (!args.scanRoots.isEmpty()) {
            Set<Path> roots = new LinkedHashSet<>(args.scanRoots);
            selectors.addAll(DiscoverySelectors.selectClasspathRoots(roots));
        } else if (args.scanClasspath) {
            Set<Path> roots = new LinkedHashSet<>();
            for (Path p : args.extraClasspath) {
                if (Files.isDirectory(p)) roots.add(p);
            }
            String cp = System.getProperty("java.class.path", "");
            for (String part : cp.split(java.io.File.pathSeparator)) {
                if (part.isEmpty()) continue;
                Path p = Paths.get(part);
                if (Files.isDirectory(p)) roots.add(p);
            }
            if (!roots.isEmpty()) {
                selectors.addAll(DiscoverySelectors.selectClasspathRoots(roots));
            }
        }

        Set<String> ids = new LinkedHashSet<>(args.positionalIds);
        if (args.loadList != null) {
            for (String line : Files.readAllLines(args.loadList, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
        }
        for (String id : ids) {
            selectors.add(parseId(id));
        }
        return selectors;
    }

    static DiscoverySelector parseId(String id) {
        if (id.startsWith("[")) {
            return DiscoverySelectors.selectUniqueId(id);
        }
        int hash = id.indexOf('#');
        if (hash > 0) {
            String cls = id.substring(0, hash);
            String method = id.substring(hash + 1);
            return DiscoverySelectors.selectMethod(cls, method);
        }
        return DiscoverySelectors.selectClass(id);
    }

    static void usage(PrintStream out) {
        out.println("usage: junit-subunit [options] [testid...]");
        out.println();
        out.println("Run JUnit tests and emit subunit v2 on stdout.");
        out.println();
        out.println("Classpath:");
        out.println("  -cp, --classpath=PATH         additional classpath entries for test discovery");
        out.println("                                (repeatable, PATH-separated); aka --class-path");
        out.println();
        out.println("Selectors (any combination):");
        out.println("  testid...                     positional test ids (unique id, class, class#method)");
        out.println("  --load-list=PATH              newline-separated file of test ids");
        out.println("  --scan-classpath[=PATH]       scan classpath roots (repeatable); no arg scans");
        out.println("                                --classpath entries and the system classpath");
        out.println("  --scan-class-path[=PATH]      alias for --scan-classpath");
        out.println("  -c, --select-class=CLASS      select a class by FQN");
        out.println("  -p, --select-package=PKG      select all tests in a package");
        out.println("  -m, --select-method=SPEC      select a method (class#method or class#method(paramTypes))");
        out.println("  -o, --select-module=NAME      select a module");
        out.println("  -u, --select-uri=URI          select by URI");
        out.println("  -f, --select-file=FILE        select a file");
        out.println("  -d, --select-directory=DIR    select a directory");
        out.println("  -r, --select-resource=RES     select a classpath resource");
        out.println("  -i, --select-iteration=SPEC   select iterations (e.g. method:com.acme.Foo#m()[1..2])");
        out.println("  --select-unique-id=UID        select by JUnit Platform unique id");
        out.println("  --uid=UID                     alias for --select-unique-id");
        out.println("  --select=PREFIX:VALUE         select via a prefixed identifier (e.g. method:com.acme.Foo#m)");
        out.println();
        out.println("Filters (applied to discovered tests):");
        out.println("  -n, --include-classname=RE    include only classes whose FQN matches (repeatable)");
        out.println("  -N, --exclude-classname=RE    exclude classes whose FQN matches (repeatable)");
        out.println("  --include-package=PKG         include only tests in package (and subpackages)");
        out.println("  --exclude-package=PKG         exclude tests in package (and subpackages)");
        out.println("  -t, --include-tag=TAG         include only tests matching tag expression");
        out.println("  -T, --exclude-tag=TAG         exclude tests matching tag expression");
        out.println("  -e, --include-engine=ID       include only the given engine (repeatable)");
        out.println("  -E, --exclude-engine=ID       exclude the given engine (repeatable)");
        out.println();
        out.println("Modes:");
        out.println("  -l, --list                    enumerate matching tests without executing");
        out.println("  -h, --help                    show this help");
    }

    static final class Args {
        boolean help;
        boolean list;
        Path loadList;
        boolean scanClasspath;
        final List<Path> scanRoots = new ArrayList<>();
        final List<Path> extraClasspath = new ArrayList<>();
        final List<String> selectClass = new ArrayList<>();
        final List<String> selectPackage = new ArrayList<>();
        final List<String> selectMethod = new ArrayList<>();
        final List<String> selectUniqueId = new ArrayList<>();
        final List<String> selectUri = new ArrayList<>();
        final List<String> selectFile = new ArrayList<>();
        final List<String> selectDirectory = new ArrayList<>();
        final List<String> selectModule = new ArrayList<>();
        final List<String> selectResource = new ArrayList<>();
        final List<String> selectIteration = new ArrayList<>();
        final List<String> selectIdentifier = new ArrayList<>();
        final List<String> includeClassname = new ArrayList<>();
        final List<String> excludeClassname = new ArrayList<>();
        final List<String> includePackage = new ArrayList<>();
        final List<String> excludePackage = new ArrayList<>();
        final List<String> includeTag = new ArrayList<>();
        final List<String> excludeTag = new ArrayList<>();
        final List<String> includeEngine = new ArrayList<>();
        final List<String> excludeEngine = new ArrayList<>();
        final List<String> positionalIds = new ArrayList<>();

        static Args parse(String[] argv) {
            Args a = new Args();
            int i = 0;
            while (i < argv.length) {
                String arg = argv[i];
                if (arg.equals("--")) {
                    for (int j = i + 1; j < argv.length; j++) a.positionalIds.add(argv[j]);
                    return a;
                }
                if (arg.equals("-h") || arg.equals("--help")) {
                    a.help = true;
                    i++;
                    continue;
                }
                if (arg.equals("-l") || arg.equals("--list")) {
                    a.list = true;
                    i++;
                    continue;
                }
                if (arg.equals("--scan-classpath") || arg.equals("--scan-class-path")) {
                    a.scanClasspath = true;
                    i++;
                    continue;
                }
                if (arg.startsWith("--scan-classpath=") || arg.startsWith("--scan-class-path=")) {
                    a.scanClasspath = true;
                    String value = arg.substring(arg.indexOf('=') + 1);
                    for (String part : value.split(java.io.File.pathSeparator)) {
                        if (!part.isEmpty()) a.scanRoots.add(Paths.get(part));
                    }
                    i++;
                    continue;
                }

                OptMatch m = matchOpt(argv, i, "-cp", "--classpath", "--class-path");
                if (m != null) {
                    for (String part : m.value.split(java.io.File.pathSeparator)) {
                        if (!part.isEmpty()) a.extraClasspath.add(Paths.get(part));
                    }
                    i = m.next;
                    continue;
                }

                m = matchOpt(argv, i, "--load-list");
                if (m != null) { a.loadList = Paths.get(m.value); i = m.next; continue; }

                m = matchOpt(argv, i, "-c", "--select-class");
                if (m != null) { a.selectClass.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-p", "--select-package");
                if (m != null) { a.selectPackage.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-m", "--select-method");
                if (m != null) { a.selectMethod.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "--select-unique-id", "--uid");
                if (m != null) { a.selectUniqueId.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-u", "--select-uri");
                if (m != null) { a.selectUri.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-f", "--select-file");
                if (m != null) { a.selectFile.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-d", "--select-directory");
                if (m != null) { a.selectDirectory.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-o", "--select-module");
                if (m != null) { a.selectModule.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-r", "--select-resource");
                if (m != null) { a.selectResource.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-i", "--select-iteration");
                if (m != null) { a.selectIteration.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "--select");
                if (m != null) { a.selectIdentifier.add(m.value); i = m.next; continue; }

                m = matchOpt(argv, i, "-n", "--include-classname");
                if (m != null) { a.includeClassname.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-N", "--exclude-classname");
                if (m != null) { a.excludeClassname.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "--include-package");
                if (m != null) { a.includePackage.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "--exclude-package");
                if (m != null) { a.excludePackage.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-t", "--include-tag");
                if (m != null) { a.includeTag.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-T", "--exclude-tag");
                if (m != null) { a.excludeTag.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-e", "--include-engine");
                if (m != null) { a.includeEngine.add(m.value); i = m.next; continue; }
                m = matchOpt(argv, i, "-E", "--exclude-engine");
                if (m != null) { a.excludeEngine.add(m.value); i = m.next; continue; }

                if (arg.startsWith("-") && !arg.equals("-")) {
                    throw new IllegalArgumentException("unknown option: " + arg);
                }
                a.positionalIds.add(arg);
                i++;
            }
            return a;
        }

        private static OptMatch matchOpt(String[] argv, int i, String... names) {
            String arg = argv[i];
            for (String name : names) {
                if (arg.equals(name)) {
                    if (i + 1 >= argv.length) {
                        throw new IllegalArgumentException(name + " requires a value");
                    }
                    return new OptMatch(argv[i + 1], i + 2);
                }
                String prefix = name + "=";
                if (arg.startsWith(prefix)) {
                    return new OptMatch(arg.substring(prefix.length()), i + 1);
                }
            }
            return null;
        }

        private static final class OptMatch {
            final String value;
            final int next;

            OptMatch(String value, int next) {
                this.value = value;
                this.next = next;
            }
        }
    }

}
