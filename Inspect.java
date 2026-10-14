import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
public class Inspect {
    public static void main(String[] args) throws Exception {
        File fabricJar = new File("C:\\Users\\Samanyu S Barna\\.gradle\\caches\\modules-2\\files-2.1\\net.fabricmc.fabric-api\\fabric-rendering-v1\\27.0.14+901a437c5d\\b7b356249f2c16fa58410e8c7694c75f0468a403\\fabric-rendering-v1-27.0.14+901a437c5d.jar");
        URL[] urls = {fabricJar.toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        try {
            Class<?> c = cl.loadClass("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
            for (Method m : c.getDeclaredMethods()) {
                System.out.println(m);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
