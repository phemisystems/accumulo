/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.start.classloader.vfs;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.accumulo.start.classloader.AccumuloClassLoader;
import org.apache.accumulo.start.classloader.vfs.providers.HdfsFileProvider;
import org.apache.commons.vfs2.CacheStrategy;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.cache.SoftRefFilesCache;
import org.apache.commons.vfs2.impl.DefaultFileReplicator;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.impl.FileContentInfoFilenameFactory;
import org.apache.commons.vfs2.impl.VFSClassLoader;
import org.apache.log4j.Logger;

/**
 * This class builds a hierarchy of Classloaders in the form of:
 * 
 * <pre>
 * SystemClassLoader that loads JVM classes
 *       ^
 *       |
 * AccumuloClassLoader loads jars from locations in general.classpaths. Usually the URLs for HADOOP_HOME, ZOOKEEPER_HOME, ACCUMULO_HOME and their associated directories
 *       ^
 *       |
 * VFSClassLoader that loads jars from locations in general.vfs.classpaths.  Can be used to load accumulo jar from HDFS
 *       ^
 *       |
 * AccumuloReloadingVFSClassLoader That loads jars from locations in general.dynamic.classpaths.  Used to load jar dynamically.
 * 
 * </pre>
 * 
 * 
 */
public class AccumuloVFSClassLoader {
  
  public static class AccumuloVFSClassLoaderShutdownThread implements Runnable {

    public void run() {
      try {
	AccumuloVFSClassLoader.close();
      } catch (Exception e) {
	  //do nothing, we are shutting down anyway
      }
    }
    
  }
  
  public static final String DYNAMIC_CLASSPATH_PROPERTY_NAME = "general.dynamic.classpaths";
  
  public static final String DEFAULT_DYNAMIC_CLASSPATH_VALUE = "$ACCUMULO_HOME/lib/ext/[^.].*.jar\n";

  public static final String VFS_CLASSLOADER_SYSTEM_CLASSPATH_PROPERTY = "general.vfs.classpaths";
  
  public static final String VFS_CONTEXT_CLASSPATH_PROPERTY = "general.vfs.context.classpath.";
  
  public static final String VFS_CACHE_DIR = "general.vfs.cache.dir";
  
  private static DefaultFileSystemManager vfs = null;
  private static ClassLoader parent = null;
  private static volatile ReloadingClassLoader loader = null;
  private static final Object lock = new Object();

  private static ContextManager contextManager;

  private static Logger log = Logger.getLogger(AccumuloVFSClassLoader.class);
  
  static {
    // Register the shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(new AccumuloVFSClassLoaderShutdownThread()));      
  }
  
  public synchronized static <U> Class<? extends U> loadClass(String classname, Class<U> extension) throws ClassNotFoundException {
    try {
      return (Class<? extends U>) getClassLoader().loadClass(classname).asSubclass(extension);
    } catch (IOException e) {
      throw new ClassNotFoundException("IO Error loading class " + classname, e);
    }
  }
  
  public static Class<?> loadClass(String classname) throws ClassNotFoundException {
    return loadClass(classname, Object.class).asSubclass(Object.class);
  }

  static FileObject[] resolve(FileSystemManager vfs, String uris) throws FileSystemException {
    return resolve(vfs, uris, new ArrayList<FileObject>());
  }

  static FileObject[] resolve(FileSystemManager vfs, String uris, ArrayList<FileObject> pathsToMonitor) throws FileSystemException {
    if (uris == null)
      return new FileObject[0];

    ArrayList<FileObject> classpath = new ArrayList<FileObject>();

    pathsToMonitor.clear();

    for (String path : uris.split(",")) {
      
      path = path.trim();

      if (path.equals(""))
        continue;

      path = AccumuloClassLoader.replaceEnvVars(path, System.getenv());

      FileObject fo = vfs.resolveFile(path);
      
      switch (fo.getType()) {
        case FILE:
          classpath.add(fo);
          pathsToMonitor.add(fo);
          break;
        case FOLDER:
          pathsToMonitor.add(fo);
          for (FileObject child : fo.getChildren()) {
            classpath.add(child);
          }
          break;
        case IMAGINARY:
          // assume its a pattern
          String pattern = fo.getName().getBaseName();
          if (fo.getParent() != null && fo.getParent().getType() == FileType.FOLDER) {
            pathsToMonitor.add(fo.getParent());
            FileObject[] children = fo.getParent().getChildren();
            for (FileObject child : children) {
              if (child.getType() == FileType.FILE && child.getName().getBaseName().matches(pattern)) {
                classpath.add(child);
              }
            }
          } else {
            log.warn("ignoring classpath entry " + fo);
          }
          break;
        default:
          log.warn("ignoring classpath entry " + fo);
          break;
      }
      
    }

    return classpath.toArray(new FileObject[classpath.size()]);
  }

  private static ReloadingClassLoader createDynamicClassloader(final ClassLoader parent) throws FileSystemException, IOException {
    String dynamicCPath = AccumuloClassLoader.getAccumuloString(DYNAMIC_CLASSPATH_PROPERTY_NAME, DEFAULT_DYNAMIC_CLASSPATH_VALUE);
    
    String envJars = System.getenv("ACCUMULO_XTRAJARS");
    if (null != envJars && !envJars.equals(""))
      if (dynamicCPath != null && !dynamicCPath.equals(""))
        dynamicCPath = dynamicCPath + "," + envJars;
      else
        dynamicCPath = envJars;
    
    ReloadingClassLoader wrapper = new ReloadingClassLoader() {
      @Override
      public ClassLoader getClassLoader() {
        return parent;
      }
    };

    if (dynamicCPath == null || dynamicCPath.equals(""))
      return wrapper;

    // TODO monitor time for lib/ext was 1 sec... should this be configurable?
    return new AccumuloReloadingVFSClassLoader(dynamicCPath, vfs, wrapper, 1000, true);
  }

  public static ClassLoader getClassLoader() throws IOException {
    ReloadingClassLoader localLoader = loader;
    while (null == localLoader) {
      synchronized (lock) {
        if (null == loader) {
          
          if (null == vfs) {
            vfs = new DefaultFileSystemManager();
            vfs.addProvider("res", new org.apache.commons.vfs2.provider.res.ResourceFileProvider());
            vfs.addProvider("zip", new org.apache.commons.vfs2.provider.zip.ZipFileProvider());
            vfs.addProvider("gz", new org.apache.commons.vfs2.provider.gzip.GzipFileProvider());
            vfs.addProvider("ram", new org.apache.commons.vfs2.provider.ram.RamFileProvider());
            vfs.addProvider("file", new org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider());
            vfs.addProvider("jar", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("http", new org.apache.commons.vfs2.provider.http.HttpFileProvider());
            vfs.addProvider("https", new org.apache.commons.vfs2.provider.https.HttpsFileProvider());
            vfs.addProvider("ftp", new org.apache.commons.vfs2.provider.ftp.FtpFileProvider());
            vfs.addProvider("ftps", new org.apache.commons.vfs2.provider.ftps.FtpsFileProvider());
            vfs.addProvider("war", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("par", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("ear", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("sar", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("ejb3", new org.apache.commons.vfs2.provider.jar.JarFileProvider());
            vfs.addProvider("tmp", new org.apache.commons.vfs2.provider.temp.TemporaryFileProvider());
            vfs.addProvider("tar", new org.apache.commons.vfs2.provider.tar.TarFileProvider());
            vfs.addProvider("tbz2", new org.apache.commons.vfs2.provider.tar.TarFileProvider());
            vfs.addProvider("tgz", new org.apache.commons.vfs2.provider.tar.TarFileProvider());
            vfs.addProvider("bz2", new org.apache.commons.vfs2.provider.bzip2.Bzip2FileProvider());
            vfs.addProvider("hdfs", new HdfsFileProvider());
            vfs.addExtensionMap("jar", "jar");
            vfs.addExtensionMap("zip", "zip");
            vfs.addExtensionMap("gz", "gz");
            vfs.addExtensionMap("tar", "tar");
            vfs.addExtensionMap("tbz2", "tar");
            vfs.addExtensionMap("tgz", "tar");
            vfs.addExtensionMap("bz2", "bz2");
            vfs.addMimeTypeMap("application/x-tar", "tar");
            vfs.addMimeTypeMap("application/x-gzip", "gz");
            vfs.addMimeTypeMap("application/zip", "zip");
            vfs.setFileContentInfoFactory(new FileContentInfoFilenameFactory());
            vfs.setFilesCache(new SoftRefFilesCache());
            String cacheDirPath = AccumuloClassLoader.getAccumuloString(VFS_CACHE_DIR, "");
            File cacheDir = new File(System.getProperty("java.io.tmpdir"), "accumulo-vfs-cache-" + System.getProperty("user.name", "nouser"));
            if (!("".equals(cacheDirPath)))
              cacheDir = new File(cacheDirPath);
            vfs.setReplicator(new DefaultFileReplicator(cacheDir));
            vfs.setCacheStrategy(CacheStrategy.ON_RESOLVE);
            vfs.init();
          }
          
          // Set up the 2nd tier class loader
          if (null == parent) {
            parent = AccumuloClassLoader.getClassLoader();
          }
          
          FileObject[] vfsCP = resolve(vfs, AccumuloClassLoader.getAccumuloString(VFS_CLASSLOADER_SYSTEM_CLASSPATH_PROPERTY, ""));
          
          if (vfsCP.length == 0) {
            localLoader = createDynamicClassloader(parent);
            loader = localLoader;
            return localLoader.getClassLoader();
          }
          
          //Create the Accumulo Context ClassLoader using the DEFAULT_CONTEXT
          localLoader = createDynamicClassloader(new VFSClassLoader(vfsCP, vfs, parent));
          loader = localLoader;
        }
      }
    }

    return localLoader.getClassLoader();
  }
  
  public static void printClassPath() {
    try {
      ClassLoader cl = getClassLoader();
      ArrayList<ClassLoader> classloaders = new ArrayList<ClassLoader>();
      
      while (cl != null) {
        classloaders.add(cl);
        cl = cl.getParent();
      }
      
      Collections.reverse(classloaders);
      
      int level = 0;
      
      for (ClassLoader classLoader : classloaders) {
        if (level > 0)
          System.out.println();
        System.out.print("Level " + level + " ");
        level++;
        
        if (classLoader instanceof URLClassLoader) {
          // If VFS class loader enabled, but no contexts defined.
          URLClassLoader ucl = (URLClassLoader) classLoader;
          System.out.println("URL classpath items are:");

            for (URL u : ucl.getURLs()) {
              System.out.println("\t" + u.toExternalForm());
            }

        } else if (classLoader instanceof VFSClassLoader) {
          System.out.println("VFS classpaths items are:");
          VFSClassLoader vcl = (VFSClassLoader) classLoader;
          for (FileObject f : vcl.getFileObjects()) {
            System.out.println("\t" + f.getURL().toExternalForm());
          }
        } else {
          System.out.println("Unknown classloader configuration " + classLoader.getClass());
        }
      }
      
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  
  public static synchronized ContextManager getContextManager() throws IOException {
    if (contextManager == null) {
      getClassLoader();
      contextManager = new ContextManager(vfs, new ReloadingClassLoader() {
        @Override
        public ClassLoader getClassLoader() {
          try {
            return AccumuloVFSClassLoader.getClassLoader();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      });
    }

    return contextManager;
  }

  public static void close() {
    if (null != vfs)
      vfs.close();
  }
}
