package cpw.mods.bootstraplauncher;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public class BootstrapLauncher {
    private static final boolean DEBUG = System.getProperties().containsKey("bsl.debug");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var legacyClasspath = loadLegacyClassPath();
        System.setProperty("legacyClassPath", String.join(File.pathSeparator, legacyClasspath));

        var ignoreList = System.getProperty("ignoreList", "asm,securejarhandler"); // TODO: find existing modules automatically instead of taking in an ignore list.
        var ignores = ignoreList.split(",");

        var previousPackages = new HashSet<String>();
        var jars = new ArrayList<SecureJar>();
        var filenameMap = getMergeFilenameMap();
        var mergeMap = new HashMap<Integer, List<Path>>();

        outer:
        for (var legacy : legacyClasspath) {
            var path = Paths.get(legacy);
            var filename = path.getFileName().toString();

            for (var filter : ignores) {
                if (filename.startsWith(filter)) {
                    if (DEBUG) {
                        System.out.println("bsl: file '" + legacy + "' ignored because filename starts with '" + filter + "'");
                    }
                    continue outer;
                }
            }

            if (DEBUG) {
                System.out.println("bsl: encountered path '" + legacy + "'");
            }

            if (filenameMap.containsKey(filename)) {
                if (DEBUG) {
                    System.out.println("bsl: path is contained with module #" + filenameMap.get(filename) + ", skipping for now");
                }
                mergeMap.computeIfAbsent(filenameMap.get(filename), k -> new ArrayList<>()).add(path);
                continue;
            }

            var jar = SecureJar.from(new PackageTracker(Set.copyOf(previousPackages), path), path);
            var packages = jar.getPackages();

            if (DEBUG) {
                System.out.println("bsl: list of packages for file '" + legacy + "'");
                packages.forEach(p -> System.out.println("bsl:    " + p));
            }

            previousPackages.addAll(packages);
            jars.add(jar);
        }

        mergeMap.forEach((idx, paths) -> {
            var pathsArray = paths.toArray(Path[]::new);
            var jar = SecureJar.from(new PackageTracker(Set.copyOf(previousPackages), pathsArray), pathsArray);
            var packages = jar.getPackages();

            if (DEBUG) {
                System.out.println("bsl: the following paths are merged together in module #" + idx);
                paths.forEach(path -> System.out.println("bsl:    " + path));
                System.out.println("bsl: list of packages for module #" + idx);
                packages.forEach(p -> System.out.println("bsl:    " + p));
            }

            previousPackages.addAll(packages);
            jars.add(jar);
        });
        var secureJarsArray = jars.toArray(SecureJar[]::new);

        var allTargets = Arrays.stream(secureJarsArray).map(SecureJar::name).toList();
        var jarModuleFinder = JarModuleFinder.of(secureJarsArray);
        var bootModuleConfiguration = ModuleLayer.boot().configuration();
        var bootstrapConfiguration = bootModuleConfiguration.resolveAndBind(jarModuleFinder, ModuleFinder.ofSystem(), allTargets);
        var moduleClassLoader = new ModuleClassLoader("MC-BOOTSTRAP", bootstrapConfiguration, List.of(ModuleLayer.boot()));
        var layer = ModuleLayer.defineModules(bootstrapConfiguration, List.of(ModuleLayer.boot()), m -> moduleClassLoader);
        Thread.currentThread().setContextClassLoader(moduleClassLoader);

        final var loader = ServiceLoader.load(layer.layer(), Consumer.class);
        // This *should* find the service exposed by ModLauncher's BootstrapLaunchConsumer {This doc is here to help find that class next time we go looking}
        ((Consumer<String[]>) loader.stream().findFirst().orElseThrow().get()).accept(args);
    }

    private static Map<String, Integer> getMergeFilenameMap() {
        // filename1.jar,filename2.jar;filename2.jar,filename3.jar
        var mergeModules = System.getProperty("mergeModules");
        if (mergeModules == null)
            return Map.of();

        Map<String, Integer> filenameMap = new HashMap<>();
        int i = 0;
        for (var merge : mergeModules.split(";")) {
            var targets = merge.split(",");
            for (String target : targets) {
                filenameMap.put(target, i);
            }
            i++;
        }

        return filenameMap;
    }

    private record PackageTracker(Set<String> packages, Path... paths) implements BiPredicate<String, String> {
        @Override
        public boolean test(final String path, final String basePath) {
            if (packages.isEmpty() || // the first jar, nothing is claimed yet
                path.startsWith("META-INF/")) // Every module can have a meta-inf
                return true;

            int idx = path.lastIndexOf('/');
            return idx < 0 || // Something in the root of the module.
                idx == path.length() - 1 || // All directories can have a potential to exist without conflict, we only care about real files.
                !packages.contains(path.substring(0, idx).replace('/', '.'));
        }
    }

    private static List<String> loadLegacyClassPath() {
        var legacyCpPath = System.getProperty("legacyClassPath.file");

        if (legacyCpPath != null) {
            var legacyCPFileCandidatePath = Paths.get(legacyCpPath);
            if (Files.exists(legacyCPFileCandidatePath) && Files.isRegularFile(legacyCPFileCandidatePath)) {
                try {
                    return Files.readAllLines(legacyCPFileCandidatePath);
                }
                catch (IOException e) {
                    throw new IllegalStateException("Failed to load the legacy class path from the specified file: " + legacyCpPath, e);
                }
            }
        }

        var legacyClasspath = System.getProperty("legacyClassPath", System.getProperty("java.class.path"));
        Objects.requireNonNull(legacyClasspath, "Missing legacyClassPath, cannot bootstrap");
        return Arrays.asList(legacyClasspath.split(File.pathSeparator));
    }
}
