/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atomos.tests.modulepath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.atomos.framework.AtomosBundleInfo;
import org.atomos.framework.AtomosLayer;
import org.atomos.framework.AtomosRuntime;
import org.atomos.framework.AtomosRuntime.LoaderType;
import org.atomos.tests.testbundles.service.contract.Echo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;

public class ModulepathLaunchTest
{

    /**
     *
     */
    private static final String ATOMOS_VERSION = "0.0.1-SNAPSHOT";
    private static final String TESTBUNDLES_SERVICE_IMPL = "atomos.tests.testbundles.service.impl";
    private static final String TESTBUNDLES_SERVICE_IMPL_A = "atomos.tests.testbundles.service.impl.a";
    private static final String TESTBUNDLES_SERVICE_IMPL_B = "atomos.tests.testbundles.service.impl.b";
    private static final String TESTBUNDLES_SERVICE_LIBRARY = "atomos.tests.testbundles.service.library";
    private static final String TESTBUNDLES_SERVICE_USER = "atomos.tests.testbundles.service.user";
    private static final String TESTBUNDLES_RESOURCE_A = "atomos.tests.testbundles.resource.a";
    /**
     *
     */
    private static final String RESSOURCE_A_CLAZZ_NAME = "org." + TESTBUNDLES_RESOURCE_A + ".Clazz";
    private Framework testFramework;

    @AfterEach
    void afterTest() throws BundleException, InterruptedException, IOException
    {
        if (testFramework != null && testFramework.getState() == Bundle.ACTIVE)
        {
            testFramework.stop();
            testFramework.waitForStop(10000);
        }

    }

    private AtomosBundleInfo assertFindBundle(String name, AtomosLayer layer,
        AtomosLayer expectedLayer, boolean expectedToFind)
    {
        final Optional<AtomosBundleInfo> result = layer.findAtomosBundle(name);
        if (expectedToFind)
        {
            assertTrue(result.isPresent(), "Could not find bundle: " + name);
            assertEquals(name, result.get().getSymbolicName(), "Wrong name");
            assertEquals(expectedLayer, result.get().getAtomosLayer(),
                "Wrong layer for bundle: " + name);
        }
        else
        {
            assertFalse(result.isPresent(), "Found unexpected bundle: " + name);
        }
        return result.orElse(null);
    }

    private void checkBundleStates(Bundle[] bundles)
    {
        assertTrue(bundles.length > 0, "No bundles: " + Arrays.toString(bundles));
        for (final Bundle b : bundles)
        {
            final String msg = b.getBundleId() + " " + b.getLocation() + ": "
                + b.getSymbolicName() + ": " + getState(b);
            System.out.println(msg);
            int expected;
            if ("osgi.annotation".equals(b.getSymbolicName())
                || "org.osgi.service.component.annotations".equals(b.getSymbolicName()))
            {
                expected = Bundle.INSTALLED;
            }
            else
            {
                expected = Bundle.ACTIVE;
            }
            if (b.getState() != expected && expected == Bundle.ACTIVE)
            {
                // for debugging
                try
                {
                    b.start();
                }
                catch (final Throwable t)
                {
                    t.printStackTrace();
                }
            }
            assertEquals(expected, b.getState(), "Wrong bundle state for bundle: " + msg);
        }
    }

    private void checkClassBundle(Object service, ServiceReference<?> ref)
    {
        final Class<?> serviceClass = service.getClass();
        final Bundle b = FrameworkUtil.getBundle(serviceClass);
        assertEquals(ref.getBundle(), b, "Wrong bundle.");
        assertEquals(b.getSymbolicName(), service.getClass().getModule().getName(),
            "Wrong module name");
    }

    private void checkLayer(AtomosLayer atomosLayer, LoaderType loaderType, int id)
    {
        assertEquals(id, atomosLayer.getId(), "Wrong id.");
        assertEquals(loaderType, atomosLayer.getLoaderType(), "Wrong loaderType");
        assertEquals(loaderType.toString(), atomosLayer.getName(), "Wrong name.");
    }

    private void checkLoader(AtomosRuntime runtime, AtomosLayer layer,
        LoaderType loaderType) throws ClassNotFoundException
    {
        final Set<AtomosBundleInfo> atomosBundles = layer.getAtomosBundles();
        final List<Class<?>> classes = new ArrayList<>();
        for (final AtomosBundleInfo atomosBundle : atomosBundles)
        {
            final Bundle b = runtime.getBundle(atomosBundle);
            assertNotNull(b, "No bundle found: " + atomosBundle.getSymbolicName());
            final String name = getTestClassName(b);
            assertNotNull(name, "No class name.");
            classes.add(b.loadClass(name));
        }
        final Set<ClassLoader> classLoaders = new HashSet<>();
        for (final Class<?> clazz : classes)
        {
            classLoaders.add(clazz.getClassLoader());
        }
        switch (loaderType)
        {
            case OSGI:
                for (final ClassLoader classLoader : classLoaders)
                {
                    assertTrue(classLoader instanceof BundleReference,
                        "Class loader is not a BundleReference");
                }
                assertEquals(5, classLoaders.size(), "Wrong number of class loaders.");
                break;
            case MANY:
                for (final ClassLoader classLoader : classLoaders)
                {
                    assertFalse(classLoader instanceof BundleReference,
                        "Class loader is a BundleReference");
                }
                assertEquals(5, classLoaders.size(), "Wrong number of class loaders.");
                break;
            case SINGLE:
                assertEquals(1, classLoaders.size(), "Wrong number of class loaders.");
                break;

            default:
                fail();
        }
    }

    private void checkServices(BundleContext bc, int expectedNumber)
        throws InvalidSyntaxException
    {
        final ServiceReference<?>[] echoRefs = bc.getAllServiceReferences(
            Echo.class.getName(), null);
        assertNotNull(echoRefs, "No Echo service ref found.");
        assertEquals(expectedNumber, echoRefs.length, "Wrong number of services.");
        for (final ServiceReference<?> ref : echoRefs)
        {
            final Echo echo = (Echo) bc.getService(ref);
            assertNotNull(echo, "No Echo service found.");
            assertEquals(ref.getProperty("type") + " Hello!!", echo.echo("Hello!!"),
                "Wrong Echo.");
            checkClassBundle(echo, ref);
        }
    }

    private ClassLoader getCLForResourceTests(Path storage) throws BundleException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath(),
                AtomosRuntime.ATOMOS_MODULES_DIR
                + "=target/modules/" + TESTBUNDLES_RESOURCE_A
                + "-" + ATOMOS_VERSION + ".jar" });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        checkBundleStates(bc.getBundles());

        final ServiceReference<AtomosRuntime> atomosRef = bc.getServiceReference(
            AtomosRuntime.class);
        final AtomosRuntime atomos = bc.getService(atomosRef);
        final AtomosLayer bootLayer = atomos.getBootLayer();
        final Set<AtomosLayer> children = bootLayer.getChildren();
        assertNotNull(children, "Null children.");
        assertEquals(1, children.size(), "Wrong number of children.");

        final AtomosLayer child = children.iterator().next();
        assertEquals(1, child.getAtomosBundles().size(), "Wrong number of bundles.");
        Module serviceLibModule = null;
        for (final AtomosBundleInfo atomosBundle : child.getAtomosBundles())
        {
            if (atomosBundle.getSymbolicName().equals(
                TESTBUNDLES_RESOURCE_A))
            {
                serviceLibModule = atomosBundle.adapt(Module.class).get();
            }
        }
        return serviceLibModule.getClassLoader();
    }

    private String getState(Bundle b)
    {
        switch (b.getState())
        {
            case Bundle.UNINSTALLED:
                return "UNINSTALLED";
            case Bundle.INSTALLED:
                return "INSTALLED";
            case Bundle.RESOLVED:
                return "RESOLVED";
            case Bundle.STARTING:
                return "STARTING";
            case Bundle.ACTIVE:
                return "ACTIVE";
            case Bundle.STOPPING:
                return "STOPPING";
            default:
                return "unknown";
        }
    }

    private String getTestClassName(Bundle b)
    {
        switch (b.getSymbolicName())
        {
            case TESTBUNDLES_SERVICE_IMPL_A:
                return "org." + TESTBUNDLES_SERVICE_IMPL_A + ".EchoImpl";
            case TESTBUNDLES_SERVICE_IMPL_B:
                return "org." + TESTBUNDLES_SERVICE_IMPL_B + ".EchoImpl";
            case TESTBUNDLES_SERVICE_LIBRARY:
                return "org." + TESTBUNDLES_SERVICE_LIBRARY + ".SomeUtil";
            case TESTBUNDLES_SERVICE_USER:
                return "org." + TESTBUNDLES_SERVICE_USER + ".EchoUser";
            case TESTBUNDLES_RESOURCE_A:
                return RESSOURCE_A_CLAZZ_NAME;
            default:
                fail("Unknown");
        }
        return null;
    }

    private AtomosLayer installChild(AtomosLayer parent, String name,
        AtomosRuntime atomosRuntime, LoaderType loaderType) throws BundleException
    {
        final File modules = new File("target/modules");
        assertTrue(modules.isDirectory(), "Modules directory does not exist: " + modules);

        final AtomosLayer child = atomosRuntime.addLayer(List.of(parent), name,
            loaderType, modules.toPath());

        final List<Bundle> bundles = new ArrayList<>();
        for (final AtomosBundleInfo atomosBundle : child.getAtomosBundles())
        {
            bundles.add(atomosBundle.install("child"));
        }
        for (final Bundle b : bundles)
        {
            b.start();
        }

        return child;
    }

    @Test
    void testAddNewLayers(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException, InterruptedException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath(),
                AtomosRuntime.ATOMOS_MODULES_DIR + "=target/modules" });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");
        final Bundle[] bundles = bc.getBundles();
        final int originalNum = bundles.length;
        assertTrue(bundles.length > 0, "No bundles: " + Arrays.toString(bundles));

        checkServices(bc, 4);

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));

        final Collection<AtomosLayer> layers = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            layers.add(installChild(atomosRuntime.getBootLayer(), "services-" + i,
                atomosRuntime, LoaderType.OSGI));
        }
        checkServices(bc, 44);

        checkBundleStates(bc.getBundles());

        final List<Bundle> allChildBundles = layers.stream().flatMap(
            (l) -> l.getAtomosBundles().stream()).map(
                (a) -> atomosRuntime.getBundle(a)).filter(Objects::nonNull).collect(
                    Collectors.toList());

        final AtomosLayer firstChild = layers.iterator().next();
        final Set<AtomosBundleInfo> firstChildInfos = firstChild.getAtomosBundles();

        List<Bundle> firstChildBundles = firstChildInfos.stream().map(
            (a) -> atomosRuntime.getBundle(a)).filter(Objects::nonNull).collect(
                Collectors.toList());

        assertEquals(5, firstChildBundles.size(),
            "Wrong number of bundles in first child.");
        firstChildBundles.forEach((b) -> {
            try
            {
                b.uninstall();
            }
            catch (final BundleException e)
            {
                throw new RuntimeException(e);
            }
        });

        firstChildBundles.forEach((b) -> {
            assertNull(atomosRuntime.getAtomosBundle(b.getLocation()),
                "No AtomsBundle expected.");
        });

        firstChildBundles = firstChildInfos.stream().map(
            (a) -> atomosRuntime.getBundle(a)).filter(Objects::nonNull).collect(
                Collectors.toList());
        assertEquals(0, firstChildBundles.size(),
            "Wrong number of bundles in first child.");

        layers.forEach((l) -> {
            try
            {
                l.uninstall();
            }
            catch (final BundleException e)
            {
                throw new RuntimeException(e);
            }
        });
        checkServices(bc, 4);

        allChildBundles.forEach((b) -> {
            assertNull(atomosRuntime.getAtomosBundle(b.getLocation()),
                "No AtomsBundle expected.");
        });

        assertEquals(originalNum, bc.getBundles().length,
            "Wrong number of final bundles.");
    }

    @Test
    void testFindBundle(@TempDir Path storage) throws BundleException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        final AtomosLayer child = installChild(atomosRuntime.getBootLayer(), "SINGLE",
            atomosRuntime, LoaderType.SINGLE);
        assertFindBundle("java.base", child, atomosRuntime.getBootLayer(), true);
        assertFindBundle(TESTBUNDLES_SERVICE_IMPL_A, child, child, true);
        assertFindBundle(TESTBUNDLES_SERVICE_IMPL, child,
            atomosRuntime.getBootLayer(), true);
        assertFindBundle(TESTBUNDLES_SERVICE_IMPL_A,
            atomosRuntime.getBootLayer(), null, false);
    }

    @Test
    void testGetEntry(@TempDir Path storage) throws BundleException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        final AtomosLayer child = installChild(atomosRuntime.getBootLayer(), "SINGLE",
            atomosRuntime, LoaderType.SINGLE);
        final Bundle b = atomosRuntime.getBundle(
            child.findAtomosBundle(TESTBUNDLES_SERVICE_IMPL_A).get());
        assertNotNull(b, "No bundle found.");
        URL mf = b.getEntry("/META-INF/MANIFEST.MF");
        assertNotNull(mf, "No manifest found.");
        mf = b.getEntry("META-INF/MANIFEST.MF");
        assertNotNull(mf, "No manifest found.");
    }

    @Test
    void testInstallDifferentPrefix(@TempDir Path storage) throws BundleException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        final AtomosLayer child = installChild(atomosRuntime.getBootLayer(), "SINGLE",
            atomosRuntime, LoaderType.SINGLE);
        final AtomosBundleInfo ab = child.findAtomosBundle(
            TESTBUNDLES_SERVICE_IMPL_A).get();
        Bundle b = atomosRuntime.getBundle(ab);
        assertNotNull(b, "No bundle found.");

        try
        {
            ab.install("shouldFail");
            fail("Should not be able to install with different prefix");
        }
        catch (final BundleException e)
        {
            // expected
        }

        final Bundle existing = ab.install("child");
        assertNotNull(existing, "No bundle.");
        assertEquals(b, existing, "Existing bundle doesn't equal original.");

        // now try to uninstall and use a different prefix
        existing.uninstall();
        b = ab.install("testPrefix");
        assertTrue(b.getLocation().startsWith("testPrefix:"),
            "Wrong location prefix: " + b.getLocation());
        b.start();
    }

    @Test
    void testInvalidUseOfRuntime(
        @TempDir Path storage)
            throws BundleException, InterruptedException
    {
        final File storage1 = new File(storage.toFile(), "s1");
        storage1.mkdirs();
        final File storage2 = new File(storage.toFile(), "s2");
        storage2.mkdirs();

        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '='
                + storage1.getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));

        Framework f = null;
        try
        {
            f = atomosRuntime.newFramework(
                Map.of(Constants.FRAMEWORK_STORAGE, storage2.getAbsolutePath()));
            f.start();
            fail();
        }
        catch (final IllegalStateException e)
        {
            // expected
        }
        finally
        {
            if (f != null)
            {
                f.stop();
            }
        }
    }

    @Test
    void testLoaderType(@TempDir Path storage) throws BundleException,
    InvalidSyntaxException, InterruptedException, ClassNotFoundException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        checkLoader(atomosRuntime, installChild(atomosRuntime.getBootLayer(), "OSGI",
            atomosRuntime, LoaderType.OSGI), LoaderType.OSGI);
        checkLoader(atomosRuntime, installChild(atomosRuntime.getBootLayer(), "SINGLE",
            atomosRuntime, LoaderType.SINGLE), LoaderType.SINGLE);
        checkLoader(atomosRuntime, installChild(atomosRuntime.getBootLayer(), "MANY",
            atomosRuntime, LoaderType.MANY), LoaderType.MANY);
    }

    @Test
    void testLoadFromModule(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException, InterruptedException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath(),
                AtomosRuntime.ATOMOS_MODULES_DIR + "=target/modules" });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");
        checkBundleStates(bc.getBundles());

        final ServiceReference<AtomosRuntime> atomosRef = bc.getServiceReference(
            AtomosRuntime.class);
        assertNotNull(atomosRef, "No Atomos runtime.");

        final AtomosRuntime atomos = bc.getService(atomosRef);
        assertNotNull(atomos, "Null Atomos runtime.");
        final AtomosLayer bootLayer = atomos.getBootLayer();
        assertNotNull(bootLayer, "The boot layer is null.");
        final Set<AtomosLayer> children = bootLayer.getChildren();
        assertNotNull(children, "Null children.");
        assertEquals(1, children.size(), "Wrong number of children.");

        final AtomosLayer child = children.iterator().next();
        assertEquals(5, child.getAtomosBundles().size(), "Wrong number of bundles.");
        Module serviceLibModule = null;
        for (final AtomosBundleInfo atomosBundle : child.getAtomosBundles())
        {
            if (atomosBundle.getSymbolicName().equals(
                TESTBUNDLES_SERVICE_LIBRARY))
            {
                serviceLibModule = atomosBundle.adapt(Module.class).get();
            }
        }
        try
        {
            final Class<?> clazz = serviceLibModule.getClassLoader().loadClass(
                "org." + TESTBUNDLES_SERVICE_LIBRARY + ".SomeUtil");
            assertNotNull(clazz, "Null class from loadClass.");
        }
        catch (final Exception e)
        {
            fail("Failed to find class: " + e.getMessage());
        }
    }

    @Test
    void testModuleDirServices(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException, InterruptedException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath(),
                AtomosRuntime.ATOMOS_MODULES_DIR + "=target/modules" });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");
        checkBundleStates(bc.getBundles());

        checkServices(bc, 4);
    }

    @Test
    void testModulePathServices(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");
        checkBundleStates(bc.getBundles());

        checkServices(bc, 2);
    }

    @Test
    void testPersistLayers(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException, InterruptedException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime1 = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        installChild(atomosRuntime1.getBootLayer(), "SINGLE", atomosRuntime1,
            LoaderType.SINGLE);
        installChild(atomosRuntime1.getBootLayer(), "MANY", atomosRuntime1,
            LoaderType.MANY);
        installChild(atomosRuntime1.getBootLayer(), "OSGI", atomosRuntime1,
            LoaderType.OSGI);

        checkBundleStates(bc.getBundles());
        checkServices(bc, 8);

        testFramework.stop();
        testFramework.waitForStop(10000);

        // try starting the framework directly again
        testFramework.start();
        bc = testFramework.getBundleContext();

        final AtomosLayer child1 = installChild(atomosRuntime1.getBootLayer(), "SINGLE2",
            atomosRuntime1, LoaderType.SINGLE);

        checkServices(bc, 10);

        testFramework.stop();
        testFramework.waitForStop(10000);

        testFramework.start();
        bc = testFramework.getBundleContext();
        checkServices(bc, 10);

        child1.uninstall();
        checkServices(bc, 8);

        testFramework.stop();
        testFramework.waitForStop(10000);

        // test persistent load with a new Runtime
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        checkServices(bc, 8);

        final AtomosRuntime atomosRuntime2 = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        final AtomosLayer bootLayer = atomosRuntime2.getBootLayer();
        assertEquals(3, bootLayer.getChildren().size(), "Wrong number of children.");

        final List<AtomosLayer> children = bootLayer.getChildren().stream().sorted(
            (l1, l2) -> Long.compare(l1.getId(), l2.getId())).collect(
                Collectors.toList());
        checkLayer(children.get(0), LoaderType.SINGLE, 2);
        checkLayer(children.get(1), LoaderType.MANY, 3);
        checkLayer(children.get(2), LoaderType.OSGI, 4);

        // uninstall service.impl.a bundle from the first child
        children.iterator().next().getAtomosBundles().stream().map(
            (a) -> atomosRuntime2.getBundle(a)).filter(Objects::nonNull).filter(
                (b) -> b.getSymbolicName().equals(
                    TESTBUNDLES_SERVICE_IMPL_A)).findFirst().orElseThrow().uninstall();
        checkServices(bc, 7);

        testFramework.stop();
        testFramework.waitForStop(10000);

        // startup with the option not to force install all atomos bundles
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath(),
                AtomosRuntime.ATOMOS_BUNDLE_INSTALL + "=false" });
        testFramework = ModulepathLaunch.getFramework();
        bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        checkServices(bc, 7);
    }

    @Test
    void testReferenceUser(@TempDir Path storage)
        throws BundleException, InvalidSyntaxException
    {
        ModulepathLaunch.main(new String[] {
                Constants.FRAMEWORK_STORAGE + '=' + storage.toFile().getAbsolutePath() });
        testFramework = ModulepathLaunch.getFramework();
        final BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");

        final AtomosRuntime atomosRuntime = bc.getService(
            bc.getServiceReference(AtomosRuntime.class));
        final AtomosLayer child = installChild(atomosRuntime.getBootLayer(), "testRef",
            atomosRuntime, LoaderType.MANY);
        checkServices(bc, 4);
        final AtomosBundleInfo ab = child.findAtomosBundle(
            TESTBUNDLES_SERVICE_USER).get();
        final Bundle b = atomosRuntime.getBundle(ab);
        assertNotNull(b, "No bundle found.");

        final ServiceReference<?>[] refs = b.getRegisteredServices();
        assertNotNull(refs, "No services.");
        assertEquals(1, refs.length, "Wrong number of services.");
        assertEquals(Boolean.TRUE, refs[0].getProperty("echo.reference"),
            "Wrong service.");
    }

    @Test
    void testResourceGetMissingResource(@TempDir Path storage)
        throws ClassNotFoundException, BundleException
    {
        try
        {
            final Class<?> clazz = getCLForResourceTests(storage).loadClass(
                RESSOURCE_A_CLAZZ_NAME);
            final URL u = clazz.getResource("/META-TEXT/noFile.txt");
            assertNull(u, "get of non-existent resource should return null.");
        }
        catch (final ClassNotFoundException e)
        {
            fail("Failed to find class: " + e.getMessage());
        }
    }

    @Test
    void testResourceLoadResource(@TempDir Path storage)
        throws ClassNotFoundException, BundleException
    {
        try
        {
            final Class<?> clazz = getCLForResourceTests(storage).loadClass(
                RESSOURCE_A_CLAZZ_NAME);
            final URL resc = clazz.getResource("/META-TEXT/file.txt");
            assertTrue(resc != null, "Expected URL, got null ");
            assertTrue(resc.getFile() != null, "Could not get resource from URL");
        }
        catch (final ClassNotFoundException e)
        {
            fail("Failed to find class: " + e.getMessage());
        }
    }

    @Test
    void testResourcePackagedResource(@TempDir Path storage)
        throws ClassNotFoundException, BundleException, IOException
    {
        try
        {
            final Class<?> clazz = getCLForResourceTests(storage).loadClass(
                RESSOURCE_A_CLAZZ_NAME);
            final URL resc = clazz.getResource("file.txt");
            assertNotNull(resc, "Expected URL, got null ");
            assertEquals("/org/atomos/tests/testbundles/resource/a/file.txt",
                new BufferedReader(new InputStreamReader(resc.openStream())).readLine(),
                "Incorrect contents from URL");
        }
        catch (final ClassNotFoundException e)
        {
            fail("Failed to find class: " + e.getMessage());
        }
    }

    @Test
    void testResourceRootResource(@TempDir Path storage)
        throws ClassNotFoundException, BundleException, IOException
    {
        try
        {
            final Class<?> clazz = getCLForResourceTests(storage).loadClass(
                RESSOURCE_A_CLAZZ_NAME);
            final URL resc = clazz.getResource("/file.txt");
            assertTrue(resc != null, "Expected URL, got null ");
            assertTrue(new BufferedReader(
                new InputStreamReader(resc.openStream())).readLine().equals("/file.txt"),
                "Incorrect contents from URL");
        }
        catch (final ClassNotFoundException e)
        {
            fail("Failed to find class: " + e.getMessage());
        }
    }

}
