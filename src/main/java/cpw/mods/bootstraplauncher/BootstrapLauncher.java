package cpw.mods.bootstraplauncher;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.SimpleJarMetadata;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BootstrapLauncher {
    private static final boolean DEBUG = System.getProperties().containsKey("bsl.debug");
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var legacyCP = Objects.requireNonNull(System.getProperty("legacyClassPath", System.getProperty("java.class.path")), "Missing legacyClassPath, cannot bootstrap");
        var ignoreList = System.getProperty("ignoreList", "/org/ow2/asm/,securejarhandler"); //TODO: find existing modules automatically instead of taking in an ignore list.
        var ignores = ignoreList.split(",");

        var previousPkgs = new HashSet<String>();
        var jars = new ArrayList<>();

        outer:
        for (var legacy : legacyCP.split(File.pathSeparator)) {
            for (var filter : ignores) {
                if (legacy.contains(filter)) {
                    if (DEBUG)
                        System.out.println(legacy + " IGNORED: " + filter);
                    continue outer;
                }
            }

            var path = Paths.get(legacy);
            if (DEBUG)
                System.out.println(path);
            var jar = SecureJar.from(new PkgTracker(Set.copyOf(previousPkgs), path), path);
            var pkgs = jar.getPackages();
            if (DEBUG)
                pkgs.forEach(p -> System.out.println("  " + p));
            previousPkgs.addAll(pkgs);
            jars.add(jar);
        }
        var finder = mergeModules(jars.toArray(SecureJar[]::new));

        var alltargets = Arrays.stream(finder).map(SecureJar::name).toList();
        var jf = JarModuleFinder.of(finder);
        var cf = ModuleLayer.boot().configuration();
        var newcf = cf.resolveAndBind(jf, ModuleFinder.ofSystem(), alltargets);
        var mycl = new ModuleClassLoader("MC-BOOTSTRAP", newcf, List.of(ModuleLayer.boot()));
        var layer = ModuleLayer.defineModules(newcf, List.of(ModuleLayer.boot()), m->mycl);
        Thread.currentThread().setContextClassLoader(mycl);

        final var loader = ServiceLoader.load(layer.layer(), Consumer.class);
        // This *should* find the service exposed by ModLauncher's BootstrapLaunchConsumer {This doc is here to help find that class next time we go looking}
        ((Consumer<String[]>)loader.stream().findFirst().orElseThrow().get()).accept(args);
    }

    private static SecureJar[] mergeModules(SecureJar[] finder) {
        // newModule=module1,module2;otherModule=module3,module4
        var mergeModules = System.getProperty("mergeModules");
        if (mergeModules == null)
            return finder;

        var moduleMap = Arrays.stream(finder).collect(Collectors.toMap(SecureJar::name, Function.identity()));
        for (var merge : mergeModules.split(";")) {
            var split = merge.split("=");
            var key = split[0];
            var targets = Arrays.stream(split[1].split(",")).map(moduleMap::remove).filter(Objects::nonNull).toList();
            var filter = targets.stream().map(SecureJar::getPathFilter).reduce(BiPredicate::or).orElseThrow();
            var paths = targets.stream().map(SecureJar::getPrimaryPath).toArray(Path[]::new);

            var newJar = SecureJar.from(sj -> new SimpleJarMetadata(key, null, sj.getPackages(), sj.getProviders()), filter, paths);
            moduleMap.put(key, newJar);
        }

        return moduleMap.values().toArray(SecureJar[]::new);
    }

    private record PkgTracker(Set<String> packages, Path paths) implements BiPredicate<String, String> {
        @Override
        public boolean test(final String path, final String basePath) {
            if (packages.isEmpty()         || // the first jar, nothing is claimed yet
                path.startsWith("META-INF/")) // Every module can have a meta-inf
                return true;

            int idx = path.lastIndexOf('/');
            return idx < 0 || // Something in the root of the module.
                idx == path.length() - 1 || // All directories can have a potential to exist without conflict, we only care about real files.
                !packages.contains(path.substring(0, idx).replace('/', '.'));
        }
    }
}
