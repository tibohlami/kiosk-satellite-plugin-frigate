// SPDX-License-Identifier: Apache-2.0
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Repackages (shades) Media3, AndroidX, Guava, and Kotlin classes under
 * me/jxl/kiosk/plugins/frigate/shaded/... so plugin DEX classes never collide
 * with R8-minified classes in the host Kiosk Satellite APK.
 */
public final class ShadeDependencies {

    private static final String[][] PREFIX_RULES = {
            {"androidx/", "me/jxl/kiosk/plugins/frigate/shaded/androidx/"},
            {"android/support/v4/", "me/jxl/kiosk/plugins/frigate/shaded/support/v4/"},
            {"com/google/common/", "me/jxl/kiosk/plugins/frigate/shaded/guava/"},
            {"com/google/thirdparty/", "me/jxl/kiosk/plugins/frigate/shaded/thirdparty/"},
            {"kotlin/", "me/jxl/kiosk/plugins/frigate/shaded/kotlin/"},
            {"org/intellij/lang/", "me/jxl/kiosk/plugins/frigate/shaded/jetbrains/intellij/"},
            {"org/jetbrains/annotations/", "me/jxl/kiosk/plugins/frigate/shaded/jetbrains/annotations/"}
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ShadeDependencies <classes-dir> <output-dir> [jar1 jar2 ...]");
            System.exit(1);
        }
        Path pluginClassesDir = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        Files.createDirectories(outputDir);

        Map<String, byte[]> inputClasses = new HashMap<>();
        collectDirClasses(pluginClassesDir, inputClasses);
        for (int i = 2; i < args.length; i++) {
            collectJarClasses(new File(args[i]), inputClasses);
        }

        final Set<String> knownInternalNames = new HashSet<>(inputClasses.keySet());
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName == null) return null;
                for (String[] rule : PREFIX_RULES) {
                    if (internalName.startsWith(rule[0])) {
                        return rule[1] + internalName.substring(rule[0].length());
                    }
                }
                return internalName;
            }

            @Override
            public Object mapValue(Object value) {
                if (value instanceof String) {
                    String s = (String) value;
                    String slashForm = s.replace('.', '/');
                    if (knownInternalNames.contains(slashForm)) {
                        String mapped = map(slashForm);
                        if (!mapped.equals(slashForm)) {
                            return s.contains("/") ? mapped : mapped.replace('/', '.');
                        }
                    }
                }
                return super.mapValue(value);
            }
        };

        for (Map.Entry<String, byte[]> entry : inputClasses.entrySet()) {
            ClassReader cr = new ClassReader(entry.getValue());
            ClassWriter cw = new ClassWriter(0);
            cr.accept(new ClassRemapper(cw, remapper), 0);
            String outName = remapper.map(entry.getKey());
            Path outFile = outputDir.resolve(outName + ".class");
            Files.createDirectories(outFile.getParent());
            try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                fos.write(cw.toByteArray());
            }
        }
        System.out.println("Shaded " + inputClasses.size() + " class files into " + outputDir);
    }

    private static void collectDirClasses(Path root, Map<String, byte[]> out) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(root).filter(Files::isRegularFile).forEach(files::add);
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
            if (!rel.endsWith(".class") || rel.startsWith("META-INF/") || rel.equals("module-info.class")) {
                continue;
            }
            String internalName = rel.substring(0, rel.length() - 6);
            out.put(internalName, Files.readAllBytes(p));
        }
    }

    private static void collectJarClasses(File jarFile, Map<String, byte[]> out) throws IOException {
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) {
                    continue;
                }
                String internalName = name.substring(0, name.length() - 6);
                if (!out.containsKey(internalName)) {
                    out.put(internalName, readAll(jf.getInputStream(e)));
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }
}
