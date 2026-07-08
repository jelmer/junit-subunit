package io.github.jelmer.junitsubunit;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors);
        if (!args.excludePackage.isEmpty()) {
            builder.filters(PackageNameFilter.excludePackageNames(args.excludePackage));
        }
        if (!args.excludeClass.isEmpty()) {
            builder.filters(ClassNameFilter.excludeClassNamePatterns(
                    args.excludeClass.toArray(new String[0])));
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
        if (!args.scanRoots.isEmpty()) {
            Set<Path> roots = new LinkedHashSet<>(args.scanRoots);
            selectors.addAll(DiscoverySelectors.selectClasspathRoots(roots));
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
        out.println("Test id sources (any combination):");
        out.println("  testid...                  positional test ids (unique id, class, class#method)");
        out.println("  --load-list=PATH           newline-separated file of test ids");
        out.println("  --scan-classpath=DIR       scan a classpath root for tests");
        out.println("  --select-class=CLASS       select a class by FQN");
        out.println("  --select-package=PKG       select all tests in a package");
        out.println("  --select-method=SPEC       select a method (class#method or class#method(paramTypes))");
        out.println("  --select-unique-id=UID     select by JUnit Platform unique id");
        out.println();
        out.println("Filters (applied to discovered tests):");
        out.println("  --exclude-package=PKG      exclude tests in a package (and its subpackages)");
        out.println("  --exclude-class=REGEX      exclude classes matching a regex (fully qualified name)");
        out.println();
        out.println("Modes:");
        out.println("  --list                     enumerate matching tests without executing");
        out.println("  -h, --help                 show this help");
    }

    static final class Args {
        boolean help;
        boolean list;
        Path loadList;
        final List<Path> scanRoots = new ArrayList<>();
        final List<String> selectClass = new ArrayList<>();
        final List<String> selectPackage = new ArrayList<>();
        final List<String> selectMethod = new ArrayList<>();
        final List<String> selectUniqueId = new ArrayList<>();
        final List<String> excludePackage = new ArrayList<>();
        final List<String> excludeClass = new ArrayList<>();
        final List<String> positionalIds = new ArrayList<>();

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.equals("--")) {
                    for (int j = i + 1; j < argv.length; j++) a.positionalIds.add(argv[j]);
                    return a;
                }
                if (arg.equals("-h") || arg.equals("--help")) {
                    a.help = true;
                    continue;
                }
                if (arg.equals("-l") || arg.equals("--list")) {
                    a.list = true;
                    continue;
                }
                String value = optValue(arg, "--load-list");
                if (value != null) { a.loadList = Paths.get(value); continue; }
                value = optValue(arg, "--scan-classpath");
                if (value != null) {
                    for (String part : value.split(java.io.File.pathSeparator)) {
                        if (!part.isEmpty()) a.scanRoots.add(Paths.get(part));
                    }
                    continue;
                }
                value = optValue(arg, "--select-class");
                if (value != null) { a.selectClass.add(value); continue; }
                value = optValue(arg, "--select-package");
                if (value != null) { a.selectPackage.add(value); continue; }
                value = optValue(arg, "--select-method");
                if (value != null) { a.selectMethod.add(value); continue; }
                value = optValue(arg, "--select-unique-id");
                if (value != null) { a.selectUniqueId.add(value); continue; }
                value = optValue(arg, "--exclude-package");
                if (value != null) { a.excludePackage.add(value); continue; }
                value = optValue(arg, "--exclude-class");
                if (value != null) { a.excludeClass.add(value); continue; }
                if (arg.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option: " + arg);
                }
                a.positionalIds.add(arg);
            }
            return a;
        }

        private static String optValue(String arg, String name) {
            if (arg.equals(name)) {
                throw new IllegalArgumentException(name + " requires a value (use " + name + "=VALUE)");
            }
            String prefix = name + "=";
            if (arg.startsWith(prefix)) return arg.substring(prefix.length());
            return null;
        }
    }

}
